package dev.pocket.agent;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact bounded facts avoid loading a neural model for a clock or calculator query. */
final class QuickCommandFacts {
    private static final String DECIMAL="(-?\\d{1,9}(?:\\.\\d{1,6})?)";
    private static QuickCommandParser.Action action(String intent,String source,String... fields) {
        Map<String,String> slots=new LinkedHashMap<>();for(int i=0;i<fields.length;i+=2)slots.put(fields[i],fields[i+1]);return new QuickCommandParser.Action(intent,source,slots);
    }
    static QuickCommandParser.Action parse(String s) {
        Matcher m=Pattern.compile("(?i)(?:(?:what time is it)|(?:what(?:'s| is) (?:the )?(?:current )?time)|(?:tell me (?:the )?(?:current )?time))(?: in (.+?))?(?: (?:right now|now|currently))?").matcher(s);
        if(m.matches())return action("time",s,"zone",m.group(1)==null?"":m.group(1));
        if(s.matches("(?i)(?:what(?:'s| is) (?:the )?(?:current |today's )?date|what (?:day|date) is (?:it|today)|tell me (?:the |today's )?date)"))return action("date",s);
        if(s.matches("(?i)(?:what(?:'s| is) (?:my |the )?(?:remaining )?battery(?: level| percentage)?|how much battery(?: is left| do i have)?|(?:check|read|show me) (?:my |the )?battery(?: level| percentage)?)"))return action("device",s,"field","battery");
        if(s.matches("(?i)(?:am i (?:online|connected to (?:the )?internet|on wi-?fi)|(?:check|show me) (?:my |the )?(?:internet|network|connection)(?: status)?)"))return action("device",s,"field","network");
        if(s.matches("(?i)(?:what(?:'s| is) (?:my |the )?(?:media |music )?volume(?: level)?|(?:check|read) (?:my |the )?volume)"))return action("device",s,"field","volume");
        if(s.matches("(?i)(?:how much (?:free )?storage(?: is (?:left|available)| do i have)?|(?:check|show me) (?:my |the )?(?:free )?storage)"))return action("device",s,"field","storage");
        if(s.matches("(?i)(?:what(?:'s| is) (?:my |the )?(?:screen )?brightness|(?:check|read) (?:my |the )?brightness)"))return action("device",s,"field","brightness");
        m=Pattern.compile("(?i)(?:what is|what's|calculate) "+DECIMAL+"\\s*(plus|minus|times|multiplied by|divided by|[+*/-])\\s*"+DECIMAL).matcher(s);
        if(m.matches())return action("math",s,"left",m.group(1),"operator",m.group(2),"right",m.group(3));
        m=Pattern.compile("(?i)(?:convert|what is|what's) "+DECIMAL+" (kilometers?|kilometres?|km|miles?|meters?|metres?|feet|foot|kilograms?|kg|pounds?|lbs?|celsius|fahrenheit) (?:to|in) (kilometers?|kilometres?|km|miles?|meters?|metres?|feet|foot|kilograms?|kg|pounds?|lbs?|celsius|fahrenheit)").matcher(s);
        if(m.matches())return action("convert",s,"value",m.group(1),"from",m.group(2),"to",m.group(3));
        return null;
    }
    static boolean exact(QuickCommandParser.Action action){return java.util.Set.of("time","date","device","math","convert").contains(action.intent());}
    static String answer(QuickCommandParser.Action action,long now) {
        if(action.intent().equals("date"))return DateTimeFormatter.ofPattern("EEEE, d MMMM uuuu",Locale.ENGLISH).format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))+".";
        if(action.intent().equals("time")) {
            String query=action.get("zone");ZoneId zone=ZoneId.systemDefault();
            if(!query.isEmpty()) {
                String normalized=QuickCommandParser.normalize(query);java.util.Set<String> zones=new java.util.TreeSet<>();
                for(String id:ZoneId.getAvailableZoneIds())if(QuickCommandParser.normalize(id.substring(id.lastIndexOf('/')+1)).equals(normalized)||id.equalsIgnoreCase(query))zones.add(id);
                if(normalized.equals("utc"))zone=ZoneId.of("UTC");
                else if(zones.size()==1)zone=ZoneId.of(zones.iterator().next());else return null;
            }
            return "It's "+DateTimeFormatter.ofPattern("h:mm a",Locale.ENGLISH).format(Instant.ofEpochMilli(now).atZone(zone))+(query.isEmpty()?".":" in "+query+".");
        }
        if(action.intent().equals("math")) {
            BigDecimal left=new BigDecimal(action.get("left")),right=new BigDecimal(action.get("right"));BigDecimal result;
            result=switch(action.get("operator").toLowerCase(Locale.ROOT)){case "+","plus"->left.add(right);case "-","minus"->left.subtract(right);case "*","times","multiplied by"->left.multiply(right);default->right.signum()==0?null:left.divide(right,MathContext.DECIMAL64);};
            return result==null?"Division by zero is undefined.":result.stripTrailingZeros().toPlainString()+".";
        }
        if(action.intent().equals("convert")) {
            String from=unit(action.get("from")),to=unit(action.get("to"));double value=Double.parseDouble(action.get("value")),result;
            Map<String,Double> lengths=Map.of("km",1000d,"mile",1609.344,"m",1d,"foot",0.3048),weights=Map.of("kg",1d,"pound",0.45359237);
            if(lengths.containsKey(from)&&lengths.containsKey(to))result=value*lengths.get(from)/lengths.get(to);
            else if(weights.containsKey(from)&&weights.containsKey(to))result=value*weights.get(from)/weights.get(to);
            else if(from.equals("celsius")&&to.equals("fahrenheit"))result=value*9/5+32;
            else if(from.equals("fahrenheit")&&to.equals("celsius"))result=(value-32)*5/9;
            else return null;
            return BigDecimal.valueOf(result).setScale(4,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+" "+action.get("to")+".";
        }
        return null;
    }
    private static String unit(String s){String n=s.toLowerCase(Locale.ROOT);if(n.matches("kilo(?:meters?|metres?)|km"))return "km";if(n.matches("meters?|metres?"))return "m";if(n.matches("miles?"))return "mile";if(n.matches("feet|foot"))return "foot";if(n.matches("kilograms?|kg"))return "kg";if(n.matches("pounds?|lbs?"))return "pound";return n;}
}
