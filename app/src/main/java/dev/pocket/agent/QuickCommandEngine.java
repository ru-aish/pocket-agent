package dev.pocket.agent;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Session-scoped dialogue and deterministic execution. All side effects use the native ledger. */
final class QuickCommandEngine {
    interface Backend { JSONObject call(String tool,JSONObject args) throws Exception; }
    interface Understanding { boolean accepts(QuickCommandParser.Action action) throws Exception; }
    record Result(boolean fallback,String speech,boolean awaiting,List<String> operations) {
        static Result fallbackResult(){return new Result(true,"",false,List.of());}
        static Result say(String text,boolean awaiting){return new Result(false,text,awaiting,List.of());}
    }
    private record Choice(String label,Map<String,String> values) {}
    private record Pending(List<QuickCommandParser.Action> plan,int index,String key,String prompt,List<Choice> choices,long at) {}
    private record Prepared(String tool,JSONObject args,String description,boolean mutation) {}
    private static final class Need extends Exception {
        final int index;final String key,prompt;final List<Choice> choices;
        Need(int index,String key,String prompt,List<Choice> choices){this.index=index;this.key=key;this.prompt=prompt;this.choices=choices;}
    }
    private static final class Fallback extends Exception {}
    private static final class Cancelled extends Exception {}
    private final Backend backend;
    private final Understanding understanding;
    private final LongSupplier clock;
    private Pending pending;
    private volatile boolean awaiting;
    QuickCommandEngine(Backend backend,Understanding understanding,LongSupplier clock) {this.backend=backend;this.understanding=understanding;this.clock=clock;}
    boolean awaiting(){return awaiting;}
    void clear(){pending=null;awaiting=false;}
    Result handle(String text,BooleanSupplier alive) {
        List<String> operations=new ArrayList<>();
        boolean wasPending=pending!=null;
        try {
            check(alive);
            List<QuickCommandParser.Action> plan;
            if(pending!=null&&clock.getAsLong()-pending.at()>120000)clear();
            if(pending!=null) {
                String normalized=QuickCommandParser.normalize(text);
                if(normalized.matches("(?:cancel|cancel it|never mind|nevermind|stop)")){clear();return Result.say("Cancelled the pending command.",false);}
                Pending p=pending;
                boolean dictating=p.key().equals("text")&&text.matches("(?is)^(?:say|(?:the )?message is)\\s+.+");
                List<QuickCommandParser.Action> independent=!dictating&&p.choices().isEmpty()&&List.of("text","recipient").contains(p.key())?QuickCommandParser.parse(text):List.of();
                if(!independent.isEmpty()){clear();plan=independent;if(!understood(plan))return Result.fallbackResult();}
                else if(resolve(p,text)) {plan=p.plan();clear();}
                else {
                    List<QuickCommandParser.Action> fresh=QuickCommandParser.parse(text);
                    if(fresh.isEmpty())return Result.say(p.prompt(),true);
                    // An independent request abandons the old command; never send it later.
                    clear();plan=fresh;if(!understood(plan))return Result.fallbackResult();
                }
            } else {plan=QuickCommandParser.parse(text);if(plan.isEmpty()||!understood(plan))return Result.fallbackResult();}
            check(alive);
            List<Prepared> prepared=new ArrayList<>();
            try {
                boolean needsCapabilities=plan.stream().anyMatch(a->List.of("call","message","media").contains(a.intent()));
                JSONObject caps=needsCapabilities?read("phone_capabilities",Json.obj(),alive):Json.obj();
                for(int i=0;i<plan.size();i++){check(alive);prepared.add(prepare(plan.get(i),i,caps,alive));}
            }catch(Need need) {
                check(alive);pending=new Pending(plan,need.index,need.key,need.prompt,need.choices,clock.getAsLong());awaiting=true;
                return Result.say(need.prompt,true);
            }
            check(alive);
            List<String> replies=new ArrayList<>();
            for(Prepared p:prepared) {
                check(alive);
                if(p.tool().isEmpty()){replies.add(p.description());continue;}
                JSONObject args=p.args();
                String operationId=null;
                if(p.mutation()) {
                    // Obtain today's installation-bound prefix immediately before dispatch.
                    JSONObject caps=read("phone_capabilities",Json.obj(),alive);
                    operationId=caps.getString("operation_prefix")+"_quick_"+UUID.randomUUID().toString().replace("-","");
                    args.put("operation_id",operationId);
                }
                check(alive);if(operationId!=null)operations.add(operationId);
                JSONObject outcome=backend.call(p.tool(),args);
                if(outcome.optBoolean("isError")) {
                    return new Result(false,String.join(" ",replies)+(replies.isEmpty()?"":" ")+"Stopped: "+outcome.optString("message","The command outcome is unknown.")+" No remaining actions were requested.",false,List.copyOf(operations));
                }
                replies.add(p.description());
            }
            return new Result(false,String.join(" ",replies),false,List.copyOf(operations));
        }catch(Fallback fallback){clear();return wasPending?Result.say("That command is no longer available locally. Please repeat the full request for the assistant.",false):Result.fallbackResult();}
        catch(Cancelled cancelled){return new Result(false,operations.isEmpty()?"":"The command was interrupted after an action was requested; check its outcome before retrying.",awaiting,List.copyOf(operations));}
        catch(Exception failure) {
            clear();
            // Includes model/backend failures. A model failure before any dispatch can safely
            // fall back; a dispatched mutation must never be replayed by another executor.
            if(!wasPending&&operations.isEmpty()&&failure instanceof ModelFailure)return Result.fallbackResult();
            return new Result(false,"The local command could not finish. Check permissions and the current phone state before trying again.",false,List.copyOf(operations));
        }
    }
    private static final class ModelFailure extends Exception {ModelFailure(Exception cause){super(cause);}}
    private boolean understood(List<QuickCommandParser.Action> plan) throws ModelFailure {
        try {for(QuickCommandParser.Action action:plan)if(!QuickCommandFacts.exact(action)&&!understanding.accepts(action))return false;return true;}
        catch(Exception failure){throw new ModelFailure(failure);}
    }
    private static void check(BooleanSupplier alive) throws Cancelled {if(!alive.getAsBoolean())throw new Cancelled();}
    private JSONObject read(String tool,JSONObject args,BooleanSupplier alive) throws Exception {
        check(alive);JSONObject result=backend.call(tool,args);check(alive);
        if(result.optBoolean("isError"))throw new IllegalStateException("Direct capability unavailable");return result;
    }
    private Prepared prepare(QuickCommandParser.Action a,int index,JSONObject caps,BooleanSupplier alive) throws Exception {
        switch(a.intent()) {
            case "time": case "date": case "math": case "convert": {
                String answer=QuickCommandFacts.answer(a,clock.getAsLong());if(answer==null)throw new Fallback();return say(answer);
            }
            case "device": {
                JSONObject info=read("phone_device_info",Json.obj(),alive);
                return say(switch(a.get("field")) {
                    case "battery" -> info.isNull("battery_percent")?"Battery level is unavailable.":"Battery is at "+info.getInt("battery_percent")+" percent"+(info.optBoolean("charging")?" and charging.":".");
                    case "network" -> "Your active network is "+info.optString("network","unknown")+". "+(info.optBoolean("internet_validated")?"Internet access is available.":"Internet access is not currently confirmed.");
                    case "volume" -> info.isNull("media_volume_percent")?"Media volume is unavailable.":"Media volume is "+info.optInt("media_volume_percent")+" percent.";
                    case "brightness" -> info.isNull("brightness_percent")?"Screen brightness is unavailable.":"Screen brightness is "+info.optInt("brightness_percent")+" percent.";
                    default -> String.format(java.util.Locale.ENGLISH,"About %.1f GB of storage is available.",info.optLong("available_storage_bytes")/1_000_000_000d);
                });
            }
            case "volume": {
                JSONObject args=Json.obj("action",a.get("action"));if(!a.get("percent").isEmpty())args.put("percent",Integer.parseInt(a.get("percent")));
                return new Prepared("phone_volume",args,"Applied the media volume command.",true);
            }
            case "brightness":return new Prepared("phone_brightness",Json.obj("percent",Integer.parseInt(a.get("percent"))),"Changed manual screen brightness to "+a.get("percent")+" percent.",true);
            case "flashlight":return new Prepared("phone_flashlight",Json.obj("enabled",Boolean.parseBoolean(a.get("enabled"))),"Requested flashlight "+(Boolean.parseBoolean(a.get("enabled"))?"on.":"off."),true);
            case "settings":return new Prepared("phone_settings",Json.obj("screen",a.get("screen"),"display_target","physical"),"Requested "+a.get("screen")+" settings. You can change the setting there.",true);
            case "clock":return new Prepared("phone_clock_screen",Json.obj("screen",a.get("screen"),"display_target","physical"),"Requested the "+a.get("screen")+" screen.",true);
            case "navigation": {
                JSONObject screen=read("phone_observe",Json.obj("display_target","physical","include_image",false,"max_nodes",0),alive);
                return new Prepared("phone_act",Json.obj("display_target","physical","kind","key","key",a.get("key"),"snapshot_id",screen.getString("snapshot_id")),"Requested "+a.get("key")+" navigation.",true);
            }
            case "calendars": {
                JSONArray rows=read("phone_calendars",Json.obj(),alive).getJSONArray("calendars");List<String> names=new ArrayList<>();
                for(int i=0;i<Math.min(rows.length(),10);i++)names.add(rows.getJSONObject(i).optString("name"));return say(names.isEmpty()?"No calendars available.":String.join(", ",names)+".");
            }
            case "app": {
                String pkg=app(a.get("app"),alive);if(pkg==null)throw new Fallback();
                return new Prepared("phone_open_app",Json.obj("package_name",pkg,"display_target","physical"),"Requested opening "+a.get("app")+" on your screen.",true);
            }
            case "url": {
                java.net.URI uri=new java.net.URI(a.get("url"));
                if(uri.getHost()==null||uri.getUserInfo()!=null||!List.of("http","https").contains(uri.getScheme()))throw new Fallback();
                return new Prepared("phone_open_url",Json.obj("url",uri.toString(),"display_target","physical"),"Requested opening the link on your screen.",true);
            }
            case "timer": {
                if(a.get("seconds").isEmpty())throw need(index,"seconds","For how long should I set the timer?");
                return new Prepared("phone_timer",Json.obj("seconds",Integer.parseInt(a.get("seconds"))),"Requested a "+a.get("seconds")+" second timer. The Clock app must accept it.",true);
            }
            case "alarm": {
                if(a.get("time").isEmpty())throw need(index,"time","What time should I set the alarm? Include AM or PM.");
                String[] time=a.get("time").split(":");
                return new Prepared("phone_alarm",Json.obj("hour",Integer.parseInt(time[0]),"minute",Integer.parseInt(time[1])),"Requested the alarm. The Clock app must accept it.",true);
            }
            case "call": case "message": {
                if(a.intent().equals("message")) {
                    if(a.get("channel").isEmpty())throw new Need(index,"channel","Should I use SMS or WhatsApp?",List.of(new Choice("SMS",Map.of("channel","sms")),new Choice("WhatsApp",Map.of("channel","whatsapp"))));
                    if(a.get("text").isEmpty())throw need(index,"text","What should the message say?");
                    // Notification titles cannot establish a contact's phone identity. Always
                    // ask the user to select the actual app-provided conversation before reply.
                    if(a.get("channel").equals("whatsapp"))return whatsapp(a,index,alive);
                }
                recipient(a,index,alive);
                if(a.intent().equals("call")) {
                    chooseSim(a,index,caps.optJSONArray("phone_accounts"),"phone_account_id","id");
                    JSONObject args=Json.obj("number",a.get("number"));if(!a.get("phone_account_id").isEmpty())args.put("phone_account_id",a.get("phone_account_id"));
                    return new Prepared("phone_call",args,"Requested a call to "+a.get("recipient")+". Connection is not yet verified.",true);
                }
                JSONArray sims=caps.optJSONArray("sms_subscriptions");
                int defaultSim=caps.optInt("default_sms_subscription_id",-1);
                if(defaultSim>=0&&a.get("subscription_id").isEmpty())a.set("subscription_id",String.valueOf(defaultSim));
                else chooseSim(a,index,sims,"subscription_id","subscription_id");
                JSONObject args=Json.obj("number",a.get("number"),"text",a.get("text"));
                if(!a.get("subscription_id").isEmpty())args.put("subscription_id",Integer.parseInt(a.get("subscription_id")));
                return new Prepared("phone_sms",args,"Submitted the SMS to "+a.get("recipient")+". Sent and delivery receipts are still pending.",true);
            }
            case "contacts": {
                JSONArray found=read("phone_contacts",Json.obj("query",a.get("recipient"),"limit",6),alive).getJSONArray("contacts");
                if(found.length()==0)return say("No matching contacts found.");
                List<String> names=new ArrayList<>();for(int i=0;i<Math.min(found.length(),5);i++)names.add(contactLabel(found.getJSONObject(i)));
                return say(String.join("; ",names)+(found.length()>5?". More matches exist; use a fuller name.":"."));
            }
            case "apps": {
                JSONArray apps=read("phone_apps",Json.obj(),alive).getJSONArray("apps");List<String> labels=new ArrayList<>();
                for(int i=0;i<Math.min(apps.length(),12);i++)labels.add(apps.getJSONObject(i).getString("label"));
                return say(String.join(", ",labels)+(apps.length()>12?". There are more apps.":"."));
            }
            case "notifications": {
                JSONArray rows=read("phone_notification_read",Json.obj("limit",5),alive).getJSONArray("notifications");List<String> lines=new ArrayList<>();
                for(int i=0;i<rows.length();i++){JSONObject n=rows.getJSONObject(i);lines.add(n.optString("title")+": "+n.optString("text"));}
                return say(lines.isEmpty()?"No current notifications.":bounded(String.join("; ",lines)));
            }
            case "calendar": {
                ZoneId zone=ZoneId.systemDefault();LocalDate day=java.time.Instant.ofEpochMilli(clock.getAsLong()).atZone(zone).toLocalDate();
                JSONArray rows=read("phone_calendar_events",Json.obj("start_ms",day.atStartOfDay(zone).toInstant().toEpochMilli(),"end_ms",day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),"limit",5),alive).getJSONArray("events");
                List<String> events=new ArrayList<>();for(int i=0;i<rows.length();i++)events.add(rows.getJSONObject(i).optString("title"));
                return say(events.isEmpty()?"No events found for today.":"Today's events include: "+bounded(String.join("; ",events)));
            }
            case "media": {
                String pkg=a.get("app").isEmpty()?null:app(a.get("app"),alive);if(!a.get("app").isEmpty()&&pkg==null)throw new Fallback();
                JSONArray sessions=caps.optJSONArray("media_sessions");List<String> matches=new ArrayList<>();
                if(sessions!=null)for(int i=0;i<sessions.length();i++){String p=sessions.getJSONObject(i).getString("package_name");if(pkg==null||pkg.equals(p))matches.add(p);}
                if(matches.size()!=1)throw new Fallback();
                return new Prepared("phone_media",Json.obj("package_name",matches.get(0),"action",a.get("action")),"Requested media "+a.get("action")+".",true);
            }
            default: throw new Fallback();
        }
    }
    private static String bounded(String s){return s.length()>700?s.substring(0,700)+". More items are available.":s;}
    private static Prepared say(String text){return new Prepared("",Json.obj(),text,false);}
    private static Need need(int index,String key,String prompt){return new Need(index,key,prompt,List.of());}
    private String app(String query,BooleanSupplier alive) throws Exception {
        JSONArray apps=read("phone_apps",Json.obj(),alive).getJSONArray("apps");String norm=QuickCommandParser.normalize(query);Map<String,String> matches=new LinkedHashMap<>();
        for(int i=0;i<apps.length();i++){JSONObject app=apps.getJSONObject(i);if(QuickCommandParser.normalize(app.getString("label")).equals(norm)||app.getString("package_name").equals(query))matches.put(app.getString("package_name"),app.getString("label"));}
        return matches.size()==1?matches.keySet().iterator().next():null;
    }
    private void recipient(QuickCommandParser.Action a,int index,BooleanSupplier alive) throws Exception {
        if(!a.get("number").isEmpty())return;
        String query=a.get("recipient");if(query.matches("\\+?[0-9 ()-]{3,26}")){a.set("number",query);return;}
        JSONArray contacts=read("phone_contacts",Json.obj("query",query,"limit",100),alive).getJSONArray("contacts");
        if(contacts.length()==0)throw need(index,"recipient","I couldn't find that contact. What is their full name or phone number?");
        // Never select from an incomplete list. Narrow search rather than truncating identities.
        if(contacts.length()>=100)throw need(index,"recipient","There are too many contacts. What is their full name or phone number?");
        List<JSONObject> candidates=new ArrayList<>(),exact=new ArrayList<>();Map<String,JSONObject> distinct=new LinkedHashMap<>();
        for(int i=0;i<contacts.length();i++){JSONObject c=contacts.getJSONObject(i);distinct.put(c.optLong("contact_id")+":"+c.getString("number").replaceAll("[^0-9+]",""),c);}
        for(JSONObject c:distinct.values()){candidates.add(c);if(QuickCommandParser.normalize(c.getString("name")).equals(QuickCommandParser.normalize(query)))exact.add(c);}
        if(!exact.isEmpty())candidates=exact;
        if(exact.size()==1){a.set("number",exact.get(0).getString("number"));a.set("recipient",exact.get(0).getString("name"));return;}
        if(candidates.size()>5)throw need(index,"recipient","I found several contacts. What is their full name or phone number?");
        List<Choice> choices=new ArrayList<>();for(JSONObject c:candidates)choices.add(new Choice(contactLabel(c),Map.of("recipient",c.getString("name"),"number",c.getString("number"))));
        throw choices(index,"number","Which contact?",choices);
    }
    private static String contactLabel(JSONObject c){String digits=c.optString("number").replaceAll("\\D","");return c.optString("name")+", number ending "+digits.substring(Math.max(0,digits.length()-4));}
    private void chooseSim(QuickCommandParser.Action a,int index,JSONArray rows,String slot,String idKey) throws Exception {
        if(!a.get(slot).isEmpty()||rows==null||rows.length()==0)return;
        if(rows.length()==1){a.set(slot,String.valueOf(rows.getJSONObject(0).get(idKey)));return;}
        List<Choice> choices=new ArrayList<>();for(int i=0;i<Math.min(rows.length(),5);i++){JSONObject row=rows.getJSONObject(i);choices.add(new Choice(row.optString("label","SIM "+(i+1)),Map.of(slot,String.valueOf(row.get(idKey)))));}
        throw choices(index,slot,"Which SIM should I use?",choices);
    }
    private Prepared whatsapp(QuickCommandParser.Action a,int index,BooleanSupplier alive) throws Exception {
        JSONArray rows;
        try {rows=read("phone_notifications",Json.obj(),alive).getJSONArray("reply_actions");}
        catch(IllegalStateException unavailable){throw new Fallback();}
        List<Choice> choices=new ArrayList<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);String pkg=row.optString("package_name");
            if(!pkg.equals("com.whatsapp")&&!pkg.equals("com.whatsapp.w4b"))continue;
            // Groups and unknown conversation types cannot establish an individual recipient.
            if(!row.has("group_conversation")||row.isNull("group_conversation")||row.optBoolean("group_conversation"))continue;
            String title=row.optString("title"),norm=QuickCommandParser.normalize(title),wanted=QuickCommandParser.normalize(a.get("recipient"));
            if(!norm.equals(wanted)&&!norm.startsWith(wanted+" "))continue;
            String key=row.getString("notification_key");long posted=row.getLong("posted_at_ms");
            if(!a.get("notification_key").isEmpty()&&a.get("notification_key").equals(key)&&a.get("posted_at_ms").equals(String.valueOf(posted))) {
                return new Prepared("phone_reply",Json.obj("notification_key",key,"posted_at_ms",posted,"action_index",Integer.parseInt(a.get("action_index")),"text",a.get("text")),"Submitted the reply through WhatsApp. Delivery is not yet verified.",true);
            }
            choices.add(new Choice(title+" in "+(pkg.endsWith("w4b")?"WhatsApp Business":"WhatsApp"),Map.of("notification_key",key,"posted_at_ms",String.valueOf(posted),"action_index",String.valueOf(row.getInt("action_index")))));
        }
        if(choices.isEmpty()||choices.size()>5)throw new Fallback();
        throw choices(index,"notification_key","I can reply to these current notifications. Which conversation should receive the message?",choices);
    }
    private static Need choices(int index,String key,String title,List<Choice> choices) {
        StringBuilder prompt=new StringBuilder(title);for(int i=0;i<choices.size();i++)prompt.append(" ").append(i+1).append(": ").append(choices.get(i).label()).append(".");
        return new Need(index,key,prompt.toString(),choices);
    }
    private boolean resolve(Pending p,String text) {
        QuickCommandParser.Action a=p.plan().get(p.index());String normalized=QuickCommandParser.normalize(text);
        if(!p.choices().isEmpty()) {
            String answer=normalized.replaceFirst("^(?:the |number |option |contact )","");
            int ordinal=-1;String remainder=answer;
            String[] names={"first","second","third","fourth","fifth"};
            String[] numbers={"one","two","three","four","five"};
            for(int i=0;i<5;i++)for(String word:new String[]{names[i],numbers[i],String.valueOf(i+1)})if(answer.equals(word)||answer.startsWith(word+" ")){ordinal=i;remainder=answer.substring(word.length()).trim().replaceFirst("^(?:one|please)\\b\\s*","");}
            List<Integer> matches=new ArrayList<>();
            for(int i=0;i<p.choices().size();i++) {
                Choice c=p.choices().get(i);String name=QuickCommandParser.normalize(c.values().getOrDefault("recipient",c.label()));
                String digits=c.values().getOrDefault("number","").replaceAll("\\D","");
                boolean named=!remainder.isEmpty()&&(name.equals(remainder)||name.startsWith(remainder+" ")||name.endsWith(" "+remainder)||remainder.matches("(?:number )?ending \\d{4}")&&digits.endsWith(remainder.substring(remainder.length()-4)));
                if(ordinal>=0?i==ordinal&&(remainder.isEmpty()||named):named)matches.add(i);
            }
            if(matches.size()!=1)return false;
            for(Map.Entry<String,String> entry:p.choices().get(matches.get(0)).values().entrySet())a.set(entry.getKey(),entry.getValue());
            return true;
        }
        switch(p.key()) {
            case "seconds": {int seconds=QuickCommandParser.duration(text);if(seconds<1)return false;a.set("seconds",String.valueOf(seconds));return true;}
            case "time": {String time=QuickCommandParser.alarm(text);if(time==null)return false;a.set("time",time);return true;}
            case "recipient": if(text.matches("[\\p{L}\\p{N}+' .()_-]{1,100}")){a.set("recipient",text.trim());a.slots().remove("number");return true;}return false;
            case "text": if(text.isBlank()||text.length()>800)return false;
                String body=text.trim().replaceFirst("(?i)^(?:say|the message is|message is)\\s+","");if(body.isBlank())return false;
                a.set("text",body);return true;
            default: return false;
        }
    }
}
