package dev.pocket.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded English slot grammar. An encoder may reject a parse, never invent tool arguments. */
final class QuickCommandParser {
    record Action(String intent, String source, Map<String,String> slots) {
        String get(String key) { return slots.getOrDefault(key, ""); }
        void set(String key, String value) { slots.put(key,value); }
    }
    private static final String VERBS = "(?:open|launch|start|bring|pull|fire|take|set|create|send|text|message|whatsapp|call|dial|ring|pause|resume|play|skip|go|find|look|show|read|list|check|turn|increase|decrease|raise|lower|mute|unmute|what|tell|calculate|convert)\\b";
    private static final Pattern JOIN = Pattern.compile("(?i)(?:,?\\s+(?:and then|and also|and|then|also)\\s+|;\\s*)(?=(?:(?:please|can you|could you|would you)\\s+)?"+VERBS+")");
    private static final Pattern NUMBER = Pattern.compile("(?i)([a-z -]+?|\\d+)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b");
    private static Matcher match(String expression,String text) { Matcher m=Pattern.compile(expression,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(text);return m.matches()?m:null; }
    static String normalize(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll("\\s+"," "); }
    private static String polite(String s) {
        String clean=s.trim().replaceFirst("(?i)^hey[, ]+jarvis[,!?. ]*","")
            .replaceFirst("(?i)^(?:(?:can|could|would|will) you(?: please)?|please|i(?:'d| would) like (?:you )?to)\\s+","").trim();
        return isMessage(clean)?clean:clean.replaceFirst("(?i)\\s+please[.!?]*$","").replaceFirst("[.!?]+$","").trim();
    }
    private static Action action(String intent,String source,String... values) {
        Map<String,String> slots=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)slots.put(values[i],values[i+1]);
        return new Action(intent,source,slots);
    }
    static List<Action> parse(String input) {
        if(input==null||input.length()>1000) return List.of();
        String text=polite(input);if(text.isEmpty())return List.of();
        // Only split conjunctions outside quoted message bodies. Unquoted messages consume
        // their remaining text; action-like conjunctions there are ambiguous and fall back.
        List<String> clauses=new ArrayList<>();int from=0;boolean quoted=false;
        Matcher joins=JOIN.matcher(text);
        for(int i=0;i<text.length();i++) {
            if(text.charAt(i)=='"')quoted=!quoted;
            if(!quoted&&joins.find(i)&&joins.start()==i) {
                String previous=text.substring(from,i);
                if(isMessage(previous)&&!previous.contains("\""))return List.of();
                clauses.add(previous);from=joins.end();i=from-1;
            }
        }
        if(quoted)return List.of();clauses.add(text.substring(from));
        if(clauses.size()>4)return List.of();
        List<Action> plan=new ArrayList<>();
        for(String clause:clauses) {Action a=parseOne(polite(clause));if(a==null)return List.of();plan.add(a);}
        return plan;
    }
    private static boolean isMessage(String text) {return text.matches("(?is)^(?:send|text|message|whatsapp)\\b.*");}
    private static Action parseOne(String s) {
        Matcher m;
        Action fact=QuickCommandFacts.parse(s);if(fact!=null)return fact;
        // Full-clause matches prevent executing a known prefix while dropping other work.
        if((m=match("(?:send (?:an? )?(?:sms|text(?: message)?|message|whatsapp(?: message)?) to|text|message|whatsapp) (.+?)(?:\\s+(?:saying|that says|with the message|that)|\\s*:)\\s+(.+)",s))!=null) {
            String body=m.group(2).trim();
            if(body.startsWith("\"")&&body.endsWith("\"")){body=body.substring(1,body.length()-1);if(body.contains("\""))return null;}
            else if(body.contains("\"")||body.matches("(?is).*\\b(?:and|then|also)\\b.*"))return null;
            String head=s.substring(0,m.start(2));
            String channel=head.toLowerCase(Locale.ROOT).contains("whatsapp")?"whatsapp":head.matches("(?is).*\\b(?:sms|text)\\b.*")?"sms":"";
            if(!validRecipient(m.group(1))||body.isBlank()||body.length()>800)return null;
            return action("message",s,"recipient",m.group(1).trim(),"text",body,"channel",channel);
        }
        if(s.matches("(?is).*\\b(?:not|never|dont|don't|do not|unless|if|when|after|before|instead|except|without|cancel|delete|tomorrow|every|weekday)\\b.*"))return null;
        if((m=match("(?:turn|switch) (?:the )?(flashlight|torch) (on|off)|(?:turn|switch) (on|off) (?:the )?(flashlight|torch)",s))!=null)
            return action("flashlight",s,"enabled",String.valueOf("on".equalsIgnoreCase(m.group(2)==null?m.group(3):m.group(2))));
        if((m=match("(?:set|change|adjust) (?:the |my )?(?:media |music )?volume to (\\d{1,3})(?: percent|%)",s))!=null) {
            int percent=Integer.parseInt(m.group(1));return percent<=100?action("volume",s,"action","set","percent",String.valueOf(percent)):null;
        }
        if((m=match("(?:turn (?:the |my )?(?:media |music )?volume (up|down)|(?:increase|raise|lower|decrease) (?:the |my )?(?:media |music )?volume|(mute|unmute)(?: (?:the |my )?(?:music|media|volume))?)",s))!=null) {
            String act=m.group(1)!=null?m.group(1):m.group(2)!=null?m.group(2):s.matches("(?i)^(?:increase|raise)\\b.*")?"up":"down";
            return action("volume",s,"action",act.toLowerCase(Locale.ROOT));
        }
        if((m=match("(?:set|change|adjust) (?:the |my )?(?:screen )?brightness to (\\d{1,3})(?: percent|%)",s))!=null) {
            int percent=Integer.parseInt(m.group(1));return percent<=100?action("brightness",s,"percent",String.valueOf(percent)):null;
        }
        if((m=match("(?:open|show(?: me)?) (?:the )?(wi-?fi|bluetooth|display|sound|location|airplane(?: mode)?|battery) settings",s))!=null)
            return action("settings",s,"screen",m.group(1).toLowerCase(Locale.ROOT).replace("-","").replace(" mode",""));
        if((m=match("(?:show(?: me)?|open) (?:the )?(alarms|timers)(?: screen)?",s))!=null)return action("clock",s,"screen",m.group(1).toLowerCase(Locale.ROOT));
        if(s.matches("(?i)(?:go (?:to (?:the )?)?home|show (?:me )?(?:the )?home screen)"))return action("navigation",s,"key","home");
        if(s.matches("(?i)(?:go back|back)"))return action("navigation",s,"key","back");
        if(s.matches("(?i)(?:show (?:me )?(?:my )?recent apps|open recents)"))return action("navigation",s,"key","recents");
        if((m=match("(?:send (?:an? )?(?:sms|text(?: message)?|message|whatsapp(?: message)?) to|text|message|whatsapp) (.+)",s))!=null) {
            if(!validRecipient(m.group(1)))return null;
            String channel=s.toLowerCase(Locale.ROOT).contains("whatsapp")?"whatsapp":s.matches("(?is).*\\b(?:sms|text)\\b.*")?"sms":"";
            return action("message",s,"recipient",m.group(1).trim(),"text","","channel",channel);
        }
        if((m=match("(?:open|launch|start|bring up|pull up|fire up|take me to) (.+)",s))!=null&&!s.toLowerCase(Locale.ROOT).contains("timer")) {
            String app=m.group(1).replaceFirst("(?i)^(?:the )?app\\s+","").replaceFirst("(?i)\\s+app$","");
            if(app.matches("(?i)https?://\\S+"))return action("url",s,"url",app);
            return action("app",s,"app",app);
        }
        if((m=match("(?:(?:set|start|create|give me) (?:me )?(?:a |the )?(?:countdown )?timer)(?: for)?(?: (.+))?",s))!=null) {
            String raw=m.group(1)==null?"":m.group(1);int seconds=duration(raw);
            if(!raw.isEmpty()&&seconds<1)return null;
            return action("timer",s,"seconds",seconds<1?"":String.valueOf(seconds));
        }
        if((m=match("(?:set|create) (?:me )?(?:an? |the )?alarm(?: for| at)?(?: (.+))?",s))!=null) {
            String raw=m.group(1)==null?"":m.group(1);String time=alarm(raw);
            if(!raw.isEmpty()&&time==null)return null;
            return action("alarm",s,"time",time==null?"":time);
        }
        if((m=match("(?:call|dial|ring) (.+)",s))!=null&&validRecipient(m.group(1)))return action("call",s,"recipient",m.group(1).trim());
        if((m=match("(?:find|look up|search for|show me) (?:the )?(?:contact(?:s)?(?: for)?|phone number (?:of|for)) (.+)",s))!=null&&validRecipient(m.group(1)))return action("contacts",s,"recipient",m.group(1).trim());
        if(s.matches("(?i)(?:list|show(?: me)?) (?:my |the )?(?:installed )?apps"))return action("apps",s);
        if(s.matches("(?i)(?:read|show(?: me)?|check|list) (?:my |the )?(?:recent |current )?notifications"))return action("notifications",s);
        if((m=match("(?:read|show(?: me)?|check|list) (?:my |the )?(?:calendar|events|schedule)(?: for)? (today)",s))!=null)return action("calendar",s,"day",m.group(1));
        if(s.matches("(?i)(?:list|show(?: me)?) (?:my |the )?calendars"))return action("calendars",s);
        if((m=match("(pause|resume|play|skip|next|previous)(?: (?:the |my )?(?:music|song|track|playback))?(?: (?:on|in) (.+))?",s))!=null) {
            String verb=m.group(1).toLowerCase(Locale.ROOT);String command=switch(verb){case "resume"->"play";case "skip"->"next";default->verb;};
            return action("media",s,"action",command,"app",m.group(2)==null?"":m.group(2));
        }
        return null;
    }
    private static boolean validRecipient(String s) {return s.length()<=100&&s.matches("[\\p{L}\\p{N}+' .()_-]+")&&!s.matches("(?is).*\\b(?:and|then|or|not|dont|don't|if|when|that|saying|to|message|last|previous|him|her|them|someone)\\b.*");}
    static int duration(String raw) {
        if(raw==null||raw.isBlank())return -1;
        Matcher m=NUMBER.matcher(raw.trim());int end=0,total=0;boolean found=false;
        while(m.find()) {
            if(!raw.substring(end,m.start()).trim().matches("(?i)(?:and)?"))return -1;
            int n=number(m.group(1).trim().replaceFirst("(?i)^and\\s+",""));if(n<0)return -1;
            String unit=m.group(2).toLowerCase(Locale.ROOT);long part=(long)n*(unit.startsWith("h")?3600:unit.startsWith("m")?60:1);
            if(part>86400)return -1;total+=(int)part;end=m.end();found=true;
        }
        return found&&raw.substring(end).isBlank()&&total>0&&total<=86400?total:-1;
    }
    static int number(String raw) {
        String s=normalize(raw);if(s.matches("\\d{1,5}"))return Integer.parseInt(s);
        String[] small={"zero","one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen"};
        for(int i=0;i<small.length;i++)if(s.equals(small[i]))return i;
        String[] tens={"twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety"};
        for(int i=0;i<tens.length;i++){if(s.equals(tens[i]))return (i+2)*10;if(s.startsWith(tens[i]+" ")){int n=number(s.substring(tens[i].length()+1));if(n>0&&n<10)return (i+2)*10+n;}}
        return -1;
    }
    static String alarm(String raw) {
        Matcher m=match("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?",raw.trim());if(m==null)return null;
        int h=Integer.parseInt(m.group(1)),min=m.group(2)==null?0:Integer.parseInt(m.group(2));String part=m.group(3);
        if(min>59||h>23||part!=null&&(h<1||h>12)||part==null&&m.group(2)==null)return null;
        if(part!=null)h=h%12+(part.toLowerCase(Locale.ROOT).startsWith("p")?12:0);
        return h+":"+min;
    }
}
