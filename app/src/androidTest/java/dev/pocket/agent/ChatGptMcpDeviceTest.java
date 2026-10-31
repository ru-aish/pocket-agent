package dev.pocket.agent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ChatGptMcpDeviceTest {
    private static final String ORIGIN="https://phone.example.com",REDIRECT="https://chatgpt.com/connector_platform_oauth_redirect",VERIFIER="v".repeat(43);
    private static final class Memory implements ConnectorAuth.Store {
        String value="";public String read(){return value;}public void write(String value){this.value=value;}
    }
    private static final class Fixture {
        long now=1000;Memory memory=new Memory();ConnectorAuth auth;
        Fixture() throws Exception{auth=new ConnectorAuth(memory,()->now);auth.origin(ORIGIN);}
        String client() throws Exception{return auth.register(new JSONObject().put("redirect_uris",new JSONArray().put(REDIRECT))).getString("client_id");}
        Map<String,String> authorization(String client) throws Exception{return new HashMap<>(Map.of("client_id",client,"redirect_uri",REDIRECT,"response_type","code","code_challenge_method","S256","code_challenge",ConnectorAuth.hash(VERIFIER),"scope","pocket.tools","resource",ORIGIN+"/mcp","state","original state"));}
        String code(String client) throws Exception {
            String handle=auth.begin(authorization(client)),pair=auth.pairing().getString("code");
            return query(auth.approve(handle,pair)).get("code");
        }
        Map<String,String> exchange(String client,String code){return new HashMap<>(Map.of("grant_type","authorization_code","client_id",client,"resource",ORIGIN+"/mcp","redirect_uri",REDIRECT,"code",code,"code_verifier",VERIFIER));}
    }
    private interface Attempt{void run() throws Exception;}
    private static void rejected(Attempt attempt) throws Exception{try{attempt.run();fail("Request should have been rejected");}catch(IllegalArgumentException expected){}}
    private static Map<String,String> query(String url){Map<String,String> result=new HashMap<>();for(String pair:URI.create(url).getRawQuery().split("&")){String[] parts=pair.split("=",2);result.put(parts[0],java.net.URLDecoder.decode(parts[1],StandardCharsets.UTF_8));}return result;}
    @Test public void pairingExpiresRotatesAndAuthorizesOnceWithPkce() throws Exception {
        Fixture f=new Fixture();String client=f.client(),expiredHandle=f.auth.begin(f.authorization(client));
        String expired=f.auth.pairing().getString("code");f.now+=ConnectorAuth.PAIRING_MS;
        rejected(()->f.auth.approve(expiredHandle,expired));
        String handle=f.auth.begin(f.authorization(client));String pair=f.auth.pairing().getString("code");assertNotEquals(expired,pair);
        String redirect=f.auth.approve(handle,pair);assertEquals("original state",query(redirect).get("state"));assertEquals(ORIGIN,query(redirect).get("iss"));
        String second=f.auth.begin(f.authorization(client));rejected(()->f.auth.approve(second,pair));
        Map<String,String> input=f.exchange(client,query(redirect).get("code"));input.put("code_verifier","x".repeat(43));rejected(()->f.auth.token(input));
        input.put("code_verifier",VERIFIER);JSONObject token=f.auth.token(input);assertNotNull(f.auth.principal("Bearer "+token.getString("access_token")));rejected(()->f.auth.token(input));
        assertFalse(f.memory.value.contains(token.getString("access_token")));assertFalse(f.memory.value.contains(token.getString("refresh_token")));
    }
    @Test public void refreshPersistsIdentityAndRevocationRemovesAccess() throws Exception {
        Fixture f=new Fixture();String client=f.client();JSONObject first=f.auth.token(f.exchange(client,f.code(client)));
        String principal=f.auth.principal("Bearer "+first.getString("access_token"));
        f.now+=ConnectorAuth.ACCESS_MS;assertNull(f.auth.principal("Bearer "+first.getString("access_token")));
        Map<String,String> refresh=new HashMap<>(Map.of("grant_type","refresh_token","client_id",client,"resource",ORIGIN+"/mcp","refresh_token",first.getString("refresh_token")));
        refresh.put("resource","https://other.example/mcp");rejected(()->f.auth.token(refresh));refresh.put("resource",ORIGIN+"/mcp");
        JSONObject next=f.auth.token(refresh);assertEquals(principal,f.auth.principal("Bearer "+next.getString("access_token")));rejected(()->f.auth.token(refresh));
        ConnectorAuth restored=new ConnectorAuth(f.memory,()->f.now);restored.origin(ORIGIN);assertEquals(principal,restored.principal("Bearer "+next.getString("access_token")));
        restored.revoke();assertNull(restored.principal("Bearer "+next.getString("access_token")));assertEquals(0,restored.connections());
        assertFalse(ConnectorAuth.redirectAllowed("https://chatgpt.com.attacker.example/connector_platform_oauth_redirect"));assertFalse(ConnectorAuth.redirectAllowed(REDIRECT+"?next=https://attacker.example"));
    }
    private static final class Reply {int status;String body,challenge,location;JSONObject json() throws Exception{return new JSONObject(body);}}
    private Reply request(String method,String path,String type,String body,String bearer,String origin) throws Exception {
        byte[] payload=body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8);
        try(java.net.Socket socket=new java.net.Socket("127.0.0.1",8768)){
            socket.setSoTimeout(5000);
            String headers=method+" "+path+" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n";
            if(bearer!=null)headers+="Authorization: Bearer "+bearer+"\r\n";
            if(origin!=null)headers+="Origin: "+origin+"\r\n";
            if(body!=null)headers+="Content-Type: "+type+"\r\nContent-Length: "+payload.length+"\r\n";
            socket.getOutputStream().write((headers+"\r\n"+(body==null?"":body)).getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
            java.io.ByteArrayOutputStream response=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
            try{while((count=socket.getInputStream().read(buffer))!=-1)response.write(buffer,0,count);}
            catch(java.net.SocketException reset){if(response.size()==0)throw reset;}
            String raw=new String(response.toByteArray(),StandardCharsets.UTF_8),parts[]=raw.split("\r\n\r\n",2);assertEquals("Complete HTTP response",2,parts.length);
            Reply reply=new Reply();reply.status=Integer.parseInt(parts[0].split(" ",3)[1]);reply.body=parts[1];
            for(String line:parts[0].split("\r\n")){int colon=line.indexOf(':');if(colon<0)continue;String key=line.substring(0,colon),value=line.substring(colon+1).trim();if(key.equalsIgnoreCase("Location"))reply.location=value;if(key.equalsIgnoreCase("WWW-Authenticate"))reply.challenge=value;}
            return reply;
        }
    }

    private static String form(Map<String,String> values){return values.entrySet().stream().map(e->ConnectorAuth.encode(e.getKey())+"="+ConnectorAuth.encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));}
    @Test public void realHttpDiscoveryOAuthAndAuthenticatedMcp() throws Exception {
        Fixture f=new Fixture();ChatGptMcpServer server=new ChatGptMcpServer(f.auth);server.start(5000,false);
        try {
            Reply denied=request("POST","/mcp","application/json","{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",null,null);assertEquals(401,denied.status);assertTrue(denied.challenge.contains("oauth-protected-resource"));
            JSONObject metadata=request("GET","/.well-known/oauth-authorization-server",null,null,null,null).json();assertEquals(ORIGIN,metadata.getString("issuer"));
            assertEquals(ORIGIN+"/mcp",request("GET","/.well-known/oauth-protected-resource/mcp",null,null,null,null).json().getString("resource"));
            Reply registered=request("POST","/oauth/register","application/json",new JSONObject().put("redirect_uris",new JSONArray().put(REDIRECT)).toString(),null,null);assertEquals(201,registered.status);String client=registered.json().getString("client_id");
            Reply consent=request("GET","/oauth/authorize?"+form(f.authorization(client)),null,null,null,null);assertEquals(200,consent.status);
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("name=\"pending\" value=\"([^\"]+)\"").matcher(consent.body);assertTrue(match.find());
            String pairing=f.auth.pairing().getString("code");assertFalse(consent.body.contains(pairing));
            Reply approved=request("POST","/oauth/authorize","application/x-www-form-urlencoded",form(Map.of("pending",match.group(1),"pairing_code",pairing)),null,ORIGIN);assertEquals(302,approved.status);
            Reply exchanged=request("POST","/oauth/token","application/x-www-form-urlencoded",form(f.exchange(client,query(approved.location).get("code"))),null,null);assertEquals(200,exchanged.status);String access=exchanged.json().getString("access_token");
            String initialize="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}";
            Reply initialized=request("POST","/mcp","application/json",initialize,access,null);assertEquals(200,initialized.status);assertEquals("pocket-agent",initialized.json().getJSONObject("result").getJSONObject("serverInfo").getString("name"));
            assertEquals(403,request("POST","/mcp","application/json",initialize,access,"https://attacker.example").status);
            assertEquals(400,request("POST","/mcp","application/json","broken",access,null).status);
            f.auth.revoke();assertEquals(401,request("POST","/mcp","application/json",initialize,access,null).status);
        }finally{server.stop();}
    }
}
