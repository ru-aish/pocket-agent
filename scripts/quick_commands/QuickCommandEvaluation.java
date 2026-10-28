package dev.pocket.agent;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;

/** Runs production parser, encoder and dialogue engine against isolated fake Android tools. */
public final class QuickCommandEvaluation {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static final class Phone implements QuickCommandEngine.Backend {
        final List<String> mutations=new ArrayList<>();final List<JSONObject> arguments=new ArrayList<>();
        JSONArray contacts=Json.arr(Json.obj("contact_id",1,"name","Rahul Shah","number","+919000001111"),Json.obj("contact_id",2,"name","Rahul Mehta","number","+919000002222"));
        JSONArray replies=new JSONArray();JSONArray sims=new JSONArray();boolean fail,changed;
        public JSONObject call(String tool,JSONObject args) throws Exception {
            return switch(tool) {
                case "phone_capabilities" -> Json.obj("operation_prefix","20000-0123456789abcdef","phone_accounts",sims,"sms_subscriptions",new JSONArray(),"media_sessions",Json.arr(Json.obj("package_name","com.spotify.music")));
                case "phone_apps" -> Json.obj("apps",Json.arr(Json.obj("label","Spotify","package_name","com.spotify.music"),Json.obj("label","YouTube","package_name","com.google.android.youtube"),Json.obj("label","Calculator","package_name","com.android.calculator")));
                case "phone_observe" -> Json.obj("snapshot_id","physical-1");
                case "phone_contacts" -> Json.obj("contacts",contacts);
                case "phone_notifications" -> Json.obj("reply_actions",replies);
                case "phone_notification_read" -> Json.obj("notifications",Json.arr(Json.obj("title","Calendar","text","Meeting in ten minutes")));
                case "phone_device_info" -> Json.obj("battery_percent",72,"charging",true,"network","Wi-Fi","internet_validated",true,"media_volume_percent",50,"brightness_percent",40,"available_storage_bytes",12345678900L);
                case "phone_calendar_events" -> Json.obj("events",Json.arr(Json.obj("title","Team meeting")));
                case "phone_calendars" -> Json.obj("calendars",Json.arr(Json.obj("name","Personal")));
                default -> {mutations.add(tool);arguments.add(new JSONObject(args.toString()));yield fail?Json.failure("RESULT_UNKNOWN","Outcome uncertain; inspect before repeating"):Json.obj("status","requested");}
            };
        }
    }
    static QuickCommandEngine engine(Phone p,AtomicLong time){return new QuickCommandEngine(p,a->true,time::get);}
    static QuickCommandEngine.Result run(QuickCommandEngine e,String text){return e.handle(text,()->true);}
    static void dialogues() {
        AtomicLong time=new AtomicLong(1700000000000L);Phone phone=new Phone();QuickCommandEngine e=engine(phone,time);
        var r=run(e,"Text Rahul saying I will be late");check(r.awaiting()&&phone.mutations.isEmpty(),"Ambiguity must not send");
        r=run(e,"first Rahul Mehta");check(r.awaiting()&&phone.mutations.isEmpty(),"Conflicting ordinal/name must not send");
        r=run(e,"first Rahul Shah");check(!r.awaiting()&&phone.mutations.equals(List.of("phone_sms")),"Ordinal plus matching name");check(phone.arguments.get(0).getString("number").endsWith("1111"),"Resolved exact number");
        check(phone.arguments.get(0).getString("text").equals("I will be late"),"Preserve body case");check(!r.speech().contains("delivered."),"No false delivery claim");
        int count=phone.mutations.size();run(e,"Text Rahul saying hello");r=run(e,"Rahul Mehta");check(!r.awaiting()&&phone.arguments.get(count).getString("number").endsWith("2222"),"Name selection");
        run(e,"Text Rahul saying hello");r=run(e,"the second one");check(!r.awaiting(),"Spoken ordinal");
        run(e,"Text Rahul saying hello");r=run(e,"number ending 1111");check(!r.awaiting(),"Number suffix selection");
        phone.contacts=Json.arr(Json.obj("contact_id",1,"name","Rahul","number","+919000001111"),Json.obj("contact_id",1,"name","Rahul","number","+919000002222"));
        r=run(e,"Text Rahul saying hello");check(r.awaiting(),"One person multiple numbers must ask");run(e,"cancel");
        count=phone.mutations.size();r=run(e,"Open Spotify and set a timer");check(r.awaiting()&&phone.mutations.size()==count,"Resolve whole compound before any action");r=run(e,"ten minutes and thirty seconds");check(!r.awaiting()&&phone.mutations.size()==count+2,"Compound resolves and executes both");check(phone.arguments.get(count+1).getInt("seconds")==630,"Combined duration");
        count=phone.mutations.size();r=run(e,"Open Spotify and research the latest news");check(r.fallback()&&phone.mutations.size()==count,"Unknown suffix cannot be discarded");
        run(e,"Text Rahul saying hello");time.addAndGet(120001);r=run(e,"first");check(r.fallback(),"Expired selection cannot send");
        run(e,"Text Rahul saying hello");r=run(e,"what time is it");check(!r.awaiting()&&!r.fallback(),"Independent question abandons pending send");count=phone.mutations.size();run(e,"first");check(phone.mutations.size()==count,"Abandoned send stays abandoned");
        r=e.handle("Open Spotify",()->false);check(phone.mutations.size()==count,"Cancellation gates side effects");
        phone.fail=true;r=run(e,"Open Spotify and set a timer for one minute");check(!r.fallback()&&phone.mutations.size()==count+1,"Uncertain result stops plan without fallback replay");phone.fail=false;
        for(JSONObject args:phone.arguments)check(args.getString("operation_id").matches("20000-0123456789abcdef_quick_[a-f0-9]{32}"),"Installation-scoped operation IDs");
        check(phone.arguments.stream().map(a->a.getString("operation_id")).distinct().count()==phone.arguments.size(),"Distinct mutation IDs");
        phone.replies=Json.arr(Json.obj("package_name","com.whatsapp","notification_key","wa1","posted_at_ms",123,"action_index",0,"title","Rahul Shah","group_conversation",false));
        count=phone.mutations.size();r=run(e,"WhatsApp Rahul saying hello");check(r.awaiting()&&phone.mutations.size()==count,"Notification recipient must be explicitly selected");r=run(e,"first");check(phone.mutations.size()==count+1&&phone.mutations.get(count).equals("phone_reply"),"Selected WhatsApp reply");
        run(e,"WhatsApp Rahul saying hi");phone.replies=new JSONArray();r=run(e,"first");check(!r.fallback()&&phone.mutations.size()==count+1,"Expired notification selection must not turn 'first' into an agent send");
        phone.replies=Json.arr(Json.obj("package_name","com.whatsapp","notification_key","wa1","posted_at_ms",123,"action_index",0,"title","Rahul Group","group_conversation",true));r=run(e,"WhatsApp Rahul saying hi");check(r.fallback(),"Groups must not masquerade as individuals");
        r=run(e,"Send a message to Rahul saying hello");check(r.awaiting()&&r.speech().contains("SMS"),"Unspecified channel asks");run(e,"cancel");
        phone.contacts=new JSONArray();r=run(e,"Text Unknown saying hi");check(r.awaiting(),"Missing contact asks");run(e,"cancel");
        phone.sims=Json.arr(Json.obj("id","sim1","label","Personal"),Json.obj("id","sim2","label","Work"));
        r=run(e,"Call +14155550123");check(r.awaiting(),"Dual SIM calls ask");r=run(e,"Work");check(phone.arguments.get(phone.arguments.size()-1).getString("phone_account_id").equals("sim2"),"SIM name selects correct account");
        Phone infoPhone=new Phone();QuickCommandEngine facts=new QuickCommandEngine(infoPhone,a->{throw new AssertionError("Facts cannot load the model");},time::get);
        check(run(facts,"Calculate 12 plus 35").speech().equals("47."),"Arithmetic value");
        check(run(facts,"Convert 5 miles to kilometers").speech().equals("8.0467 kilometers."),"Conversion value");
        check(run(facts,"Convert 10 kilograms to kilometers").fallback(),"Unit dimensions cannot be mixed");
        check(run(facts,"What time is it in UTC?").speech().contains("UTC"),"Explicit timezone");
        check(run(facts,"What time is it in nowhere?").fallback(),"Unknown timezone cannot silently use local time");
        check(run(facts,"How much battery is left?").speech().contains("72 percent"),"Battery read-back");
        Phone missing=new Phone();missing.contacts=Json.arr(Json.obj("contact_id",1,"name","Rahul","number","+919000001111"));
        QuickCommandEngine missingEngine=engine(missing,time);
        check(run(missingEngine,"Text Rahul").awaiting(),"Missing body asks");
        check(!run(missingEngine,"What time is it?").awaiting()&&missing.mutations.isEmpty(),"New exact question cannot be sent as a missing body");
        run(missingEngine,"Text Rahul");run(missingEngine,"message is Go home");
        check(missing.arguments.get(0).getString("text").equals("Go home"),"Explicit command-like dictation");
        Phone singlePartial=new Phone();singlePartial.contacts=Json.arr(Json.obj("contact_id",4,"name","Rahul Shah","number","+919000001111"));
        QuickCommandEngine partialEngine=engine(singlePartial,time);
        check(run(partialEngine,"Text Rahul saying hello").awaiting(),"A single partial-name match must require selection");
        check(singlePartial.mutations.isEmpty(),"Partial-name match cannot dispatch automatically");
        run(partialEngine,"first");check(singlePartial.mutations.equals(List.of("phone_sms")),"Explicit selection resolves a single partial match");
        Phone interrupted=new Phone();java.util.concurrent.atomic.AtomicBoolean alive=new java.util.concurrent.atomic.AtomicBoolean(true);
        QuickCommandEngine cancellation=new QuickCommandEngine((tool,args)->{JSONObject result=interrupted.call(tool,args);if(tool.equals("phone_open_app"))alive.set(false);return result;},a->true,time::get);
        var cancelled=cancellation.handle("Open Spotify and set a timer for one minute",alive::get);
        check(cancelled.operations().size()==1&&!cancelled.speech().isEmpty()&&interrupted.mutations.equals(List.of("phone_open_app")),"Interrupted dispatch retains a reviewable outcome and never starts the second action");
        check(QuickCommandParser.parse("Text Rahul saying \"hi\" and \"bye\"").isEmpty(),"Nested quotes cannot become a partial dictated message");
        System.out.println("Dialogue and execution checks passed");
    }
    static void generate(Path assets) throws Exception {
        List<String> lines=Files.readAllLines(assets.resolve("prototypes.tsv")).stream().filter(line->!line.isBlank()).toList();
        if(lines.isEmpty()||lines.size()>500)throw new IllegalArgumentException("Expected 1..500 nonempty prototypes");
        for(String line:lines){String[] fields=line.split("\\t",2);if(fields.length!=2||fields[0].isBlank()||fields[1].isBlank())throw new IllegalArgumentException("Each prototype needs a label, tab and example");List<QuickCommandParser.Action> parsed=QuickCommandParser.parse(fields[1]);if(!fields[0].equals("other")&&(parsed.size()!=1||!parsed.get(0).intent().equals(fields[0])))throw new IllegalArgumentException("Prototype does not match its intent: "+line);}
        Path temporary=Files.createTempFile(assets,"prototypes-",".tmp");
        try {
            try(QuickCommandEncoder encoder=new QuickCommandEncoder(assets.resolve("model.onnx").toString(),Files.newInputStream(assets.resolve("vocab.txt")),null);
                DataOutputStream out=new DataOutputStream(Files.newOutputStream(temporary))) {
                out.writeInt(0x51434d31);out.writeInt(lines.size());
                for(String line:lines){String[] fields=line.split("\\t",2);List<QuickCommandParser.Action> parsed=QuickCommandParser.parse(fields[1]);String query=parsed.size()==1?QuickCommandEncoder.semanticText(parsed.get(0)):fields[1];out.writeUTF(fields[0]);for(float value:encoder.embed(query))out.writeFloat(value);}
            }
            Files.move(temporary,assets.resolve("prototypes.bin"),StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
    public static void main(String[] args) throws Exception {
        Path assets=Path.of(args[0]);if(args.length>1&&args[1].equals("--generate")){generate(assets);return;}
        dialogues();
        JSONArray cases=new JSONArray(Files.readString(Path.of(args[1]))),failures=new JSONArray();List<Long> times=new ArrayList<>();
        if(cases.isEmpty())throw new IllegalArgumentException("Evaluation cases cannot be empty");
        Set<String> examples=new HashSet<>();for(String line:Files.readAllLines(assets.resolve("prototypes.tsv"))){String[] parts=line.split("\\t",2);if(parts.length==2)examples.add(parts[1].toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", ""));}
        for(int i=0;i<cases.length();i++)if(examples.contains(cases.getJSONObject(i).getString("text").toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", "")))throw new IllegalArgumentException("Evaluation request duplicates a prototype: "+cases.getJSONObject(i).getString("text"));
        long loadAt=System.nanoTime();
        try(QuickCommandEncoder encoder=new QuickCommandEncoder(assets.resolve("model.onnx").toString(),Files.newInputStream(assets.resolve("vocab.txt")),Files.newInputStream(assets.resolve("prototypes.bin")))) {
            long loadMs=(System.nanoTime()-loadAt)/1000000;
            int accepted=0,rejected=0,expectedIntents=0,expectedMutations=0;
            for(int i=0;i<cases.length();i++) {
                JSONObject c=cases.getJSONObject(i);String text=c.getString("text");long at=System.nanoTime();List<QuickCommandParser.Action> plan=QuickCommandParser.parse(text);
                List<String> labels=new ArrayList<>();JSONArray ranks=new JSONArray();boolean supported=!plan.isEmpty();
                for(var a:plan)labels.add(a.intent());
                if(c.has("slots")) {JSONObject slots=c.getJSONObject("slots");for(String key:slots.keySet())check(!plan.isEmpty()&&plan.get(0).get(key).equals(slots.getString(key)),"Wrong slot: "+text+" "+key);}
                Phone phone=new Phone();QuickCommandEngine production=new QuickCommandEngine(phone,a->QuickCommandFacts.exact(a)||encoder.accepts(a),System::currentTimeMillis);
                phone.replies=Json.arr(Json.obj("package_name","com.whatsapp","notification_key","wa-mum","posted_at_ms",123,"action_index",0,"title","Mum","group_conversation",false),Json.obj("package_name","com.whatsapp","notification_key","wa-alex","posted_at_ms",123,"action_index",0,"title","Alex","group_conversation",false));
                QuickCommandEngine.Result routed=run(production,text);supported=!routed.fallback();
                if(c.has("expected_intents")){JSONArray want=c.getJSONArray("expected_intents");List<String> expectedLabels=new ArrayList<>();for(int n=0;n<want.length();n++)expectedLabels.add(want.getString(n));check(labels.equals(expectedLabels),"Wrong intents for "+text+": "+labels);expectedIntents++;}
                if(c.has("expected_mutations")){JSONArray want=c.getJSONArray("expected_mutations");List<String> expectedTools=new ArrayList<>();for(int n=0;n<want.length();n++)expectedTools.add(want.getString(n));check(phone.mutations.equals(expectedTools),"Wrong native mutations for "+text+": "+phone.mutations);expectedMutations++;}
                times.add((System.nanoTime()-at)/1000000);
                boolean expected=c.optBoolean("local");
                if(expected)accepted++;else rejected++;
                if(supported!=expected){for(var a:plan)if(!QuickCommandFacts.exact(a)){var rank=encoder.rank(QuickCommandEncoder.semanticText(a));ranks.put(Json.obj("intent",a.intent(),"top",rank.label(),"score",rank.score(),"margin",rank.margin()));}failures.put(Json.obj("text",text,"expected_local",expected,"parsed",labels,"ranks",ranks));}
            }
            Collections.sort(times);
            JSONObject report=Json.obj("cases",cases.length(),"positive_cases",accepted,"negative_cases",rejected,"cases_with_intent_assertions",expectedIntents,"cases_with_mutation_assertions",expectedMutations,"failures",failures,"host_model_load_ms",loadMs,"host_request_p50_ms",times.get(times.size()/2),"host_request_p95_ms",times.get(Math.min(times.size()-1,(int)(times.size()*.95))),"model_bytes",Files.size(assets.resolve("model.onnx")),"note","Host JVM inference and fake Android backends; not phone microphone latency or actual message delivery.");
            System.out.println(report.toString(2));if(args.length>2)Files.writeString(Path.of(args[2]),report.toString(2)+"\n");
            check(failures.isEmpty(),"Request evaluation failed");
        }
    }
}
