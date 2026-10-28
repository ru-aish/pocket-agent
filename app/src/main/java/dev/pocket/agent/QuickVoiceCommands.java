package dev.pocket.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** A single bounded inference/execution owner per live voice session. */
final class QuickVoiceCommands implements AutoCloseable {
    private final Context context;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicLong generation=new AtomicLong();
    private volatile boolean closed;
    private NativeTools tools;
    private QuickCommandEncoder encoder;
    private boolean encoderUnavailable;
    private final QuickCommandEngine engine;
    QuickVoiceCommands(Context context) {
        this.context=context.getApplicationContext();
        engine=new QuickCommandEngine((name,args)->{
            if(tools==null)tools=new NativeTools(this.context);
            return tools.execute(name,args,"quick-voice");
        },action->{
            if(QuickCommandFacts.exact(action))return true;
            if(encoderUnavailable)throw new IllegalStateException("Local command model unavailable for this session");
            if(encoder==null)try(InputStream vocabulary=this.context.getAssets().open("quickcommands/vocab.txt");
                                  InputStream examples=this.context.getAssets().open("quickcommands/prototypes.bin")) {
                encoder=new QuickCommandEncoder(modelFile(this.context).getAbsolutePath(),vocabulary,examples);
            }catch(Exception failure){encoderUnavailable=true;throw failure;}
            return encoder.accepts(action);
        },System::currentTimeMillis);
    }
    static boolean enabled(Context context){return context.getSharedPreferences("quick_commands",Context.MODE_PRIVATE).getBoolean("enabled",true);}
    static void setEnabled(Context context,boolean value){context.getSharedPreferences("quick_commands",Context.MODE_PRIVATE).edit().putBoolean("enabled",value).apply();}
    boolean awaiting(){return engine.awaiting();}
    void cancelCurrent(){generation.incrementAndGet();}
    void submit(String text,Consumer<QuickCommandEngine.Result> callback) {
        long ticket=generation.incrementAndGet();
        executor.execute(()->{
            long began=android.os.SystemClock.elapsedRealtime();
            QuickCommandEngine.Result result=engine.handle(text,()->!closed&&generation.get()==ticket);
            // No utterance, contact, message body or number is written to logcat.
            Log.i("PocketQuickCommand","route="+(result.fallback()?"agent":result.awaiting()?"clarify":"local")+" elapsed_ms="+(android.os.SystemClock.elapsedRealtime()-began));
            // Retain structured local outcomes and ledger references without entering model inference.
            boolean current=!closed&&generation.get()==ticket;
            if((current||!result.operations().isEmpty())&&!result.fallback()&&!result.speech().isEmpty()) {
                try(StateStore store=new StateStore(context)){store.record(Json.obj("type","quick_voice_result","text",text,"response",result.speech(),"awaiting",result.awaiting(),"operations",new org.json.JSONArray(result.operations())));}
                catch(Exception ignored){Log.w("PocketQuickCommand","Could not record local command result");}
                if(!current&&AgentService.isRunning()&&!AgentService.isStopping())try {
                    context.startService(new android.content.Intent(context,AgentService.class).setAction(AgentService.ACTION_COMMAND)
                        .putExtra("command",Json.obj("action","record_local_voice","text",text,"response",result.speech()).toString()));
                }catch(RuntimeException unavailable){Log.w("PocketQuickCommand","Could not record interrupted local action in chat");}
            }
            main.post(()->{if(!closed&&generation.get()==ticket)callback.accept(result);});
        });
    }
    private static synchronized File modelFile(Context context) throws Exception {
        File target=new File(context.getNoBackupFilesDir(),"quick-minilm-4278337fd0ff.onnx");
        if(target.isFile()&&target.length()==23026053)try(InputStream existing=new java.io.FileInputStream(target)) {
            if(hash(existing).equals("4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474"))return target;
        }
        File temporary=new File(target.getPath()+".tmp");
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        try(InputStream input=context.getAssets().open("quickcommands/model.onnx");FileOutputStream output=new FileOutputStream(temporary)) {
            byte[] block=new byte[65536];int read;while((read=input.read(block))!=-1){output.write(block,0,read);digest.update(block,0,read);}
        }
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(!hash.toString().equals("4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474")){temporary.delete();throw new IllegalStateException("Local command model unavailable");}
        java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
    private static String hash(InputStream input) throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[65536];int count;
        while((count=input.read(bytes))!=-1)digest.update(bytes,0,count);
        StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();
    }
    @Override public void close() {
        if(closed)return;
        closed=true;cancelCurrent();
        executor.execute(()->{engine.clear();if(encoder!=null)try{encoder.close();}catch(Exception ignored){}if(tools!=null)tools.close();});
        executor.shutdown();
    }
}
