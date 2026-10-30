package dev.pocket.agent;

import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;

/** Host/Android regression test for command cancellation and durable completion receipts. */
public final class ShellTerminalSessionTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static JSONObject completed(ShellTerminal terminal,String id) throws Exception {
        long deadline=System.currentTimeMillis()+5000;
        do {
            JSONObject result=terminal.poll(id,0);
            if("exited".equals(result.getString("status")))return result;
            Thread.sleep(10);
        } while(System.currentTimeMillis()<deadline);
        throw new AssertionError("Session did not complete: "+id);
    }
    public static void main(String[] args) throws Exception {
        File home=new File(args[0]);String shell=args.length>1?args[1]:"/system/bin/sh";
        ShellTerminal terminal=new ShellTerminal(home,shell);
        File cwd=new File(terminal.context("test").getString("working_directory"));
        File proceed=new File(cwd,"continue"),marker=new File(cwd,"continued");
        check(!proceed.exists()&&!marker.exists(),"Use a fresh test directory");
        String id=terminal.execute(new JSONObject().put("command",
            "printf ready; while [ ! -e continue ]; do :; done; printf continued > continued"),"test",null).getString("session_id");
        try {
            long deadline=System.currentTimeMillis()+5000;
            while(!terminal.poll(id,0).getString("output").equals("ready")) {
                check(System.currentTimeMillis()<deadline,"Command did not start");Thread.sleep(10);
            }
            check(terminal.activeSessions()==1,"A running shell must keep its Shizuku client bound");
            terminal.stop(id,0);
            check(completed(terminal,id).getInt("exit_code")!=0,"Stopped command must have a nonzero exit code");
            check(terminal.activeSessions()==0,"Completed shells must not keep the client bound");
            Files.createFile(proceed.toPath());Thread.sleep(100);
            check(!marker.exists(),"Stopped script continued executing after the session exited");
            check(completed(new ShellTerminal(home,shell),id).getInt("exit_code")!=0,"Stopped session lost its durable receipt");
        } finally {if(!proceed.exists())Files.createFile(proceed.toPath());}

        // No original-executor polling: receipt persistence cannot depend on the caller returning.
        String replaced=terminal.execute(new JSONObject().put("command","exec "+shell+" -c 'printf replaced; exit 7'"),"test",null).getString("session_id");
        JSONObject restored=completed(new ShellTerminal(home,shell),replaced);
        check(restored.getInt("exit_code")==7&&restored.getString("output").equals("replaced"),"exec lost its output or exit receipt: "+restored);
        System.out.println("SHELL_SESSION_TEST=passed");
    }
}
