package dev.pocket.agent;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.enums.V8AwaitMode;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.NodeRuntime;
import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.node.modules.NodeModuleModule;
import com.caoccao.javet.values.reference.V8ValueObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.json.JSONObject;

/** One isolate and one owning thread for main and every logical worker. */
public final class RuntimeHost implements AutoCloseable {
    private final Context context;
    private final StateStore store;
    private final MemoryStore memory;
    private final AutonomyStore autonomy;
    private final CompletableFuture<Void> closeCompletion=new CompletableFuture<>();
    private final CredentialStore credentials;
    private final Consumer<JSONObject> listener;
    private final HandlerThread thread = new HandlerThread("PocketNode");
    private final Handler handler;
    private NativeTools tools;
    private final ActiveWorkWakeLock workWakeLock;
    private NodeRuntime runtime;
    private boolean active, cleanupOnly, pumpScheduled, shutdownAcknowledged, shutdownClean;
    private int cleanupWakeMs=200;
    private long pumpDueAtMs;
    private volatile boolean closing, closed, terminalRuntimeFailure;
    private int runtimeRestartAttempts;
    private static final int MAX_RUNTIME_RESTARTS=2;
    private static final int MAX_DURABLE_STATE_CHARS=24_000_000;
    private static final int MAX_DURABLE_STATE_BYTES=64_000_000;
    private File durableSaveFile;
    private FileOutputStream durableSaveOutput;
    private int durableSaveExpected=-1, durableSaveReceived, durableSaveBytes;
    private CompletableFuture<JSONObject> gate;
    private final java.util.Map<String, CompletableFuture<JSONObject>> debugPending = new java.util.HashMap<>();
    private final java.util.Set<CompletableFuture<?>> pendingRequests =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private final Runnable pump = this::pumpEvents;
    private void pumpEvents() {
        pumpScheduled = false;
        pumpDueAtMs = 0;
        if (closed || closing || runtime == null) return;
        try { runtime.await(V8AwaitMode.RunNoWait); }
        catch (Throwable error) { poisonAndRestart("Node event processing failed", error); return; }
        schedulePump();
    }
    public RuntimeHost(Context context, Consumer<JSONObject> listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        store = new StateStore(this.context); autonomy=new AutonomyStore(this.context); memory = new MemoryStore(this.context); credentials = new CredentialStore(this.context);
        workWakeLock = new ActiveWorkWakeLock(this.context);
        thread.start(); handler = new Handler(thread.getLooper());
    }
    private void createRuntime() throws Exception {
        store.markInterrupted();
        tools = new NativeTools(context);
        runtime = V8Host.getNodeI18nInstance().createV8Runtime();
        runtime.getNodeModule(NodeModuleModule.class).setRequireRootDirectory(context.getFilesDir());
        try (V8ValueObject bridge = runtime.createV8ValueObject()) {
            bridge.bind(new Bridge()); runtime.getGlobalObject().set("PocketNative", bridge);
        }
        File bundle = new File(context.getFilesDir(), "runtime.cjs");
        try (InputStream input = context.getAssets().open("runtime.cjs"); FileOutputStream output = new FileOutputStream(bundle)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        // CommonJS preserves full Node built-ins; never evaluate this as a WebView script.
        runtime.getExecutor("process.on('unhandledRejection',()=>PocketNative.emit(JSON.stringify({type:'error',error:'Unhandled runtime rejection'})));require(" + JSONObject.quote(bundle.getAbsolutePath()) + ");").executeVoid();
        runtime.await(V8AwaitMode.RunNoWait); schedulePump();
    }
    private void closeRuntimeOnly() {
        workWakeLock.setActive(false);
        NodeRuntime doomed=runtime;runtime=null;
        if(doomed!=null) {
            try {doomed.setStopping(true);} catch(Throwable ignored) {}
            try {doomed.close();} catch(Throwable ignored) {}
        }
        NativeTools oldTools=tools;tools=null;
        if(oldTools!=null)try {oldTools.close();} catch(Throwable ignored) {}
    }
    private void abortDurableSave() {
        FileOutputStream output=durableSaveOutput;durableSaveOutput=null;
        if(output!=null)try {output.close();} catch(Exception ignored) {}
        File file=durableSaveFile;durableSaveFile=null;
        if(file!=null)try {file.delete();} catch(RuntimeException ignored) {}
        durableSaveExpected=-1;durableSaveReceived=0;durableSaveBytes=0;
    }
    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pendingRequests.add(future);
        future.whenComplete((value,error)->pendingRequests.remove(future));
        return future;
    }
    private void completePending(Throwable reason) {
        for (CompletableFuture<?> pending : java.util.List.copyOf(pendingRequests)) pending.completeExceptionally(reason);
        for (CompletableFuture<JSONObject> pending : debugPending.values()) pending.completeExceptionally(reason);
        debugPending.clear();
        if (gate != null) {gate.completeExceptionally(reason);gate=null;}
    }
    private static boolean javetFailure(Throwable error) {
        for(Throwable current=error;current!=null;current=current.getCause())
            if(current instanceof JavetException)return true;
        return false;
    }
    private void handleRuntimeEntryFailure(String message,Throwable error) {
        if(javetFailure(error)&&!closing&&!closed)poisonAndRestart(message,error);
        else fail(message,error);
    }
    private void terminalFailure(String message,Throwable error) {
        terminalRuntimeFailure=true;active=false;pumpScheduled=false;
        closeRuntimeOnly();fail(message,error);
        try {
            JSONObject event=new JSONObject().put("type","runtime_terminal_failure")
                .put("error","Runtime recovery exhausted; restart Pocket Agent to retry");
            store.record(event);listener.accept(event);
        } catch(Throwable ignored) {}
    }
    private void scheduleRestart(Throwable previousFailure) {
        if(closing||closed)return;
        int attempt=++runtimeRestartAttempts;
        if(attempt>MAX_RUNTIME_RESTARTS) {terminalFailure("Runtime restart budget exhausted",previousFailure);return;}
        boolean accepted=handler.postDelayed(() -> {
            if(closing||closed||runtime!=null)return;
            try {createRuntime();terminalRuntimeFailure=false;}
            catch(Throwable restartFailure) {
                fail("Runtime restart failed",restartFailure);active=false;closeRuntimeOnly();
                scheduleRestart(restartFailure);
            }
        },250L*attempt);
        if(!accepted)terminalFailure("Runtime thread rejected restart",previousFailure);
    }
    private void poisonAndRestart(String message,Throwable error) {
        fail(message,error);active=false;pumpScheduled=false;
        try {store.markInterrupted();} catch(Throwable ignored) {}
        completePending(new IllegalStateException("Runtime restarted after event-loop failure"));
        closeRuntimeOnly();
        scheduleRestart(error);
    }
    private void attemptStart(CompletableFuture<Void> result,Throwable previousFailure) {
        if(closing||closed){result.completeExceptionally(new IllegalStateException("Runtime is closing"));return;}
        try {createRuntime();terminalRuntimeFailure=false;result.complete(null);}
        catch(Throwable error) {
            fail(previousFailure==null?"Runtime startup failed":"Runtime startup retry failed",error);
            closeRuntimeOnly();
            int attempt=++runtimeRestartAttempts;
            if(attempt>MAX_RUNTIME_RESTARTS) {
                terminalFailure("Runtime startup recovery exhausted",error);result.completeExceptionally(error);return;
            }
            if(!handler.postDelayed(()->attemptStart(result,error),250L*attempt)) {
                terminalFailure("Runtime thread rejected startup retry",error);
                result.completeExceptionally(error);
            }
        }
    }
    public CompletableFuture<Void> start() {
        CompletableFuture<Void> result=track(new CompletableFuture<>());
        runtimeRestartAttempts=0;
        boolean accepted=handler.post(()->attemptStart(result,null));
        if(!accepted)result.completeExceptionally(new IllegalStateException("Runtime thread is unavailable"));
        return result;
    }
    public CompletableFuture<Void> recover() {
        CompletableFuture<Void> result=track(new CompletableFuture<>());
        boolean accepted=handler.post(()->{
            if(closing||closed){result.completeExceptionally(new IllegalStateException("Runtime is closing"));return;}
            if(runtime!=null){terminalRuntimeFailure=false;result.complete(null);return;}
            terminalRuntimeFailure=false;runtimeRestartAttempts=0;attemptStart(result,null);
        });
        if(!accepted)result.completeExceptionally(new IllegalStateException("Runtime thread is unavailable"));
        return result;
    }
    public boolean terminalFailure(){return terminalRuntimeFailure;}
    public CompletableFuture<JSONObject> dispatch(JSONObject command) {
        CompletableFuture<JSONObject> result = track(new CompletableFuture<>());
        final String serialized = command.toString();
        boolean accepted=handler.post(() -> {
            if (closed || closing || runtime == null) {
                result.completeExceptionally(new IllegalStateException(terminalRuntimeFailure
                    ?"Runtime recovery is exhausted; restart Pocket Agent to retry":"Runtime is not running")); return;
            }
            try {
                String response = runtime.getExecutor("(()=>{const value=PocketAgent.dispatch(" + JSONObject.quote(serialized) + ");return typeof value==='string'?value:JSON.stringify(value)})()").executeString();
                runtime.await(V8AwaitMode.RunNoWait); schedulePump();
                JSONObject outcome = response == null ? new JSONObject().put("ok", true) : new JSONObject(response);
                if (outcome.optBoolean("ok") && ("new_main".equals(command.optString("action")) || "stop_all".equals(command.optString("action"))))
                    VoiceConversationInvitation.cancel(context, "chat_or_runtime_changed");
                result.complete(outcome);
            } catch (Throwable error) {
                handleRuntimeEntryFailure("Agent command failed",error);
                result.completeExceptionally(error);
            }
        });
        if(!accepted)result.completeExceptionally(new IllegalStateException("Runtime thread is unavailable"));
        return result;
    }
    private void schedulePump() {
        if (closed || closing || !(active || gate != null || !debugPending.isEmpty())) return;
        // A display-cleanup-only runtime has referenced Node retry timers but no
        // interactive work. Wake for their nearest deadline, not every 16 ms.
        boolean cleanupWait=active&&cleanupOnly&&gate==null&&debugPending.isEmpty();
        int delay=cleanupWait?Math.max(1,Math.min(cleanupWakeMs,5000)):16;
        long dueAt=android.os.SystemClock.uptimeMillis()+delay;
        if(pumpScheduled) {
            if(dueAt>=pumpDueAtMs)return;
            handler.removeCallbacks(pump);
        }
        pumpDueAtMs=dueAt;
        pumpScheduled=handler.postDelayed(pump,delay);
    }
    private void emit(JSONObject event) {
        String type = event.optString("type");
        if (type.equals("shutdown_ready")) {
            shutdownAcknowledged=true;shutdownClean=event.optBoolean("clean",false);
        } else if (type.equals("dev_tool_result")) {
            CompletableFuture<JSONObject> pending = debugPending.remove(event.optString("requestId"));
            if (pending != null) pending.complete(event.optJSONObject("result"));
            return;
        }
        if (type.equals("state")) {
            JSONObject state = event.optJSONObject("state");
            if (state != null) {
                try {store.save(state);} catch(Throwable persistenceFailure) {android.util.Log.w("PocketRuntime","State snapshot persistence failed",persistenceFailure);}
                active = state.optBoolean("active", state.optBoolean("busy"));
                cleanupOnly = state.optBoolean("cleanupOnly",false);
                cleanupWakeMs = state.optInt("cleanupWakeMs",200);
            }
        } else if (type.equals("activity")) {
            active = event.optBoolean("active");
            cleanupOnly = event.optBoolean("cleanupOnly",false);
            cleanupWakeMs = event.optInt("cleanupWakeMs",200);
        }
        else if (type.equals("gate")) {
            try {store.record(event);} catch(Throwable persistenceFailure) {android.util.Log.w("PocketRuntime","Gate event persistence failed",persistenceFailure);}
            if (gate != null) { gate.complete(event); gate = null; }
        } else if (type.equals("ready") || type.equals("error") || (BuildConfig.DEVELOPMENT_TOOLS && type.equals("log"))) {
            try {store.record(event);} catch(Throwable persistenceFailure) {android.util.Log.w("PocketRuntime","Runtime event persistence failed",persistenceFailure);}
        }
        if(type.equals("state")||type.equals("activity"))workWakeLock.setActive(active&&!cleanupOnly);
        try {listener.accept(event);} catch(Throwable observerFailure) {android.util.Log.w("PocketRuntime","Runtime listener failed",observerFailure);}
        schedulePump();
    }
    private void fail(String message, Throwable error) {
        // Deliberately do not log exception messages: providers may include secrets in URLs/errors.
        try {
            JSONObject event = new JSONObject().put("type", "error").put("error", message).put("kind", error.getClass().getSimpleName());
            if (error instanceof JavetException) event.put("javetCode", ((JavetException) error).getError().getCode());
            if(message.equals("Runtime startup failed")) {
                String detail=String.valueOf(error.getMessage());
                JSONObject protectedValues=credentials.get();
                for(String key:new String[]{"apiKey","accessToken","refreshToken","idToken","accountId"}) {
                    String secret=protectedValues.optString(key);
                    if(!secret.isEmpty())detail=detail.replace(secret,"[redacted]");
                }
                detail=redactMcpStartupSecrets(detail,protectedValues);
                event.put("startupDiagnostic",detail.substring(0,Math.min(detail.length(),800)));
            }
            emit(event);
        }
        catch (Exception ignored) { }
    }
    static String redactMcpStartupSecrets(String detail, JSONObject credentials) {
        org.json.JSONArray servers=credentials.optJSONArray("mcpServers");if(servers==null)return detail;
        for(int i=0;i<servers.length();i++){
            JSONObject server=servers.optJSONObject(i);if(server==null)continue;
            detail=redact(detail,server.optString("bearerToken"));
            detail=redact(detail,server.optString("headerValue"));
            org.json.JSONArray headers=server.optJSONArray("headers");
            if(headers!=null)for(int j=0;j<headers.length();j++){JSONObject header=headers.optJSONObject(j);if(header!=null)detail=redact(detail,header.optString("value"));}
        }
        return detail;
    }
    private static String redact(String text,String secret){return secret.isEmpty()?text:text.replace(secret,"[redacted]");}
    public final class Bridge {
        @V8Function public String call(String method, String argsJson) {
            try {
                if (argsJson == null || argsJson.length() > 2_000_000) throw new IllegalArgumentException("Native arguments exceed limit");
                JSONObject args = new JSONObject(argsJson); Object result;
                if(method.startsWith("autonomy."))result=autonomy.call(method.substring(9),args);
                else switch (method) {
                    case "voice.preferences": result = VoiceConversationInvitation.preferences(context); break;
                    case "voice.request": result = VoiceConversationInvitation.request(context, args); break;
                    case "tool": result = tools.execute(args.getString("name"), args.optJSONObject("args") == null ? new JSONObject() : args.getJSONObject("args"), args.optString("owner", "main")); break;
                    case "memory": result = memory.run(args.getJSONObject("request"), args.getString("owner")); break;
                    case "history.search": result = new JSONObject().put("messages",store.searchHistory(args.getString("query"),args.optInt("limit",8),args.getString("owner"))); break;
                    case "history.read": result = new JSONObject().put("message",store.readHistory(args.getLong("id"),args.getString("owner"),args.optInt("offset",0),args.optInt("limit",4000))); break;
                    case "history.resume_main": result = new JSONObject().put("messages",store.resumeMainConversation(args.getString("threadId"))); break;
                    case "store.load": result = store.loadDurable(); break;
                    case "store.save": store.saveDurable(args.getJSONObject("state")); result = true; break;
                    case "store.save.begin": {
                        int chars=args.getInt("chars");
                        if(chars<0||chars>MAX_DURABLE_STATE_CHARS)throw new IllegalArgumentException("Durable state exceeds limit");
                        abortDurableSave();
                        durableSaveExpected=chars;
                        durableSaveFile=File.createTempFile("runtime-checkpoint-",".json",context.getCacheDir());
                        durableSaveOutput=new FileOutputStream(durableSaveFile);
                        result=true;break;
                    }
                    case "store.save.chunk": {
                        if(durableSaveOutput==null||durableSaveFile==null)throw new IllegalStateException("No durable save in progress");
                        String chunk=args.getString("chunk");
                        if(!chunk.isEmpty()&&(Character.isLowSurrogate(chunk.charAt(0))||Character.isHighSurrogate(chunk.charAt(chunk.length()-1))))
                            throw new IllegalArgumentException("Durable state chunk splits a surrogate pair");
                        if(durableSaveReceived+chunk.length()>durableSaveExpected)throw new IllegalArgumentException("Durable state chunk overflow");
                        byte[] bytes=chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        if(durableSaveBytes+bytes.length>MAX_DURABLE_STATE_BYTES)throw new IllegalArgumentException("Durable state byte budget exceeded");
                        durableSaveOutput.write(bytes);durableSaveReceived+=chunk.length();durableSaveBytes+=bytes.length;
                        result=durableSaveReceived;break;
                    }
                    case "store.save.commit": {
                        if(durableSaveOutput==null||durableSaveFile==null||durableSaveReceived!=durableSaveExpected)
                            throw new IllegalStateException("Incomplete durable state");
                        durableSaveOutput.flush();durableSaveOutput.getFD().sync();durableSaveOutput.close();durableSaveOutput=null;
                        byte[] bytes=java.nio.file.Files.readAllBytes(durableSaveFile.toPath());
                        if(bytes.length!=durableSaveBytes||bytes.length>MAX_DURABLE_STATE_BYTES)throw new IllegalStateException("Durable state transfer mismatch");
                        store.saveDurableBytes(bytes);abortDurableSave();result=true;break;
                    }
                    case "store.save.abort": abortDurableSave();result=true;break;
                    case "credentials.get": result = credentials.get(); break;
                    case "credentials.updateCodex": result = credentials.updateCodex(args); break;
                    default: throw new IllegalArgumentException("Unknown native method");
                }
                return new JSONObject().put("ok", true).put("result", result).toString();
            } catch (Throwable error) {
                if(method!=null&&method.startsWith("store.save.")&&!"store.save.abort".equals(method))abortDurableSave();
                try { return new JSONObject().put("ok", false).put("error", new JSONObject().put("code", error.getClass().getSimpleName()).put("message", "Native call failed: " + method)).toString(); }
                catch (Exception ignored) { return "{\"ok\":false,\"error\":{\"code\":\"native_error\"}}"; }
            }
        }
        @V8Function public void emit(String eventJson) {
            try {
                if (eventJson == null || eventJson.length() > 8_000_000) throw new IllegalArgumentException("Event exceeds limit");
                RuntimeHost.this.emit(new JSONObject(eventJson));
            } catch (Exception error) { fail("Invalid runtime event", error); }
        }
    }
    CompletableFuture<org.json.JSONArray> developmentToolList() {
        CompletableFuture<org.json.JSONArray> future = track(new CompletableFuture<>());
        boolean accepted=handler.post(() -> {
            if (!BuildConfig.DEVELOPMENT_TOOLS || closed || runtime == null) {
                future.completeExceptionally(new IllegalStateException("Development bridge unavailable")); return;
            }
            try { future.complete(new org.json.JSONArray(runtime.getExecutor("JSON.stringify(PocketAgent.debugList())").executeString())); }
            catch (Throwable error) {handleRuntimeEntryFailure("Development tool listing failed",error);future.completeExceptionally(error);}
        });
        if(!accepted)future.completeExceptionally(new IllegalStateException("Runtime thread is unavailable"));
        return future;
    }
    CompletableFuture<JSONObject> developmentToolCall(String name, JSONObject args) {
        CompletableFuture<JSONObject> future = track(new CompletableFuture<>());
        boolean accepted=handler.post(() -> {
            if (!BuildConfig.DEVELOPMENT_TOOLS || closed || runtime == null) {
                future.completeExceptionally(new IllegalStateException("Development bridge unavailable")); return;
            }
            String id = java.util.UUID.randomUUID().toString();
            debugPending.put(id, future);
            try {
                String command = "PocketAgent.debugCall(" + JSONObject.quote(id) + "," + JSONObject.quote(name) + "," + "JSON.parse(" + JSONObject.quote(args.toString()) + ")" + ")";
                runtime.getExecutor(command).executeVoid();
                runtime.await(V8AwaitMode.RunNoWait);
                schedulePump();
                handler.postDelayed(() -> {
                    if (debugPending.remove(id) != null) future.completeExceptionally(new java.util.concurrent.TimeoutException("Tool execution outcome unknown; inspect state before retry"));
                }, 35000);
            } catch (Throwable error) {
                debugPending.remove(id);handleRuntimeEntryFailure("Development tool call failed",error);future.completeExceptionally(error);
            }
        });
        if(!accepted)future.completeExceptionally(new IllegalStateException("Runtime thread is unavailable"));
        return future;
    }
    /** Local instrumentation only: no exported Intent/broadcast/tool endpoint. */
    public CompletableFuture<JSONObject> runCompatibilityGate() {
        CompletableFuture<JSONObject> result = track(new CompletableFuture<>());
        boolean accepted=handler.post(() -> {
            if (gate != null || closed || runtime == null) { result.completeExceptionally(new IllegalStateException("Gate unavailable")); return; }
            gate = result;
            try (InputStream input = context.getAssets().open("node-gate.cjs")) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                runtime.getExecutor(bytes.toString("UTF-8")).executeVoid(); runtime.await(V8AwaitMode.RunNoWait); schedulePump();
                handler.postDelayed(() -> { if (gate == result) { gate = null; result.completeExceptionally(new IllegalStateException("Node gate timed out")); } }, 20000);
            } catch (Throwable error) {gate=null;handleRuntimeEntryFailure("Runtime compatibility gate failed",error);result.completeExceptionally(error);}
        });
        if(!accepted)result.completeExceptionally(new IllegalStateException("Runtime thread is unavailable"));
        return result;
    }
    public CompletableFuture<JSONObject> memoryStats() {
        CompletableFuture<JSONObject> result = track(new CompletableFuture<>());
        boolean accepted=handler.post(() -> {
            if(runtime==null){result.completeExceptionally(new IllegalStateException("Runtime is not running"));return;}
            try { result.complete(new JSONObject(runtime.getExecutor("JSON.stringify(process.memoryUsage())").executeString())); }
            catch (Throwable error) {handleRuntimeEntryFailure("Runtime memory query failed",error);result.completeExceptionally(error);}
        });
        if(!accepted)result.completeExceptionally(new IllegalStateException("Runtime thread is unavailable"));
        return result;
    }
    private boolean drainRuntimeBeforeClose() {
        NodeRuntime current=runtime;
        if(current==null)return true;
        shutdownAcknowledged=false;shutdownClean=false;
        try {
            current.getExecutor("PocketAgent.dispatch('{\"action\":\"prepare_shutdown\"}')").executeVoid();
        } catch(Throwable error) {return false;}
        long deadline=android.os.SystemClock.elapsedRealtime()+2200;
        while(runtime==current&&!shutdownAcknowledged&&android.os.SystemClock.elapsedRealtime()<deadline) {
            try {current.await(V8AwaitMode.RunNoWait);}
            catch(Throwable error){return false;}
            try {Thread.sleep(10);} catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
        }
        return shutdownAcknowledged&&shutdownClean;
    }
    private void closeResourcesOnOwnerThread(boolean cleanShutdown) {
        completePending(new IllegalStateException("Service stopped"));
        abortDurableSave();
        closeRuntimeOnly();
        if(!cleanShutdown) {
            try {store.markInterrupted();} catch(Throwable error) {android.util.Log.w("PocketRuntime","Could not mark interrupted",error);}
            try {store.markDurableInterrupted();} catch(Throwable error) {android.util.Log.w("PocketRuntime","Could not neutralize durable inbox",error);}
        }
        try {autonomy.close();} catch(Throwable error) {android.util.Log.w("PocketRuntime","Autonomy store close failed",error);}
        try {memory.close();} catch(Throwable error) {android.util.Log.w("PocketRuntime","Memory store close failed",error);}
        try {store.close();} catch(Throwable error) {android.util.Log.w("PocketRuntime","State store close failed",error);}
        try {thread.quitSafely();} catch(Throwable error) {android.util.Log.w("PocketRuntime","Runtime thread close failed",error);}
        closeCompletion.complete(null);
    }
    private void closeOnOwnerThread() {
        if(closed)return;
        closed=true;handler.removeCallbacks(pump);pumpScheduled=false;
        boolean clean=false;
        try {clean=drainRuntimeBeforeClose();}
        catch(Throwable error) {fail("Runtime shutdown drain failed",error);}
        closeResourcesOnOwnerThread(clean);
    }
    private void closeWithoutRuntimeThread() {
        // The Javet isolate is thread-affine. If the owning Looper already rejects
        // work, never close it from the caller thread; mark durable state interrupted
        // and release only resources that are safe outside the isolate owner.
        completePending(new IllegalStateException("Runtime owner thread is unavailable"));
        abortDurableSave();
        try {store.markInterrupted();} catch(Throwable error) {android.util.Log.w("PocketRuntime","Could not mark interrupted",error);}
        try {store.markDurableInterrupted();} catch(Throwable error) {android.util.Log.w("PocketRuntime","Could not neutralize durable inbox",error);}
        try {autonomy.close();} catch(Throwable error) {android.util.Log.w("PocketRuntime","Autonomy store close failed",error);}
        try {memory.close();} catch(Throwable error) {android.util.Log.w("PocketRuntime","Memory store close failed",error);}
        try {store.close();} catch(Throwable error) {android.util.Log.w("PocketRuntime","State store close failed",error);}
        try {thread.quitSafely();} catch(Throwable error) {android.util.Log.w("PocketRuntime","Runtime thread close failed",error);}
        closeCompletion.complete(null);
        android.util.Log.e("PocketRuntime","Runtime owner thread unavailable; thread-affine isolate left for process teardown");
    }
    CompletableFuture<Void> closeAsync() {close();return closeCompletion;}
    @Override public void close() {
        if(closed||closing)return;
        closing=true;
        boolean accepted=false;
        try {accepted=handler.post(this::closeOnOwnerThread);} catch(Throwable ignored) {}
        if(!accepted) {closed=true;closeWithoutRuntimeThread();}
    }
}
