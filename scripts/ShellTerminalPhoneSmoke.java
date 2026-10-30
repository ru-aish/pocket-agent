package dev.pocket.agent;

import android.content.Context;
import android.os.Looper;
import android.util.Base64;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Standalone app_process smoke: runs the production shell/frame code as shell UID, without an APK. */
public final class ShellTerminalPhoneSmoke {
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static JSONObject completed(ShellTerminal terminal,JSONObject started) throws Exception {
        String id=started.getString("session_id");long until=System.currentTimeMillis()+10000;
        while(System.currentTimeMillis()<until){JSONObject result=terminal.poll(id,0);if(result.getString("status").equals("exited"))return result;Thread.sleep(25);}
        throw new AssertionError("Session did not exit: "+id);
    }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t,error)->{error.printStackTrace();System.exit(1);});
        File root=new File(args[0]);Files.createDirectories(root.toPath());
        if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
        Class<?> activityThread=Class.forName("android.app.ActivityThread");Object thread=activityThread.getMethod("systemMain").invoke(null);
        Context context=(Context)activityThread.getMethod("getSystemContext").invoke(thread);
        JSONObject results=new JSONObject();ShellDisplayService service=new ShellDisplayService(context);int display=-1;
        try {
            check(android.os.Process.myUid()==2000,"Smoke must execute as Android shell UID");
            ShellTerminal terminal=new ShellTerminal(new File(root,"workspace"));
            JSONObject first=terminal.context("main"),second=terminal.context("worker");
            check(first.getString("scripts_directory").equals(second.getString("scripts_directory")),"Scripts must be shared");
            check(!first.getString("working_directory").equals(second.getString("working_directory")),"Each agent needs its own scratch directory");
            JSONObject create=completed(terminal,terminal.execute(new JSONObject().put("command",
                "cat > \"$POCKET_SCRIPTS_DIR/probe.sh\" <<'SCRIPT'\nprintf 'reused:%s\\n' \"$POCKET_DISPLAY_ID\"\nSCRIPT\nsh \"$POCKET_SCRIPTS_DIR/probe.sh\""),"main",81));
            check(create.getString("output").contains("reused:81"),create.toString());
            JSONObject reuse=completed(terminal,terminal.execute(new JSONObject().put("command","sh \"$POCKET_SCRIPTS_DIR/probe.sh\""),"worker",82));
            check(reuse.getString("output").contains("reused:82"),reuse.toString());results.put("saved_script_reused_by_other_agent",true);
            long before=System.currentTimeMillis();JSONObject slow=terminal.execute(new JSONObject().put("command","sleep 1; printf background-finished"),"main",null);
            results.put("async_start_ms",System.currentTimeMillis()-before);check(System.currentTimeMillis()-before<700,"Command blocked the bridge");
            JSONObject fast=completed(terminal,terminal.execute(new JSONObject().put("command","printf fast; printf err >&2; exit 7"),"worker",null));
            check(fast.getInt("exit_code")==7&&fast.getString("output").equals("fasterr"),fast.toString());
            check(completed(terminal,slow).getString("output").equals("background-finished"),"Background output lost");results.put("concurrent_sessions_and_exit_codes",true);
            JSONObject restored=new ShellTerminal(new File(root,"workspace")).poll(fast.getString("session_id"),0);
            check(restored.getInt("exit_code")==7,"Exit receipt did not survive executor recreation");results.put("persisted_output_and_exit_receipt",true);
            JSONObject unicode=completed(terminal,terminal.execute(new JSONObject().put("command","printf '雪%.0s' $(seq 1 4000)"),"main",null));
            long offset=0;StringBuilder all=new StringBuilder();
            do {JSONObject page=terminal.poll(unicode.getString("session_id"),offset);all.append(page.getString("output"));long next=page.getLong("next_offset");check(next>offset||!page.getBoolean("has_more"),"Paging made no progress");offset=next;if(!page.getBoolean("has_more"))break;}while(true);
            check(all.toString().equals("雪".repeat(4000)),"UTF-8 paging corrupted output");results.put("utf8_paging",true);
            display=service.createDisplay(720,1280,320);results.put("display_id",display);
            if(args.length>1)check(service.launchActivity(display,args[1]),"Cannot launch an independent task");
            JSONObject capture=null;long deadline=System.currentTimeMillis()+10000;
            do {capture=new JSONObject(service.terminal("{\"action\":\"capture\"}","main",display));if(!capture.optBoolean("isError"))break;Thread.sleep(100);}while(System.currentTimeMillis()<deadline);
            check(!capture.optBoolean("isError"),capture.toString());
            check(capture.getInt("coordinate_width")==720&&capture.getInt("coordinate_height")==1280,"Wrong display frame dimensions");
            Files.write(new File(root,"capture.jpg").toPath(),Base64.decode(capture.getJSONArray("images").getJSONObject(0).getString("data"),Base64.DEFAULT));
            results.put("accessibility_independent_frame_capture",true);
            // Exercise the production AIDL entry point with an independent shell command.
            JSONObject started=new JSONObject(service.terminal("{\"action\":\"exec\",\"command\":\"id; printf \\\"display=%s\\\\n\\\" \\\"$POCKET_DISPLAY_ID\\\"\"}","smoke",display));
            check(started.has("session_id"),started.toString());String session=started.getString("session_id");
            JSONObject reply=null;deadline=System.currentTimeMillis()+5000;
            do {reply=new JSONObject(service.terminal(new JSONObject().put("action","poll").put("session_id",session).toString(),"other-worker",display));if("exited".equals(reply.optString("status")))break;Thread.sleep(25);}while(System.currentTimeMillis()<deadline);
            check(reply.getString("output").contains("uid=2000(shell)"),reply.toString());check(reply.getString("output").contains("display="+display),reply.toString());
            check("exited".equals(reply.optString("status"))&&reply.getInt("exit_code")==0,reply.toString());
            results.put("production_service_shell_uid",2000);results.put("cross_agent_session_access",true);results.put("ok",true);
        } finally {
            if(display>0){service.removeDisplay(display);results.put("display_released",true);}
            Files.write(new File(root,"result.json").toPath(),results.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("SHELL_PHONE_SMOKE="+results);System.exit(0);
    }
}
