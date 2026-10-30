package dev.pocket.agent;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only explicit task records establish membership; diagnostic references never do. */
final class DisplayTaskSnapshot {
    private static final Pattern STACK=Pattern.compile("^(?:RootTask|Stack)\\s+id=(\\d+)\\b.*?\\bdisplayId=(\\d+)\\b");
    private static final Pattern HEADER=Pattern.compile("^Display #(\\d+) \\(activities from top to bottom\\):\\s*$");
    private static final Pattern ROOT=Pattern.compile("^  (?:\\* Task\\{[^#\\r\\n]*#|RootTask #)(\\d+)\\b");
    private static final Pattern TASK=Pattern.compile("^\\s+\\* Task\\{[^#\\r\\n]*#(\\d+)\\b");
    private static final Pattern COMPONENT=Pattern.compile("^\\s+taskId=(\\d+): ([A-Za-z0-9_.]+)/");
    private static final Pattern ACTIVITY=Pattern.compile("^\\s+\\* Hist #\\d+: ActivityRecord\\{[^\\r\\n]*?\\bu\\d+\\s+([A-Za-z0-9_.]+)/");
    final Map<Integer,Integer> displays=new LinkedHashMap<>();
    final Map<Integer,Integer> taskDisplays=new LinkedHashMap<>();
    final Set<Integer> visibleRoots=new LinkedHashSet<>();
    private final Map<Integer,Set<String>> packages=new LinkedHashMap<>();

    static DisplayTaskSnapshot stacks(String output) {
        DisplayTaskSnapshot result=new DisplayTaskSnapshot();
        Integer root=null;
        for(String line:output.split("\\R")) {
            Matcher record=STACK.matcher(line);
            if(record.find()) {
                root=Integer.parseInt(record.group(1));
                result.displays.put(root,Integer.parseInt(record.group(2)));
                result.taskDisplays.put(root,Integer.parseInt(record.group(2)));
            } else if(!line.isEmpty()&&!Character.isWhitespace(line.charAt(0)))root=null;
            Matcher component=COMPONENT.matcher(line);
            if(root!=null&&component.find()) {
                result.taskDisplays.put(Integer.parseInt(component.group(1)),result.displays.get(root));
                result.packages.computeIfAbsent(root,key->new LinkedHashSet<>()).add(component.group(2));
                if(line.contains(" visible=true"))result.visibleRoots.add(root);
            }
        }
        return result;
    }

    static DisplayTaskSnapshot activities(String output) {
        DisplayTaskSnapshot result=new DisplayTaskSnapshot();
        int display=-1;Integer root=null;
        for(String line:output.split("\\R")) {
            Matcher header=HEADER.matcher(line);
            if(header.matches()) {display=Integer.parseInt(header.group(1));root=null;continue;}
            // ActivityTaskSupervisor state, focused-task references, history, etc.
            // are separate dump sections, even if they mention Task{...} again.
            if(!line.isEmpty()&&!Character.isWhitespace(line.charAt(0)))display=-1;
            Matcher task=ROOT.matcher(line);
            if(display>=0&&task.find()) {
                int id=Integer.parseInt(task.group(1));root=id;
                result.displays.put(id,display);result.taskDisplays.put(id,display);
            }
            Matcher component=ACTIVITY.matcher(line);
            if(display>=0&&root!=null&&component.find())result.packages.computeIfAbsent(root,key->new LinkedHashSet<>()).add(component.group(1));
            if(display>=0&&root!=null&&(line.contains("visible=true")||line.contains("mVisibleRequested=true")))result.visibleRoots.add(root);
            Matcher anyTask=TASK.matcher(line);
            if(display>=0&&anyTask.find())result.taskDisplays.put(Integer.parseInt(anyTask.group(1)),display);
        }
        return result;
    }

    Set<Integer> onDisplay(int display) {
        Set<Integer> result=new LinkedHashSet<>();
        displays.forEach((task,id)->{if(id==display)result.add(task);});
        return result;
    }

    boolean packageOutside(String packageName,int display) {
        return packages.entrySet().stream().anyMatch(entry->
            displays.get(entry.getKey())!=display&&entry.getValue().contains(packageName));
    }

    boolean hasPackageRecords() {return !packages.isEmpty();}

    Set<Integer> backgroundStoreTasks(int targetDisplay) {
        Set<Integer> result=new LinkedHashSet<>();
        for(Map.Entry<Integer,Set<String>> entry:packages.entrySet()) {
            if(!entry.getValue().contains("com.android.vending"))continue;
            int root=entry.getKey(),display=displays.get(root);
            if(display==targetDisplay)continue;
            if(display!=0||visibleRoots.contains(root)||entry.getValue().size()!=1)return Set.of();
            result.add(root);
        }
        return result;
    }

    boolean packageOn(String packageName,int display) {
        return packages.entrySet().stream().anyMatch(entry->
            displays.get(entry.getKey())==display&&entry.getValue().contains(packageName));
    }

    void protectOutside(int ownedDisplay,Map<Integer,Integer> protectedTasks) {
        // A task can migrate A -> display 0 -> B -> our display. Its most
        // recently observed outside location, not its first one, is authoritative.
        taskDisplays.forEach((task,display)->{
            if(display!=ownedDisplay)protectedTasks.put(task,display);
        });
    }

    Set<Integer> removable(int display,Map<Integer,Integer> protectedTasks) {
        Set<Integer> result=onDisplay(display);
        result.removeAll(protectedTasks.keySet());
        return result;
    }
}
