package dev.pocket.agent;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

final class ChatGptMcpServer extends NanoHTTPD {
    private final ConnectorAuth auth;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(16,16,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(12));
    private volatile boolean closed;
    ChatGptMcpServer(ConnectorAuth auth){super("127.0.0.1",8768);this.auth=auth;}
    static JSONObject object(Object... values){JSONObject result=new JSONObject();try{for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]==null?JSONObject.NULL:values[i+1]);}catch(Exception error){throw new IllegalStateException(error);}return result;}
    private Response response(Response.IStatus status,String mime,String body){
        Response result=newFixedLengthResponse(status,mime,body);
        result.addHeader("Cache-Control","no-store");result.addHeader("X-Content-Type-Options","nosniff");result.addHeader("Referrer-Policy","no-referrer");
        result.addHeader("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");return result;
    }
    private Response json(Response.IStatus status,JSONObject body){return response(status,"application/json; charset=utf-8",body.toString());}
    private Response rpcError(Response.IStatus status,int code,String message,Object id){return json(status,object("jsonrpc","2.0","id",id,"error",object("code",code,"message",message)));}
    private Response unauthorized(){Response result=rpcError(Response.Status.UNAUTHORIZED,-32001,"Connect PocketAgent in ChatGPT using the code shown on your phone",null);result.addHeader("WWW-Authenticate","Bearer resource_metadata=\""+auth.origin()+"/.well-known/oauth-protected-resource\", scope=\"pocket.tools\"");return result;}
    private static Map<String,String> form(String encoded) throws java.io.UnsupportedEncodingException {
        if(encoded.length()>16000)throw new IllegalArgumentException("Request too large");
        Map<String,String> result=new HashMap<>();
        for(String pair:encoded.split("&")){String[] parts=pair.split("=",2);String key=URLDecoder.decode(parts[0],"UTF-8"),value=parts.length==2?URLDecoder.decode(parts[1],"UTF-8"):"";if(result.put(key,value)!=null)throw new IllegalArgumentException("Duplicate parameter");}
        return result;
    }
    private static String body(IHTTPSession request) throws Exception {return new String(McpHttpBody.readRequestBody(request),StandardCharsets.UTF_8);}
    private Response authorizationPage(String handle){
        return response(Response.Status.OK,"text/html; charset=utf-8","<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Connect PocketAgent</title>"
            +"<style>body{font:17px system-ui;margin:0;background:#f6f7fb;color:#202436}main{max-width:28rem;margin:12vh auto;padding:24px}h1{font-size:28px}p{line-height:1.6}label{display:block;margin:28px 0 8px}input,button{box-sizing:border-box;width:100%;padding:16px;font:inherit;border:1px solid #aab1c5;border-radius:12px}button{margin-top:20px;background:#344fc4;color:white;border:0}small{display:block;margin-top:20px;line-height:1.5}</style>"
            +"<main><h1>Connect this phone to ChatGPT</h1><p>This connection can use PocketAgent’s phone tools and workers. Approve only a connection you started.</p>"
            +"<form method=\"post\" action=\"/oauth/authorize\"><input type=\"hidden\" name=\"pending\" value=\""+handle+"\"><label for=\"code\">Code from PocketAgent Settings → ChatGPT MCP</label><input id=\"code\" name=\"pairing_code\" required maxlength=\"16\" autocomplete=\"one-time-code\" spellcheck=\"false\"><button>Allow ChatGPT to connect</button></form><small>The code expires after five minutes and works once. You can revoke connections from PocketAgent settings.</small></main></html>");
    }
    private Response oauth(IHTTPSession request) throws Exception {
        String path=request.getUri(),origin=auth.origin();
        if(origin.isEmpty())return json(Response.Status.SERVICE_UNAVAILABLE,object("error","Connector is starting"));
        if(request.getMethod()==Method.GET&&(path.equals("/.well-known/oauth-protected-resource")||path.equals("/.well-known/oauth-protected-resource/mcp")))
            return json(Response.Status.OK,object("resource",origin+"/mcp","authorization_servers",new JSONArray().put(origin),"scopes_supported",new JSONArray().put("pocket.tools"),"bearer_methods_supported",new JSONArray().put("header")));
        if(request.getMethod()==Method.GET&&path.equals("/.well-known/oauth-authorization-server"))
            return json(Response.Status.OK,object("issuer",origin,"authorization_endpoint",origin+"/oauth/authorize","token_endpoint",origin+"/oauth/token","registration_endpoint",origin+"/oauth/register","response_types_supported",new JSONArray().put("code"),"grant_types_supported",new JSONArray().put("authorization_code").put("refresh_token"),"token_endpoint_auth_methods_supported",new JSONArray().put("none"),"code_challenge_methods_supported",new JSONArray().put("S256"),"scopes_supported",new JSONArray().put("pocket.tools"),"authorization_response_iss_parameter_supported",true));
        if(path.equals("/oauth/authorize")&&request.getMethod()==Method.GET)return authorizationPage(auth.begin(form(request.getQueryParameterString()==null?"":request.getQueryParameterString())));
        if(request.getMethod()!=Method.POST)return json(Response.Status.METHOD_NOT_ALLOWED,object("error","Unsupported method"));
        String browserOrigin=request.getHeaders().get("origin");
        if(browserOrigin!=null&&!browserOrigin.equals(origin))return json(Response.Status.FORBIDDEN,object("error","Untrusted browser origin"));
        String type=request.getHeaders().getOrDefault("content-type","").split(";",2)[0].trim();
        if(path.equals("/oauth/register")&&type.equals("application/json"))return json(Response.Status.CREATED,auth.register(new JSONObject(body(request))));
        if(!type.equals("application/x-www-form-urlencoded"))return json(Response.Status.UNSUPPORTED_MEDIA_TYPE,object("error","Form encoding required"));
        Map<String,String> values=form(body(request));
        if(path.equals("/oauth/authorize")){
            String destination=auth.approve(values.getOrDefault("pending",""),values.getOrDefault("pairing_code",""));
            // NanoHTTPD REDIRECT is a permanent 301; the OAuth callback needs 302.
            Response result=response(Response.Status.FOUND,"text/plain","Connection authorized. Returning to ChatGPT.");result.addHeader("Location",destination);return result;
        }
        if(path.equals("/oauth/token")){
            try{return json(Response.Status.OK,auth.token(values));}
            catch(IllegalArgumentException rejected){return json(Response.Status.BAD_REQUEST,object("error",rejected.getMessage().equals("unsupported_grant_type")?"unsupported_grant_type":"invalid_grant"));}
        }
        return json(Response.Status.NOT_FOUND,object("error","Unknown endpoint"));
    }
    @Override public Response serve(IHTTPSession request){
        try {
            if(!request.getUri().equals("/mcp"))return oauth(request);
            if(request.getHeaders().containsKey("origin"))return rpcError(Response.Status.FORBIDDEN,-32002,"Browser tool access is disabled",null);
            String principal=auth.principal(request.getHeaders().get("authorization"));if(principal==null)return unauthorized();
            if(closed)return rpcError(Response.Status.SERVICE_UNAVAILABLE,-32000,"Connector stopped",null);
            if(request.getMethod()!=Method.POST)return rpcError(Response.Status.METHOD_NOT_ALLOWED,-32600,"Use POST",null);
            if(!request.getHeaders().getOrDefault("content-type","").split(";",2)[0].trim().equals("application/json"))return rpcError(Response.Status.UNSUPPORTED_MEDIA_TYPE,-32600,"JSON required",null);
            JSONObject input=new JSONObject(body(request));Object id=input.opt("id");
            if(!input.optString("jsonrpc").equals("2.0")||(id!=null&&!(id instanceof String||id instanceof Integer||id instanceof Long)))return rpcError(Response.Status.BAD_REQUEST,-32600,"Invalid JSON-RPC",null);
            String method=input.optString("method");if(!input.has("id")&&method.startsWith("notifications/"))return response(Response.Status.ACCEPTED,"application/json","");
            if(!input.has("id"))return rpcError(Response.Status.BAD_REQUEST,-32600,"Request ID required",null);
            JSONObject params=input.optJSONObject("params");if(params==null)params=new JSONObject();JSONObject result;
            switch(method){
                case "initialize" -> {
                    String version=params.optString("protocolVersion","2025-11-25");if(!java.util.List.of("2025-11-25","2025-06-18","2025-03-26").contains(version))version="2025-11-25";
                    result=object("protocolVersion",version,"serverInfo",object("name","pocket-agent","version",BuildConfig.VERSION_NAME),"capabilities",object("tools",object("listChanged",false)),"instructions","PocketAgent phone tools and workers. Use the exact pocket_session supplied in the active PocketAgent turn. Every call needs a unique pocket_request; reuse it for an uncertain outcome. Calls execute on this phone under the worker’s existing permissions. Start tasks in PocketAgent; never invent a session ID. This is the production connector, not the debugging bridge.");
                }
                case "ping" -> result=new JSONObject();
                case "tools/list" -> {
                    RuntimeHost host=AgentService.peekHost();if(host==null)return rpcError(Response.Status.SERVICE_UNAVAILABLE,-32000,"Start PocketAgent to discover tools",id);
                    result=object("tools",host.connectorToolList().get(8,TimeUnit.SECONDS));
                }
                case "tools/call" -> {
                    RuntimeHost host=AgentService.peekHost();if(host==null)return rpcError(Response.Status.SERVICE_UNAVAILABLE,-32000,"PocketAgent runtime is not available",id);
                    String name=params.getString("name");JSONObject args=params.optJSONObject("arguments");if(args==null)args=new JSONObject();JSONObject copied=args;
                    Future<JSONObject> work;
                    try{work=workers.submit(()->host.connectorToolCall(principal,name,copied).get(40,TimeUnit.SECONDS));}
                    catch(RejectedExecutionException busy){return rpcError(Response.Status.SERVICE_UNAVAILABLE,-32000,"Device action queue full",id);}
                    try{result=work.get(42,TimeUnit.SECONDS);}
                    catch(TimeoutException|ExecutionException failure){result=object("isError",true,"content",new JSONArray().put(object("type","text","text","Execution outcome unknown. Inspect phone state or query with the same pocket_request; never repeat a mutation with a new ID.")));}
                }
                case "resources/list" -> result=object("resources",new JSONArray());
                case "prompts/list" -> result=object("prompts",new JSONArray());
                default -> {return rpcError(Response.Status.OK,-32601,"Method not found",id);}
            }
            return json(Response.Status.OK,object("jsonrpc","2.0","id",id,"result",result));
        } catch(org.json.JSONException malformed){return rpcError(Response.Status.BAD_REQUEST,-32700,"Malformed request",null);}
        catch(IllegalArgumentException rejected){return json(Response.Status.BAD_REQUEST,object("error","invalid_request","error_description",rejected.getMessage()));}
        catch(Exception unavailable){return json(Response.Status.SERVICE_UNAVAILABLE,object("error","Connector unavailable; check PocketAgent settings"));}
    }
    @Override public void stop(){closed=true;super.stop();workers.shutdownNow();}
}
