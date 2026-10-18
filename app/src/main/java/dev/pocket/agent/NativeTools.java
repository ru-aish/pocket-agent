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
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.pocket.control.ControlException.Code.*;

final class NativeTools implements AutoCloseable {
    private final Context context;
    private final OperationStore operations;
    private final JSONArray definitions;
    private final AndroidSystemActions system;
    private final AgentDisplayCoordinator displays;
    NativeTools(Context context) throws Exception {
        this.context=context.getApplicationContext();operations=new OperationStore(context);system=new AndroidSystemActions(context);displays=new AgentDisplayCoordinator(context);
        try(java.io.InputStream input=context.getAssets().open("tools.json")) {
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;
            while((n=input.read(chunk))!=-1) bytes.write(chunk,0,n);
            definitions=new JSONArray(bytes.toString("UTF-8"));
        }
    }
    JSONArray list() {return definitions;}
    synchronized void releaseOwner(String owner) {displays.release(owner);}
    @Override public synchronized void close(){displays.close();}
    private JSONObject screenLease(JSONObject args,String owner) {
        if(args.length()!=1) return Json.failure("INVALID_ARGUMENT","Only action is accepted");
        String action=args.optString("action");
        if(action.equals("acquire"))try{return displays.acquire(owner);}catch(ControlException e){return Json.failure(e.code.name(),e.getMessage());}
        if(action.equals("release")) {releaseOwner(owner);return Json.obj("status","released");}
        return Json.failure("INVALID_ARGUMENT","action must be acquire or release");
    }
    private JSONObject batch(JSONObject args,String owner) {
        JSONArray calls=args.optJSONArray("calls");
        if(args.length()!=1 || calls==null || calls.length()<1 || calls.length()>8)
            return Json.failure("INVALID_ARGUMENT","calls must contain 1..8 direct calls");
        try {
            // Validate the whole batch before the first dispatch. Nested batches and leases are excluded.
            for(int i=0;i<calls.length();i++) {
                JSONObject call=calls.getJSONObject(i);
                if(call.length()!=2) throw new ControlException(INVALID_ARGUMENT,"Each call needs only name and args");
                String name=call.getString("name");
                if(name.equals("phone_batch") || name.equals("phone_screen")) throw new ControlException(INVALID_ARGUMENT,"Nested batches and lease changes are not allowed");
                validate(call.getJSONObject("args"),definition(name).getJSONObject("inputSchema"));
            }
            JSONArray results=new JSONArray();
            for(int i=0;i<calls.length();i++) {
                JSONObject call=calls.getJSONObject(i);
                JSONObject result=execute(call.getString("name"),call.getJSONObject("args"),owner);
                results.put(Json.obj("index",i,"name",call.getString("name"),"result",result));
                if(result.optBoolean("isError")) return Json.obj("isError",true,"status","partial","results",results,"stopped_at",i,"retry_safe",false);
            }
            return Json.obj("status","completed","results",results);
        } catch(Exception error) {return Json.failure("INVALID_ARGUMENT",error.getMessage()==null?"Invalid batch":error.getMessage());}
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
        if (owner==null || owner.isBlank() || owner.length()>160) return Json.failure("INVALID_ARGUMENT","Valid conversation owner required");
        if (name.equals("phone_batch")) return batch(args,owner);
        if (name.equals("phone_screen")) return screenLease(args,owner);
        try {
            JSONObject spec=definition(name);validate(args,spec.getJSONObject("inputSchema"));
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
    private AndroidScreenBackend screen(String owner) throws ControlException {return displays.screen(owner);}
    private static Integer integer(JSONObject a,String name) throws Exception {return a.has(name)?a.getInt(name):null;}
    private static String string(JSONObject a,String name) throws Exception {return a.has(name)?a.getString(name):null;}
    private JSONObject outcome(AndroidSystemActions.Outcome value) {return Json.tool(Json.obj("status",value.status(),"reference",value.reference(),"message",value.message()));}
    private JSONObject observation(JSONObject a,String owner) throws Exception {
        // Validate the lease outside retry: a missing/disconnected display is
        // an actionable backend error, not a transient accessibility window.
        AndroidScreenBackend backend=screen(owner);
        ControlBackend.Observation o=null;
        // Accessibility often emits its first virtual-window event after the
        // screen or app launch returns. Never report the previous app as a
        // fresh observation while a requested launch is still in flight.
        for(int attempt=0;attempt<12;attempt++) {
            try {
                ControlBackend.Observation observed=backend.observe(a.optBoolean("include_image",false),a.optInt("max_image_dimension",1280),a.optInt("max_nodes",150));
                if(displays.observationReady(owner,observed.packageName())) {o=observed;break;}
            } catch(ControlException notReady) {
                if(notReady.code!=NOT_READY)throw notReady;
            }
            // A lease that vanished mid-capture must retain its original error.
            displays.display(owner);
            if(attempt<11)Thread.sleep(200);
        }
        if(o==null)throw new ControlException(NOT_READY,"Window launch still pending; observe again without replacing the display lease");
        JSONArray nodes=new JSONArray();
        for(ControlBackend.Node n:o.nodes()) nodes.put(Json.obj("index",n.index(),"resource_id",n.resourceId(),"text",n.text(),"description",n.description(),"role",n.role(),
            "bounds",Json.obj("left",n.left(),"top",n.top(),"right",n.right(),"bottom",n.bottom()),"enabled",n.enabled(),"focused",n.focused(),"editable",n.editable(),"clickable",n.clickable(),"long_clickable",n.longClickable(),"scrollable",n.scrollable()));
        JSONObject data=Json.obj("snapshot_id",o.snapshotId(),"captured_at_ms",o.capturedAtMs(),"package_name",o.packageName(),"window_id",o.windowId(),"coordinate_width",o.coordinateWidth(),"coordinate_height",o.coordinateHeight(),"interactive",o.interactive(),"locked",o.locked(),"nodes",nodes,"nodes_truncated",o.nodesTruncated(),"image_width",o.imageWidth(),"image_height",o.imageHeight());
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
        return Json.tool(Json.obj("version",BuildConfig.VERSION_NAME,"operation_prefix",operations.prefix(),"operation_nonce_min_length",8,"operation_nonce_max_length",48,"accessibility_connected",AgentAccessibility.connected!=null,"notification_listener_connected",PhoneNotifications.connected!=null,"interactive",context.getSystemService(PowerManager.class).isInteractive(),"locked",context.getSystemService(KeyguardManager.class).isKeyguardLocked(),"permissions",permissions,"phone_accounts",accounts,"sms_subscriptions",subscriptions,"media_sessions",media,"call_state",callState,"call_state_note","offhook includes dialing; it is not connection proof","preferred_order",Json.arr("direct_android_api","app_provided_action","semantic_accessibility","screen_gesture"),"virtual_displays",Json.obj("backend",displays.backendStatus(),"active",displays.activeCount(),"maximum",AgentDisplayCoordinator.MAX_DISPLAYS,"requires_shizuku",true)));
    }
    private JSONObject dispatch(String name,JSONObject a,String owner) throws Exception {
        switch(name) {
            case "phone_capabilities": return capabilities();
            case "phone_observe": return observation(a,owner);
            case "phone_act": {
                JSONObject s=a.optJSONObject("selector");ControlBackend.Selector selector=s==null?null:new ControlBackend.Selector(string(s,"resource_id"),string(s,"text"),string(s,"description"));
                ControlBackend.Action action=new ControlBackend.Action(ControlBackend.Kind.valueOf(a.getString("kind").toUpperCase(java.util.Locale.ROOT)),a.getString("snapshot_id"),integer(a,"x"),integer(a,"y"),integer(a,"end_x"),integer(a,"end_y"),a.optInt("duration_ms",600),string(a,"text"),a.optBoolean("replace",true),string(a,"key"),string(a,"direction"),selector);
                ControlBackend.Result result;
                if(action.kind()==ControlBackend.Kind.KEY && !"enter".equals(action.key())) {
                    screen(owner).validateAction(action);
                    int key=switch(action.key()==null?"":action.key()) {case "back"->4;case "home"->3;case "recents"->187;default->throw new ControlException(UNSUPPORTED,"Key must be home, back, recents or enter");};
                    displays.key(owner,key);
                    result=new ControlBackend.Result("dispatched","Display-targeted key accepted; task outcome not yet verified");
                }else result=screen(owner).execute(action);
                displays.markTransition(owner);
                return Json.tool(Json.obj("status",result.status(),"message",result.message()));
            }
            case "phone_apps": {
                JSONArray apps=new JSONArray();List<ResolveInfo> found=context.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);
                for(ResolveInfo info:found) apps.put(Json.obj("package_name",info.activityInfo.packageName,"label",String.valueOf(info.loadLabel(context.getPackageManager()))));return Json.tool(Json.obj("apps",apps));
            }
            case "phone_open_app": {
                String pkg=a.getString("package_name");Intent launch=context.getPackageManager().getLaunchIntentForPackage(pkg);
                if(launch==null||launch.getComponent()==null) throw new ControlException(UNSUPPORTED,"No launcher component visible for this package");
                displays.launch(owner,launch.getComponent().flattenToString());
                return Json.tool(Json.obj("status","requested","package_name",pkg,"display_id",displays.display(owner),"message","Launch requested on the private virtual display; verify with phone_observe."));
            }
            case "phone_open_url": {
                String raw=a.getString("url");
                Uri uri=Uri.parse(raw);
                if(!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme())) ||
                    uri.getHost()==null || uri.getHost().isBlank() || uri.getUserInfo()!=null ||
                    raw.length()>2048 || raw.contains("\n") || raw.contains("\r"))
                    throw new ControlException(INVALID_ARGUMENT,"Provide an HTTP(S) URL without embedded credentials or control characters");
                displays.openUrl(owner,uri.toString());
                return Json.tool(Json.obj("status","requested","display_id",displays.display(owner),
                    "message","URL launch requested on the virtual display; verify in phone_observe."));
            }
            case "phone_clock_screen": {
                String screen=a.getString("screen");
                displays.showClock(owner,screen);
                return Json.tool(Json.obj("status","requested","display_id",displays.display(owner),
                    "message","Clock screen requested; read alarms/timers from the UI, not from an unsupported provider API."));
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
