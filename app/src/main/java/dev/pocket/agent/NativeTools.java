package dev.pocket.agent;

import android.Manifest;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.PowerManager;
import android.app.KeyguardManager;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Base64;
import dev.pocket.android.*;
import dev.pocket.control.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.pocket.control.ControlException.Code.*;

final class NativeTools implements AutoCloseable {
    private static final int DEFAULT_OBSERVATION_NODES=40;
    private static final int OBSERVATION_TEXT_BUDGET=24_000;
    private static final int OBSERVATION_NODE_TEXT_LIMIT=400;
    private static final int OBSERVATION_NODE_ID_LIMIT=240;
    private static final int OBSERVATION_NODE_ROLE_LIMIT=160;
    private static final int BATCH_TEXT_BUDGET=28_000;
    private static final int BATCH_IMAGE_PAYLOAD_LIMIT=2_000_000;
    private final Context context;
    private final OperationStore operations;
    private final JSONArray definitions;
    private final AndroidSystemActions system;
    private final AgentDisplayCoordinator displays;
    private final Set<String> acquiredVirtualOwners=new LinkedHashSet<>();
    private final Object displayGeneration=new Object();
    NativeTools(Context context) throws Exception {
        this.context=context.getApplicationContext();operations=new OperationStore(context);system=new AndroidSystemActions(context);displays=AgentDisplayCoordinator.shared(context);
        try(java.io.InputStream input=context.getAssets().open("tools.json")) {
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;
            while((n=input.read(chunk))!=-1) bytes.write(chunk,0,n);
            definitions=new JSONArray(bytes.toString("UTF-8"));
        }
    }
    JSONArray list() {return definitions;}
    synchronized void releaseOwner(String owner) {
        displays.release(owner,displayGeneration);
        acquiredVirtualOwners.remove(owner);
    }
    @Override public synchronized void close() {
        displays.retireOwners(new LinkedHashSet<>(acquiredVirtualOwners),displayGeneration);
        acquiredVirtualOwners.clear();
    }
    private synchronized JSONObject screenLease(JSONObject args,String owner) {
        if(args.length()!=1) return Json.failure("INVALID_ARGUMENT","Only action is accepted");
        String action=args.optString("action");
        if(action.equals("acquire")) {
            // Track uncertain bootstrap failures too: they can retain native leases.
            acquiredVirtualOwners.add(owner);
            try{return displays.acquire(owner,displayGeneration);}
            catch(ControlException e){return Json.failure(e.code.name(),e.getMessage());}
            catch(RuntimeException e){return Json.failure("NOT_READY","Cannot establish safe virtual display ownership");}
        }
        if(action.equals("release"))try {releaseOwner(owner);return Json.obj("status","released");}
        catch(IllegalStateException e){return Json.failure("NOT_READY","Display retained because safe task cleanup could not be completed");}
        return Json.failure("INVALID_ARGUMENT","action must be acquire or release");
    }
    private static boolean uiTool(String name) {
        return name.equals("phone_observe")||name.equals("phone_act")||name.equals("phone_open_app")||name.equals("phone_open_url")||name.equals("phone_clock_screen");
    }
    private static String displayTarget(JSONObject args) {return args.optString("display_target","virtual");}
    private static boolean physical(JSONObject args) {return "physical".equals(displayTarget(args));}
    static int textLengthWithoutImages(Object value) {
        if(value==null||value==JSONObject.NULL)return 4;
        if(value instanceof JSONObject object) {
            int chars=2;boolean first=true;Iterator<String> keys=object.keys();
            while(keys.hasNext()) {
                String key=keys.next();if("images".equals(key))continue;
                if(!first)chars++;first=false;
                chars+=JSONObject.quote(key).length()+1+textLengthWithoutImages(object.opt(key));
            }
            return chars;
        }
        if(value instanceof JSONArray array) {
            int chars=2;
            for(int i=0;i<array.length();i++) {if(i>0)chars++;chars+=textLengthWithoutImages(array.opt(i));}
            return chars;
        }
        if(value instanceof String string)return JSONObject.quote(string).length();
        return String.valueOf(value).length();
    }
    static void prepareBatchObservationImages(JSONArray priorResults,JSONObject current) throws Exception {
        for(int i=0;i<priorResults.length();i++) {
            JSONObject entry=priorResults.optJSONObject(i);
            JSONObject result=entry==null?null:entry.optJSONObject("result");
            if(result!=null)result.remove("images");
        }
        JSONArray images=current.optJSONArray("images");
        if(images==null)return;
        JSONObject latest=null;
        for(int i=0;i<images.length();i++) {
            JSONObject image=images.optJSONObject(i);
            String data=image==null?null:image.optString("data",null);
            String mime=image==null?null:image.optString("mimeType",null);
            if(data!=null&&!data.isEmpty()&&data.length()<=BATCH_IMAGE_PAYLOAD_LIMIT&&mime!=null&&!mime.isEmpty())latest=image;
        }
        if(latest==null) {
            current.remove("images");
            current.put("image_omitted",true);
            current.put("image_note","Screenshot payload exceeded the independent batch image limit and was omitted.");
        } else current.put("images",new JSONArray().put(latest));
    }
    private JSONObject batch(JSONObject args,String owner) {
        JSONArray calls=args.optJSONArray("calls");
        if(args.length()!=1 || calls==null || calls.length()<1 || calls.length()>8)
            return Json.failure("INVALID_ARGUMENT","calls must contain 1..8 direct calls");
        try {
            // Validate the whole batch before the first dispatch. Nested batches and leases are excluded.
            String batchDisplayTarget=null;
            for(int i=0;i<calls.length();i++) {
                JSONObject call=calls.getJSONObject(i);
                if(call.length()!=2) throw new ControlException(INVALID_ARGUMENT,"Each call needs only name and args");
                String name=call.getString("name");JSONObject callArgs=call.getJSONObject("args");
                if(name.equals("phone_batch") || name.equals("phone_screen")) throw new ControlException(INVALID_ARGUMENT,"Nested batches and lease changes are not allowed");
                validate(callArgs,definition(name).getJSONObject("inputSchema"));
                if(uiTool(name)) {
                    String target=displayTarget(callArgs);
                    if(batchDisplayTarget==null)batchDisplayTarget=target;
                    else if(!batchDisplayTarget.equals(target))throw new ControlException(INVALID_ARGUMENT,"All UI calls in one batch must use the same display_target");
                }
            }
            JSONArray results=new JSONArray();JSONObject previousObservation=null;String expectedPackage=null;
            boolean transitionPending=false;long transitionStartedMs=0;
            for(int i=0;i<calls.length();i++) {
                JSONObject call=calls.getJSONObject(i);String name=call.getString("name");JSONObject callArgs=call.getJSONObject("args");
                JSONObject spec=definition(name);boolean readOnly=spec.getJSONObject("annotations").optBoolean("readOnlyHint",false);
                JSONObject result;
                if(name.equals("phone_observe")) {
                    int remaining=BATCH_TEXT_BUDGET-textLengthWithoutImages(results)-1500;
                    result=remaining<1200
                        ?Json.failure("OUTPUT_LIMIT","Batch response budget exhausted; issue this observation separately")
                        :batchObservation(callArgs,owner,Math.min(OBSERVATION_TEXT_BUDGET,remaining),transitionPending,expectedPackage,previousObservation,transitionStartedMs);
                } else result=execute(name,callArgs,owner);
                if(name.equals("phone_observe"))prepareBatchObservationImages(results,result);
                JSONObject entry=Json.obj("index",i,"name",name,"result",result);
                if(readOnly && textLengthWithoutImages(results)+textLengthWithoutImages(entry)+512>BATCH_TEXT_BUDGET) {
                    result=Json.failure("OUTPUT_LIMIT","Batch response budget exceeded; issue this read separately");
                    entry=Json.obj("index",i,"name",name,"result",result);
                }
                results.put(entry);
                if(result.optBoolean("isError")) return Json.obj("isError",true,"code",result.optString("code","BATCH_PARTIAL"),
                    "message",result.optString("message","Batch stopped on an error"),"status","partial","results",results,"stopped_at",i,
                    "retry_safe",false,"display_replaced",result.optBoolean("display_replaced"));
                if(result.optBoolean("display_replaced")&&i+1<calls.length()) return Json.obj("isError",true,"code","NOT_READY",
                    "message","Display lease was replaced while recovering the observation; prior private-display app state was discarded and remaining batch calls were not dispatched.",
                    "status","partial","results",results,"stopped_after",i,"next_index",i+1,"retry_safe",false,"display_replaced",true);
                if(name.equals("phone_observe")) {previousObservation=result;transitionPending=false;expectedPackage=null;}
                else if(name.equals("phone_open_app")) {transitionPending=true;expectedPackage=callArgs.getString("package_name");transitionStartedMs=android.os.SystemClock.elapsedRealtime();}
                else if(name.equals("phone_open_url")||name.equals("phone_clock_screen")) {transitionPending=true;expectedPackage=null;transitionStartedMs=android.os.SystemClock.elapsedRealtime();}
            }
            return Json.obj("status","completed","results",results);
        } catch(Exception error) {return Json.failure("INVALID_ARGUMENT",error.getMessage()==null?"Invalid batch":error.getMessage());}
    }
    private static boolean sameObservation(JSONObject first,JSONObject second) {
        return first.optString("package_name").equals(second.optString("package_name"))
            && first.optInt("window_id",-1)==second.optInt("window_id",-1)
            && String.valueOf(first.optJSONArray("nodes")).equals(String.valueOf(second.optJSONArray("nodes")));
    }
    private static boolean pauseObservationRetry() {
        try {Thread.sleep(120);return true;}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();return false;}
    }
    static boolean observationLeaseReplacementNeeded(JSONObject result) {
        if(!result.optBoolean("isError"))return false;
        String code=result.optString("code"),message=result.optString("message").toLowerCase(java.util.Locale.ROOT);
        return ("RESULT_UNKNOWN".equals(code)&&message.contains("timed out")) ||
            ("NOT_READY".equals(code)&&(message.contains("shizuku backend")||message.contains("display service disconnected")||
                message.contains("virtual display backend disconnected")||message.contains("no virtual display")));
    }
    private JSONObject replaceObservationLease(String owner) {
        displays.release(owner,displayGeneration);
        try {return displays.acquire(owner,displayGeneration);}
        catch(ControlException error) {return Json.failure(error.code.name(),error.getMessage());}
        catch(RuntimeException error) {return Json.failure("NOT_READY","Virtual display recovery failed");}
    }
    private static JSONObject markDisplayReplacement(JSONObject result) {
        try {
            result.put("display_replaced",true);
            result.put("display_note","Display lease was replaced after a backend failure; prior app state on the display was discarded.");
            return result;
        } catch(org.json.JSONException impossible) {
            return Json.failure("RESULT_UNKNOWN","Recovered display observation could not be annotated");
        }
    }
    private JSONObject observationResult(JSONObject args,String owner,int textBudget) {
        try {return observation(args,owner,textBudget);}
        catch(ControlException error) {return Json.failure(error.code.name(),error.getMessage());}
        catch(SecurityException error) {return Json.failure("PERMISSION_REQUIRED","Android denied access");}
        catch(Exception error) {return Json.failure("RESULT_UNKNOWN","Operation unavailable; inspect current state before repeating");}
    }
    private JSONObject batchObservation(JSONObject args,String owner,int textBudget,boolean transitionPending,String expectedPackage,
                                        JSONObject previousObservation,long transitionStartedMs) {
        long deadline=android.os.SystemClock.elapsedRealtime()+(transitionPending?2500:2000);boolean displayReplaced=false;
        for(;;) {
            JSONObject result=observationResult(args,owner,textBudget);long now=android.os.SystemClock.elapsedRealtime();
            if(result.optBoolean("isError")) {
                String code=result.optString("code"),message=result.optString("message");
                String lower=message.toLowerCase(java.util.Locale.ROOT);
                if(!physical(args)&&!displayReplaced&&observationLeaseReplacementNeeded(result)) {
                    JSONObject replacement=replaceObservationLease(owner);
                    if(replacement.optBoolean("isError"))return replacement;
                    displayReplaced=true;transitionPending=false;expectedPackage=null;previousObservation=null;
                    deadline=android.os.SystemClock.elapsedRealtime()+2000;
                    if(!pauseObservationRetry())return markDisplayReplacement(Json.failure("RESULT_UNKNOWN","Interrupted while replacing the private display; inspect before repeating"));
                    continue;
                }
                boolean retryable="STALE_OBSERVATION".equals(code) ||
                    ("NOT_READY".equals(code) && lower.matches(".*(active accessibility window|screen transition|virtual display|shizuku backend|display service).*"));
                if(retryable&&now<deadline) {
                    if(!pauseObservationRetry()) {
                        JSONObject interrupted=Json.failure("RESULT_UNKNOWN","Interrupted while waiting for screen readiness; inspect before repeating");
                        return displayReplaced?markDisplayReplacement(interrupted):interrupted;
                    }
                    continue;
                }
                return displayReplaced?markDisplayReplacement(result):result;
            }
            if(transitionPending) {
                if(expectedPackage!=null&&!expectedPackage.equals(result.optString("package_name"))) {
                    if(now<deadline) {
                        if(!pauseObservationRetry())return Json.failure("RESULT_UNKNOWN","Interrupted while waiting for screen readiness; inspect before repeating");
                        continue;
                    }
                    return Json.failure("NOT_READY","Launch transition to "+expectedPackage+" was not observable before the readiness deadline");
                }
                if(expectedPackage==null) {
                    long elapsed=now-transitionStartedMs;
                    boolean changed=previousObservation!=null&&!sameObservation(previousObservation,result);
                    if(((previousObservation!=null&&!changed&&elapsed<1200)||(previousObservation==null&&elapsed<350))&&now<deadline) {
                        if(!pauseObservationRetry())return Json.failure("RESULT_UNKNOWN","Interrupted while waiting for screen readiness; inspect before repeating");
                        continue;
                    }
                }
            }
            return displayReplaced?markDisplayReplacement(result):result;
        }
    }
    private JSONObject definition(String name) throws Exception {
        for(int i=0;i<definitions.length();i++) if(definitions.getJSONObject(i).getString("name").equals(name)) return definitions.getJSONObject(i);
        throw new ControlException(UNSUPPORTED,"Unknown tool");
    }
    private static void validate(JSONObject args,JSONObject schema) throws Exception {
        JSONObject props=schema.getJSONObject("properties");JSONArray required=schema.optJSONArray("required");
        if(required!=null) for(int i=0;i<required.length();i++) if(!args.has(required.getString(i)) || args.isNull(required.getString(i))) throw new ControlException(INVALID_ARGUMENT,"Missing argument: "+required.getString(i));
        Iterator<String> keys=args.keys();
        while(keys.hasNext()) {
            String key=keys.next();if(!props.has(key)) throw new ControlException(INVALID_ARGUMENT,"Unknown argument: "+key);
            JSONObject p=props.getJSONObject(key);Object v=args.get(key);String type=p.getString("type");
            boolean valid=switch(type) {case "string" -> v instanceof String;case "boolean" -> v instanceof Boolean;case "integer" -> v instanceof Integer || v instanceof Long;case "object" -> v instanceof JSONObject;default -> false;};
            if(!valid) throw new ControlException(INVALID_ARGUMENT,"Invalid type for "+key);
            if(v instanceof Number num && (num.longValue()<p.optLong("minimum",Long.MIN_VALUE)||num.longValue()>p.optLong("maximum",Long.MAX_VALUE))) throw new ControlException(INVALID_ARGUMENT,"Out-of-range argument: "+key);
            if(v instanceof String s && (s.length()<p.optInt("minLength",0)||s.length()>p.optInt("maxLength",20000))) throw new ControlException(INVALID_ARGUMENT,"Invalid length for "+key);
            JSONArray choices=p.optJSONArray("enum");if(choices!=null) {boolean found=false;for(int i=0;i<choices.length();i++) if(choices.get(i).equals(v)) found=true;if(!found) throw new ControlException(INVALID_ARGUMENT,"Unsupported value for "+key);}
            if(v instanceof JSONObject nested) validate(nested,p);
        }
    }
    JSONObject execute(String name,JSONObject args,String owner) {
        if (owner==null || owner.trim().isEmpty() || owner.length()>160) return Json.failure("INVALID_ARGUMENT","Valid conversation owner required");
        if (name.equals("phone_batch")) return batch(args,owner);
        if (name.equals("phone_screen")) return screenLease(args,owner);
        try {
            JSONObject spec=definition(name);validate(args,spec.getJSONObject("inputSchema"));
            if(name.equals("phone_shell")){String action=args.getString("action");if(action.equals("acquire")||action.equals("release"))return screenLease(Json.obj("action",action),owner);return displays.terminal(args,owner,displayGeneration);}
            if(!spec.getJSONObject("annotations").getBoolean("readOnlyHint")) return operations.execute(args.getString("operation_id"),name,args,()->dispatch(name,args,owner));
            return dispatch(name,args,owner);
        } catch(ControlException e) {return Json.failure(e.code.name(),e.getMessage());}
        catch(SecurityException e) {return Json.failure("PERMISSION_REQUIRED","Android denied access");}
        catch(Exception e) {return Json.failure("RESULT_UNKNOWN","Operation unavailable; inspect current state before repeating");}
    }
    /** Preserve a usable record and advance pagination even for escape-heavy extras. */
    static JSONObject boundedNotification(AndroidNotificationActions.Entry n,JSONArray messages) {
        JSONObject full=Json.obj("notification_key",n.notificationKey(),"posted_at_ms",n.postedAt(),
            "package_name",n.packageName(),"title",n.title(),"text",n.text(),"expanded_text",n.expandedText(),
            "conversation_title",n.conversationTitle(),"group_conversation",n.groupConversation(),"messages",messages);
        if(full.toString().length()<=16000)return full;
        return Json.obj("notification_key",clip(n.notificationKey(),1000),"posted_at_ms",n.postedAt(),
            "package_name",n.packageName(),"title",clip(n.title(),200),"text",clip(n.text(),200),
            "content_truncated",true,"message_count",messages.length());
    }
    private static String clip(String value,int max) {
        if(value==null)return "";
        return value.length()>max?value.substring(0,max):value;
    }
    private AccessibilityProxyBackend screen(String owner,JSONObject args) throws ControlException {return displays.screen(owner,physical(args),displayGeneration);}
    private static Integer integer(JSONObject a,String name) throws Exception {return a.has(name)?a.getInt(name):null;}
    private static String string(JSONObject a,String name) throws Exception {return a.has(name)?a.getString(name):null;}
    private JSONObject outcome(AndroidSystemActions.Outcome value) {return Json.tool(Json.obj("status",value.status(),"reference",value.reference(),"message",value.message()));}
    static JSONObject boundedObservationNodes(List<ControlBackend.Node> source) {return boundedObservationNodes(source,OBSERVATION_TEXT_BUDGET);}
    private static boolean exceeds(String value,int max) {return value!=null&&value.length()>max;}
    static JSONObject boundedObservationNodes(List<ControlBackend.Node> source,int textBudget) {
        JSONArray nodes=new JSONArray();int nodeCharacters=0;boolean outputTruncated=false;
        for(ControlBackend.Node n:source) {
            outputTruncated|=exceeds(n.resourceId(),OBSERVATION_NODE_ID_LIMIT)||exceeds(n.text(),OBSERVATION_NODE_TEXT_LIMIT)
                ||exceeds(n.description(),OBSERVATION_NODE_TEXT_LIMIT)||exceeds(n.role(),OBSERVATION_NODE_ROLE_LIMIT);
            JSONObject node=Json.obj("index",n.index(),"resource_id",clip(n.resourceId(),OBSERVATION_NODE_ID_LIMIT),
                "text",clip(n.text(),OBSERVATION_NODE_TEXT_LIMIT),"description",clip(n.description(),OBSERVATION_NODE_TEXT_LIMIT),
                "role",clip(n.role(),OBSERVATION_NODE_ROLE_LIMIT),
                "bounds",Json.obj("left",n.left(),"top",n.top(),"right",n.right(),"bottom",n.bottom()),"enabled",n.enabled(),"focused",n.focused(),
                "editable",n.editable(),"clickable",n.clickable(),"long_clickable",n.longClickable(),"scrollable",n.scrollable());
            int next=node.toString().length()+1;
            if(nodeCharacters+next>textBudget) {outputTruncated=true;break;}
            nodes.put(node);nodeCharacters+=next;
        }
        return Json.obj("nodes",nodes,"nodes_truncated",outputTruncated);
    }
    private JSONObject observation(JSONObject a,String owner) throws Exception {return observation(a,owner,OBSERVATION_TEXT_BUDGET);}
    private JSONObject observation(JSONObject a,String owner,int textBudget) throws Exception {
        ControlBackend.Observation o=screen(owner,a).observe(a.optBoolean("include_image",false),a.optInt("max_image_dimension",1280),a.optInt("max_nodes",DEFAULT_OBSERVATION_NODES));
        JSONObject bounded=boundedObservationNodes(o.nodes(),textBudget);JSONArray nodes=bounded.getJSONArray("nodes");
        JSONObject data=Json.obj("snapshot_id",o.snapshotId(),"captured_at_ms",o.capturedAtMs(),"package_name",o.packageName(),"window_id",o.windowId(),"display_target",displayTarget(a),"display_id",displays.display(owner,physical(a),displayGeneration),"coordinate_width",o.coordinateWidth(),"coordinate_height",o.coordinateHeight(),"interactive",o.interactive(),"locked",o.locked(),"nodes",nodes,"nodes_truncated",o.nodesTruncated()||bounded.getBoolean("nodes_truncated"),"image_width",o.imageWidth(),"image_height",o.imageHeight());
        JSONObject result=Json.tool(data);byte[] image=o.image();if(image!=null) result.put("images",Json.arr(Json.obj("data",Base64.encodeToString(image,Base64.NO_WRAP),"mimeType",o.imageMime())));
        return result;
    }
    private PhoneAccountHandle account(String id) throws ControlException {
        // Without an explicit account, Telecom can use the configured default.
        if(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED) {
            if(id!=null) throw new ControlException(PERMISSION_REQUIRED,"Grant phone-state access to select a SIM");
            return null;
        }
        TelecomManager manager=context.getSystemService(TelecomManager.class);if(manager==null) throw new ControlException(NOT_READY,"Telecom unavailable");List<PhoneAccountHandle> accounts;
        try {accounts=manager.getCallCapablePhoneAccounts();}catch(SecurityException denied) {throw new ControlException(PERMISSION_REQUIRED,"Android denied phone-account access");}
        if(id!=null) for(PhoneAccountHandle handle:accounts) if(accountId(handle).equals(id)) return handle;
        if(id!=null) throw new ControlException(INVALID_ARGUMENT,"Unknown phone account ID");
        if(accounts.size()>1) throw new ControlException(AMBIGUOUS_TARGET,"Choose phone_account_id from phone_capabilities for dual-SIM calling");
        if(accounts.isEmpty()) return null;
        return accounts.get(0);
    }
    private static String accountId(PhoneAccountHandle account) {return account.getComponentName().flattenToString()+"#"+account.getId();}
    private PendingIntent receipt(String op,String stage,int part) {
        Intent intent=new Intent(context,SmsReceiptReceiver.class).setData(Uri.parse("pocket-mcp://receipt/"+op+"/"+stage+"/"+part))
            .putExtra("operation",op).putExtra("stage",stage).putExtra("part",part);
        return PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
    }
    private JSONObject capabilities() {
        JSONObject permissions=new JSONObject();
        for(String permission:new String[]{Manifest.permission.READ_CONTACTS,Manifest.permission.CALL_PHONE,Manifest.permission.READ_PHONE_STATE,Manifest.permission.SEND_SMS,Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR}) {
            try {permissions.put(permission,context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED);}catch(Exception ignored){}
        }
        JSONArray accounts=new JSONArray();
        JSONArray media=new JSONArray();
        if(PhoneNotifications.connected!=null) {
            try {for(android.media.session.MediaController controller:new AndroidMediaActions(context,new ComponentName(context,PhoneNotifications.class)).sessions()) {
                android.media.session.PlaybackState state=controller.getPlaybackState();
                media.put(Json.obj("package_name",controller.getPackageName(),"playback_state",state==null?null:state.getState(),"position_ms",state==null?null:state.getPosition(),"supported_actions",state==null?null:state.getActions()));
            }}catch(Exception unavailable) {}
        }
        JSONArray subscriptions=new JSONArray();String callState="unknown";
        if(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED) {
            try {for(PhoneAccountHandle h:context.getSystemService(TelecomManager.class).getCallCapablePhoneAccounts()) accounts.put(Json.obj("id",accountId(h),"label",String.valueOf(context.getSystemService(TelecomManager.class).getPhoneAccount(h).getLabel())));}catch(Exception ignored){}
            try {
                List<android.telephony.SubscriptionInfo> active=context.getSystemService(android.telephony.SubscriptionManager.class).getActiveSubscriptionInfoList();
                if(active!=null) for(android.telephony.SubscriptionInfo info:active) subscriptions.put(Json.obj("subscription_id",info.getSubscriptionId(),"sim_slot",info.getSimSlotIndex(),"label",String.valueOf(info.getDisplayName())));
                int state=context.getSystemService(android.telephony.TelephonyManager.class).getCallState();callState=switch(state) {case 0 -> "idle";case 1 -> "ringing";case 2 -> "offhook";default -> "unknown";};
            }catch(Exception denied) {}
        }
        boolean accessibility=displays.accessibilityConnected();
        return Json.tool(Json.obj("version",BuildConfig.VERSION_NAME,"operation_prefix",operations.prefix(),"operation_nonce_min_length",8,"operation_nonce_max_length",48,"accessibility_connected",accessibility,"notification_listener_connected",PhoneNotifications.connected!=null,"interactive",context.getSystemService(PowerManager.class).isInteractive(),"locked",context.getSystemService(KeyguardManager.class).isKeyguardLocked(),"permissions",permissions,"phone_accounts",accounts,"sms_subscriptions",subscriptions,"media_sessions",media,"call_state",callState,"call_state_note","offhook includes dialing; it is not connection proof","preferred_order",Json.arr("direct_android_api","app_provided_action","semantic_accessibility","screen_gesture"),"physical_display",Json.obj("available",accessibility,"display_id",0,"requires_explicit_target",true,"launch_via_accessibility",true),"shell",Json.obj("tool","phone_shell","backend",displays.backendStatus(),"requires_shizuku",true,"requires_pc",false,"command_allowlist",false,"available_to_all_agents",true),"virtual_displays",displays.capabilities()));
    }
    private JSONObject dispatch(String name,JSONObject a,String owner) throws Exception {
        switch(name) {
            case "phone_capabilities": return capabilities();
            case "phone_observe": return observation(a,owner);
            case "phone_act": {
                JSONObject s=a.optJSONObject("selector");ControlBackend.Selector selector=s==null?null:new ControlBackend.Selector(string(s,"resource_id"),string(s,"text"),string(s,"description"));
                ControlBackend.Action action=new ControlBackend.Action(ControlBackend.Kind.valueOf(a.getString("kind").toUpperCase(java.util.Locale.ROOT)),a.getString("snapshot_id"),integer(a,"x"),integer(a,"y"),integer(a,"end_x"),integer(a,"end_y"),a.optInt("duration_ms",600),string(a,"text"),a.optBoolean("replace",true),string(a,"key"),string(a,"direction"),selector);
                ControlBackend.Result result;AccessibilityProxyBackend target=screen(owner,a);
                if("keyboard".equals(a.optString("input_method"))) {
                    if(action.kind()!=ControlBackend.Kind.TYPE||physical(a))throw new ControlException(UNSUPPORTED,"Keyboard text is supported only for type on a private display");
                    String text=action.text();
                    if(text==null||text.isEmpty()||text.length()>2000)throw new ControlException(INVALID_ARGUMENT,"Keyboard text requires 1..2000 characters");
                    if(android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())==null)
                        throw new ControlException(UNSUPPORTED,"These characters cannot be typed by the Android virtual keyboard; no text was sent");
                    target.prepareKeyboardText(action);
                    displays.text(owner,text,displayGeneration);
                    result=new ControlBackend.Result("dispatched","Real keyboard text dispatched; observe and reopen to verify persistence");
                }else if(action.kind()==ControlBackend.Kind.KEY && !"enter".equals(action.key()) && !physical(a)) {
                    target.validateAction(action);
                    // Real-device MIUI A/B testing showed /system/bin/input -d is filtered while
                    // the physical phone is asleep/keyguard-locked, even though the leased
                    // ALWAYS_UNLOCKED virtual display remains alive. There is no display-scoped
                    // Accessibility global Home/Back/Recents API, so fail closed rather than
                    // report a key as dispatched when the platform is known to discard it.
                    boolean physicalInteractive=context.getSystemService(PowerManager.class).isInteractive();
                    boolean physicalLocked=context.getSystemService(KeyguardManager.class).isKeyguardLocked();
                    if(!physicalInteractive||physicalLocked)
                        throw new ControlException(UNSUPPORTED,"Virtual-display Home/Back/Recents are unavailable while the physical device is asleep or locked; use in-app semantic or gesture navigation");
                    int key=switch(action.key()==null?"":action.key()) {case "back"->4;case "home"->3;case "recents"->187;default->throw new ControlException(UNSUPPORTED,"Key must be home, back, recents or enter");};
                    displays.key(owner,key,displayGeneration);
                    result=new ControlBackend.Result("dispatched","Display-targeted key accepted; task outcome not yet verified");
                }else result=target.execute(action);
                return Json.tool(Json.obj("status",result.status(),"message",result.message(),"display_target",displayTarget(a),"display_id",displays.display(owner,physical(a),displayGeneration)));
            }
            case "phone_apps": {
                JSONArray apps=new JSONArray();List<ResolveInfo> found=context.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);
                for(ResolveInfo info:found) apps.put(Json.obj("package_name",info.activityInfo.packageName,"label",String.valueOf(info.loadLabel(context.getPackageManager()))));return Json.tool(Json.obj("apps",apps));
            }
            case "phone_open_app": {
                String pkg=a.getString("package_name");Intent launch=context.getPackageManager().getLaunchIntentForPackage(pkg);
                if(launch==null||launch.getComponent()==null) throw new ControlException(UNSUPPORTED,"No launcher component visible for this package");
                boolean physicalTarget=physical(a);displays.launch(owner,launch,physicalTarget,displayGeneration);
                return Json.tool(Json.obj("status","requested","package_name",pkg,"display_target",displayTarget(a),"display_id",displays.display(owner,physicalTarget,displayGeneration),"message",physicalTarget?"Launch requested on the visible physical display; verify with phone_observe using display_target=physical.":"Launch requested on the private virtual display; verify with phone_observe."));
            }
            case "phone_open_url": {
                String raw=a.getString("url");
                Uri uri=Uri.parse(raw);
                if(!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme())) ||
                    uri.getHost()==null || uri.getHost().trim().isEmpty() || uri.getUserInfo()!=null ||
                    raw.length()>2048 || raw.contains("\n") || raw.contains("\r"))
                    throw new ControlException(INVALID_ARGUMENT,"Provide an HTTP(S) URL without embedded credentials or control characters");
                boolean physicalTarget=physical(a);displays.openUrl(owner,uri.toString(),physicalTarget,displayGeneration);
                return Json.tool(Json.obj("status","requested","display_target",displayTarget(a),"display_id",displays.display(owner,physicalTarget,displayGeneration),
                    "message",physicalTarget?"URL launch requested on the visible physical display; verify with phone_observe using display_target=physical.":"URL launch requested on the virtual display; verify in phone_observe."));
            }
            case "phone_clock_screen": {
                String screen=a.getString("screen");boolean physicalTarget=physical(a);
                displays.showClock(owner,screen,physicalTarget,displayGeneration);
                return Json.tool(Json.obj("status","requested","display_target",displayTarget(a),"display_id",displays.display(owner,physicalTarget,displayGeneration),
                    "message",physicalTarget?"Clock screen requested on the visible physical display; verify with phone_observe using display_target=physical.":"Clock screen requested; read alarms/timers from the UI, not from an unsupported provider API."));
            }
            case "phone_contacts": {
                JSONArray contacts=new JSONArray();for(AndroidSystemActions.Contact c:system.findContacts(a.getString("query"),a.optInt("limit",20))) contacts.put(Json.obj("contact_id",c.contactId(),"name",c.name(),"number",c.number()));return Json.tool(Json.obj("contacts",contacts));
            }
            case "phone_call": {
                if(a.has("name")==a.has("number")) throw new ControlException(INVALID_ARGUMENT,"Provide exactly one of name or number");
                system.requirePermission(Manifest.permission.CALL_PHONE);
                PhoneAccountHandle handle=account(string(a,"phone_account_id"));boolean speaker=a.optBoolean("speaker",false);
                return outcome(a.has("name")?system.callContact(a.getString("name"),handle,speaker):system.callNumber(a.getString("number"),handle,speaker));
            }
            case "phone_calendars": {
                JSONArray calendars=new JSONArray();for(AndroidSystemActions.Calendar c:system.calendars()) calendars.put(Json.obj("id",c.id(),"name",c.name(),"writable",c.writable(),"account_name",c.accountName(),"account_type",c.accountType(),"owner_account",c.ownerAccount(),"visible",c.visible()));return Json.tool(Json.obj("calendars",calendars));
            }
            case "phone_calendar_events": {
                AndroidSystemActions.CalendarEvents result=system.calendarEvents(a.getLong("start_ms"),a.getLong("end_ms"),
                    a.has("calendar_id")?a.getLong("calendar_id"):null,a.optInt("limit",50));
                JSONArray events=new JSONArray();
                for(AndroidSystemActions.CalendarEvent event:result.events())events.put(Json.obj("event_id",event.eventId(),
                    "calendar_id",event.calendarId(),"title",event.title(),"start_ms",event.startMillis(),
                    "end_ms",event.endMillis(),"all_day",event.allDay(),"event_timezone",event.timezone()));
                return Json.tool(Json.obj("events",events,"truncated",result.truncated(),"timezone_note",
                    "Start/end are UTC epoch milliseconds; use the device timezone for display and check all-day boundaries."));
            }
            case "phone_calendar_create":return outcome(system.createEvent(a.getLong("calendar_id"),a.getString("title"),a.getLong("start_ms"),a.getLong("end_ms"),a.getString("timezone")));
            case "phone_alarm":return outcome(system.setAlarm(a.getInt("hour"),a.getInt("minute"),a.optString("label","")));
            case "phone_timer":return outcome(system.startTimer(a.getInt("seconds"),a.optString("label","")));
            case "phone_sms": {
                system.requirePermission(Manifest.permission.SEND_SMS);
                AndroidMessagingActions sms=new AndroidMessagingActions(context);int sim=a.optInt("subscription_id",-1);int count=sms.parts(a.getString("text"),sim);
                ArrayList<PendingIntent> sent=new ArrayList<>(),delivered=new ArrayList<>();for(int i=0;i<count;i++) {sent.add(receipt(a.getString("operation_id"),"sent",i));delivered.add(receipt(a.getString("operation_id"),"delivered",i));}
                JSONObject result=outcome(sms.sendSms(a.getString("number"),a.getString("text"),sim,sent,delivered));result.put("parts",count);return result;
            }
            case "phone_notifications": {
                JSONArray replies=new JSONArray();for(AndroidNotificationActions.Reply r:new AndroidNotificationActions(PhoneNotifications.connected).replies()) replies.put(Json.obj("notification_key",r.notificationKey(),"posted_at_ms",r.postedAt(),"package_name",r.packageName(),"action_index",r.actionIndex(),"label",r.label(),"title",r.title(),"conversation_title",r.conversationTitle(),"group_conversation",r.groupConversation(),"identity_note","Titles are recipient hints, not contact identity proof; distinguish group conversations before replying"));return Json.tool(Json.obj("reply_actions",replies));
            }
            case "phone_notification_read": {
                JSONArray notifications=new JSONArray();
                int offset=a.optInt("offset",0);
                AndroidNotificationActions reader=new AndroidNotificationActions(PhoneNotifications.connected);
                AndroidNotificationActions.Page page=reader.readPage(string(a,"package_name"),offset,a.optInt("limit",20));
                boolean byteLimited=false;
                int characters=0;
                for(AndroidNotificationActions.Entry n:page.entries()) {
                    JSONArray messages=new JSONArray();
                    for(AndroidNotificationActions.Message m:n.messages()) messages.put(Json.obj("sender",m.sender(),"text",m.text(),"timestamp_ms",m.timestamp()));
                    JSONObject entry=boundedNotification(n,messages);
                    // The JS bridge rejects responses above 32K characters. Reserve ample
                    // space for the envelope and escape expansion while keeping pagination usable.
                    int entryCharacters=entry.toString().length()+1;
                    if(characters+entryCharacters>20000) {byteLimited=true;break;}
                    notifications.put(entry);
                    characters+=entryCharacters;
                }
                boolean truncated=byteLimited||page.hasMore();
                return Json.tool(Json.obj("notifications",notifications,"truncated",truncated,
                    "next_offset",truncated?offset+notifications.length():null,
                    "scope_note","Active notifications only, not historical conversations. Offset pagination is best-effort when active notifications change."));
            }
            case "phone_reply":return outcome(new AndroidNotificationActions(PhoneNotifications.connected).reply(a.getString("notification_key"),a.getLong("posted_at_ms"),a.getInt("action_index"),a.getString("text")));
            case "phone_media":return outcome(new AndroidMediaActions(context,new ComponentName(context,PhoneNotifications.class)).control(a.getString("package_name"),a.getString("action"),a.optLong("position_ms",0)));
            case "phone_operation":return Json.tool(operations.inspect(a.getString("operation_id")));
            default:throw new ControlException(UNSUPPORTED,"Unknown tool");
        }
    }
}
