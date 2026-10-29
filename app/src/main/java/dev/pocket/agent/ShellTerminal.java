package dev.pocket.agent;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/** Runs arbitrary shell programs locally. Output is paged; programs are not tied to Binder calls. */
final class ShellTerminal {
    private final File home;
    private final Map<String,Process> processes=new ConcurrentHashMap<>();
    ShellTerminal(File home) {this.home=home;}
    JSONObject context(String owner) throws Exception {
        File scripts=new File(home,"scripts"),work=new File(home,"workspaces/"+UUID.nameUUIDFromBytes(owner.getBytes(StandardCharsets.UTF_8)));
        Files.createDirectories(scripts.toPath());Files.createDirectories(work.toPath());
        return Json.obj("shell_home",home.getAbsolutePath(),"scripts_directory",scripts.getAbsolutePath(),"working_directory",work.getAbsolutePath(),
            "shell","/system/bin/sh","execution","on_phone","command_policy","unrestricted_shell_permissions",
            "persistence","Scripts and output files survive turns and process restarts; running sessions do not survive a reboot. A missing exit receipt is an unknown outcome, never permission to replay.");
    }
    JSONObject execute(JSONObject request,String owner,Integer displayId) throws Exception {
        JSONObject info=context(owner);String command=request.getString("command");
        if(command.trim().isEmpty())throw new IllegalArgumentException("command must not be empty");
        File cwd=new File(request.optString("cwd",info.getString("working_directory")));
        if(!cwd.isDirectory())throw new IllegalArgumentException("cwd is not an existing directory");
        String id=UUID.randomUUID().toString();File job=new File(home,"sessions/"+id);Files.createDirectories(job.toPath());
        File script=new File(job,"command.sh"),output=new File(job,"output"),exit=new File(job,"exit");
        Files.write(script.toPath(),command.getBytes(StandardCharsets.UTF_8));
        // Pass paths as argv, never interpolate caller-controlled shell source into our wrapper.
        ProcessBuilder builder=new ProcessBuilder("/system/bin/sh","-c",
            "sh \"$1\"; result=$?; printf '%s\\n' \"$result\" > \"$2\"; exit \"$result\"","pocket-shell",script.getAbsolutePath(),exit.getAbsolutePath());
        builder.directory(cwd).redirectErrorStream(true).redirectOutput(output);
        builder.environment().put("POCKET_SHELL_HOME",info.getString("shell_home"));
        builder.environment().put("POCKET_SCRIPTS_DIR",info.getString("scripts_directory"));
        builder.environment().put("POCKET_WORK_DIR",cwd.getAbsolutePath());
        builder.environment().put("POCKET_DISPLAY_ID",displayId==null?"":displayId.toString());
        builder.environment().put("POCKET_DISPLAY_WIDTH",displayId==null?"":"720");
        builder.environment().put("POCKET_DISPLAY_HEIGHT",displayId==null?"":"1280");
        Process process=builder.start();process.getOutputStream().close();processes.put(id,process);
        JSONObject result=poll(id,0);result.put("working_directory",cwd.getAbsolutePath());return result;
    }
    private File job(String id) {
        if(!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Invalid session_id");
        File job=new File(home,"sessions/"+id);if(!job.isDirectory())throw new IllegalArgumentException("Session not found; inspect existing files before repeating a command");return job;
    }
    JSONObject poll(String id,long offset) throws Exception {
        File job=job(id),output=new File(job,"output");Process process=processes.get(id);
        String status=process!=null&&process.isAlive()?"running":"unknown";Integer code=null;
        File exit=new File(job,"exit");
        if(process!=null&&!process.isAlive()){status="exited";code=process.exitValue();processes.remove(id,process);}
        else if(exit.isFile()){try {code=Integer.parseInt(new String(Files.readAllBytes(exit.toPath()),StandardCharsets.UTF_8).trim());status="exited";}catch(NumberFormatException incomplete) {}}
        long length=output.length();if(offset<0||offset>length)throw new IllegalArgumentException("offset is outside the current output file");
        byte[] bytes=new byte[(int)Math.min(4096,length-offset)];
        if(bytes.length>0)try(RandomAccessFile file=new RandomAccessFile(output,"r")){file.seek(offset);file.readFully(bytes);}
        // Offsets are bytes. Avoid cutting a UTF-8 sequence between pages.
        int count=bytes.length;
        if((offset+count<length||status.equals("running"))&&count>0){int start=count-1;while(start>0&&(bytes[start]&0xc0)==0x80)start--;int lead=bytes[start]&255;int expected=lead<128?1:lead<224?2:lead<240?3:4;if(start+expected>count)count=start;}
        return Json.obj("session_id",id,"status",status,"exit_code",code,"output",new String(bytes,0,count,StandardCharsets.UTF_8),
            "offset",offset,"next_offset",offset+count,"output_bytes",length,"has_more",offset+count<length,"output_path",output.getAbsolutePath(),
            "output_streams","stdout and stderr combined","retry_safe",false);
    }
    JSONObject stop(String id,long offset) throws Exception {
        job(id);Process process=processes.get(id);
        if(process!=null&&process.isAlive())process.destroy();
        JSONObject result=poll(id,offset);result.put("stop_requested",process!=null);result.put("stop_note","Only the session shell was signalled. Independently spawned children may remain; inspect them before repeating work.");return result;
    }
}
