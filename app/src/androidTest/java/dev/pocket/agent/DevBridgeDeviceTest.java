package dev.pocket.agent;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import org.json.JSONArray;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DevBridgeDeviceTest {
    private Context context() {return InstrumentationRegistry.getInstrumentation().getTargetContext();}

    private Object server() throws Exception {return server("");}
    private Object server(String token) throws Exception {
        Class<?> type=Class.forName("dev.pocket.agent.DevMcpServer");
        Constructor<?> constructor=type.getDeclaredConstructor(Context.class,String.class);
        constructor.setAccessible(true);
        Object result=constructor.newInstance(context(),token);
        type.getMethod("start",int.class,boolean.class).invoke(result,5000,false);
        return result;
    }
    private JSONObject history(String method,Class<?> arg1, Object first, JSONObject request) throws Exception {
        Class<?> type=Class.forName("dev.pocket.agent.DevHistory");
        Method call=type.getDeclaredMethod(method,arg1,JSONObject.class);
        call.setAccessible(true);
        try {return (JSONObject)call.invoke(null,first,request);}
        catch(InvocationTargetException error) {
            if(error.getCause() instanceof Exception)throw (Exception)error.getCause();
            throw error;
        }
    }
    private String request(String token,String method,String origin) throws Exception {
        return request(token,method,origin,false);
    }
    private String request(String token,String method,String origin,boolean chunked) throws Exception {
        JSONObject body=new JSONObject().put("jsonrpc","2.0").put("id",1).put("method",method);
        if(method.equals("initialize"))body.put("params",new JSONObject().put("protocolVersion","2025-11-25"));
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        try(Socket socket=new Socket("127.0.0.1",8767)) {
            socket.setSoTimeout(9000);
            String auth=token==null||token.isEmpty()?"":"Authorization: Bearer "+token+"\r\n";
            String headers="POST /mcp HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nAccept: application/json\r\n"+auth+
                (chunked?"Transfer-Encoding: chunked\r\n":"Content-Length: "+bytes.length+"\r\n")+"Connection: close\r\n"+(origin==null?"":"Origin: https://untrusted.example\r\n")+"\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
            if(chunked)socket.getOutputStream().write((Integer.toHexString(bytes.length)+"\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes);
            if(chunked)socket.getOutputStream().write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            java.io.ByteArrayOutputStream response=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[8192];int n;
            try {
                while((n=socket.getInputStream().read(buffer))!=-1)response.write(buffer,0,n);
            } catch (java.net.SocketException resetAfterEarlyRejection) {
                // The bridge rejects untrusted Origin before reading the POST body.
                // TCP may reset on close with unread input after sending a valid 403.
                // Preserve any HTTP response for the test's status assertions; a
                // reset before any response is still a genuine connection failure.
                if (response.size()==0) throw resetAfterEarlyRejection;
            }
            return response.toString("UTF-8");
        }
    }
    private void startAgent() throws Exception {
        Context c=context();
        c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        c.startForegroundService(new Intent(c,AgentService.class).setAction(AgentService.ACTION_START));
        long end=System.currentTimeMillis()+30000;
        while(System.currentTimeMillis()<end) {
            RuntimeHost host=AgentService.peekHost();
            if(host!=null)try {if(host.developmentToolList().get(2,TimeUnit.SECONDS).length()>0)return;}
            catch(Exception ignored){}
            Thread.sleep(150);
        }
        throw new AssertionError("Development tools did not start");
    }
    @Test public void localMcpNeedsNoBearerListsSameModelToolsAndBlocksUntrustedOrigin() throws Exception {
        Assume.assumeTrue(BuildConfig.DEVELOPMENT_TOOLS);
        startAgent();
        Object bridge=server();
        try {
            assertTrue(request(null,"ping","https://untrusted.example").startsWith("HTTP/1.1 403"));
            assertTrue(request(null,"initialize",null).contains("pocket-agent-development"));
            assertTrue(request(null,"ping",null,true).contains("\"result\":{}"));
            String tools=request(null,"tools/list",null);
            assertTrue(tools,tools.contains("phone_capabilities"));
            assertTrue(tools,tools.contains("memory_search"));
            assertTrue(tools,tools.contains("subagent"));
            assertTrue(tools,tools.contains("debug_history"));
        } finally {bridge.getClass().getMethod("stop").invoke(bridge);}
    }
    @Test public void bearerModeStillProtectsWirelessBridge() throws Exception {
        Assume.assumeTrue(BuildConfig.DEVELOPMENT_TOOLS);
        Object bridge=server("instrumentation-test-token");
        try {
            assertTrue(request(null,"ping",null).startsWith("HTTP/1.1 401"));
            assertTrue(request("wrong","ping",null).startsWith("HTTP/1.1 401"));
            assertTrue(request("instrumentation-test-token","ping",null).contains("\"result\":{}"));
            assertTrue(request("instrumentation-test-token","ping","https://untrusted.example").startsWith("HTTP/1.1 403"));
        } finally {bridge.getClass().getMethod("stop").invoke(bridge);}
    }
    @Test public void chunkFramingBudgetRejectsExcessiveTinyChunks() throws Exception {
        Assume.assumeTrue(BuildConfig.DEVELOPMENT_TOOLS);
        Object bridge=server();
        try(Socket socket=new Socket("127.0.0.1",8767)) {
            socket.setSoTimeout(9000);
            java.io.ByteArrayOutputStream wire=new java.io.ByteArrayOutputStream();
            wire.write(("POST /mcp HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"+
                "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            // The decoded body is only 4 KiB, but thousands of chunks waste CPU
            // and framing bytes unless a separate chunk-count limit is enforced.
            for(int i=0;i<4200;i++)wire.write("1\r\n \r\n".getBytes(StandardCharsets.US_ASCII));
            wire.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(wire.toByteArray());
            socket.getOutputStream().flush();
            String statusLine=new java.io.BufferedReader(new java.io.InputStreamReader(
                socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();
            assertNotNull("No HTTP response",statusLine);
            assertTrue("Expected bounded chunk framing rejection: "+statusLine,
                statusLine.startsWith("HTTP/1.1 400"));
        } finally {bridge.getClass().getMethod("stop").invoke(bridge);}
    }
    @Test public void localLogSearchAndPaginationRejectInvalidBounds() throws Exception {
        Assume.assumeTrue(BuildConfig.DEVELOPMENT_TOOLS);
        try(StateStore store=new StateStore(context())) {
            String nonce="dev-bridge-test-"+System.nanoTime();
            store.record(new JSONObject().put("type","log").put("message",nonce));
            JSONObject found=store.queryEvents(nonce,0,10).getJSONObject(0);
            assertEquals(nonce,found.getString("message"));
            JSONObject history=new JSONObject().put("query","absent-synthetic-query").put("limit",5);
            assertEquals(0,history("query",StateStore.class,store,history).getJSONArray("messages").length());
            try {history("query",StateStore.class,store,new JSONObject().put("limit",101));fail("limit should reject");}
            catch(IllegalArgumentException expected) {}
            try {history("events",StateStore.class,store,new JSONObject().put("offset",-1));fail("offset should reject");}
            catch(IllegalArgumentException expected) {}
            JSONObject checkpoint=new JSONObject()
                .put("mainMessages",new JSONArray().put(new JSONObject().put("timestamp",100).put("role","user").put("content","main")))
                .put("workerMessages",new JSONObject().put("worker-1",new JSONArray().put(new JSONObject().put("timestamp",300).put("role","assistant").put("content","worker"))));
            JSONObject ordered=history("queryCheckpoint",JSONObject.class,checkpoint,new JSONObject().put("limit",1));
            assertEquals("worker-1",ordered.getJSONArray("messages").getJSONObject(0).getString("conversationId"));
            assertEquals(1,ordered.getInt("nextOffset"));
            JSONObject page=history("queryCheckpoint",JSONObject.class,checkpoint,new JSONObject().put("limit",1).put("offset",1));
            assertEquals("main",page.getJSONArray("messages").getJSONObject(0).getString("conversationId"));
        }
    }
}
