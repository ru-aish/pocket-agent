package dev.pocket.agent;

import fi.iki.elonen.NanoHTTPD;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;

final class DevMcpServer extends NanoHTTPD {
    private static final int MAX_BODY = 1_048_576;
    private static final int MAX_CHUNKS = 4096;
    private static final int MAX_FRAMING_BYTES = 64 * 1024;
    private final byte[] auth;
    private final android.content.Context context;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(12));
    private volatile boolean closing;

    DevMcpServer(android.content.Context context, String token) {
        super("127.0.0.1", 8767);
        this.context = context.getApplicationContext();
        this.auth = token == null || token.isEmpty()
            ? null
            : ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    private Response respond(Response.IStatus status, String body) {
        Response result = newFixedLengthResponse(status, "application/json; charset=utf-8", body);
        result.addHeader("Cache-Control", "no-store");
        result.addHeader("X-Content-Type-Options", "nosniff");
        return result;
    }
    private Response error(Response.IStatus status, int code, String message, Object id) {
        return respond(status, object("jsonrpc","2.0","id",id,"error",object("code",code,"message",message)).toString());
    }
    private static JSONObject object(Object... fields) {
        JSONObject result = new JSONObject();
        try { for (int i=0;i<fields.length;i+=2) result.put((String)fields[i],fields[i+1]); }
        catch(Exception e) { throw new IllegalStateException(e); }
        return result;
    }
    private static JSONObject definition(String name, String description, JSONObject schema) {
        return object("name",name,"description",description,"inputSchema",schema);
    }
    private static JSONObject schema(Object... pairs) {
        JSONObject properties = new JSONObject();
        try { for(int i=0;i<pairs.length;i+=2) properties.put((String)pairs[i],pairs[i+1]); }
        catch(Exception e) { throw new IllegalStateException(e); }
        return object("type","object","properties",properties,"additionalProperties",false);
    }
    private static JSONObject field(String type) { return object("type",type); }
    private JSONObject diagnostics(String name) throws Exception {
        try (StateStore store = new StateStore(context)) {
            return switch(name) {
                case "debug_history" -> definition(name, "Search saved main and worker messages and tool-call transcripts; optional conversationId, query, limit (1..100), offset.", schema(
                    "query",field("string"),"conversationId",field("string"),"limit",field("integer"),"offset",field("integer")));
                case "debug_events" -> definition(name, "Search recent locally persisted runtime tool activity, errors and readiness events.", schema(
                    "query",field("string"),"limit",field("integer"),"offset",field("integer")));
                case "debug_state" -> definition(name, "Inspect the last rendered runtime state, without credentials or provider token fields.", schema());
                default -> throw new IllegalArgumentException("Unknown diagnostic");
            };
        }
    }
    private JSONObject execute(String name, JSONObject args) throws Exception {
        if(name.startsWith("debug_")) {
            try(StateStore store=new StateStore(context)) {
                JSONObject payload=switch(name) {
                    case "debug_history" -> DevHistory.query(store,args);
                    case "debug_events" -> DevHistory.events(store,args);
                    case "debug_state" -> object("state",store.load());
                    default -> throw new IllegalArgumentException("Unknown diagnostic");
                };
                return object("content",new JSONArray().put(object("type","text","text",payload.toString())),"structuredContent",payload,"isError",false);
            }
        }
        RuntimeHost host = AgentService.peekHost();
        if (host == null) return object("isError",true,"content",new JSONArray().put(object("type","text","text","Agent service not running")));
        return host.developmentToolCall(name,args).get(40,TimeUnit.SECONDS);
    }
    private static boolean supported(String v) {
        return v.equals("2025-11-25") || v.equals("2025-06-18") || v.equals("2025-03-26");
    }
    @Override public Response serve(IHTTPSession request) {
        String authorization=request.getHeaders().get("authorization");
        if(auth!=null && (authorization==null || !MessageDigest.isEqual(auth,authorization.getBytes(StandardCharsets.UTF_8))))
            return error(Response.Status.UNAUTHORIZED,-32001,"Bearer authorization required",null);
        if(request.getHeaders().containsKey("origin"))
            return error(Response.Status.FORBIDDEN,-32002,"Browser access disabled",null);
        if(!"/mcp".equals(request.getUri()))
            return error(Response.Status.NOT_FOUND,-32601,"Unknown endpoint",null);
        if(request.getMethod()!=Method.POST)
            return error(Response.Status.METHOD_NOT_ALLOWED,-32600,"POST only",null);
        String protocol=request.getHeaders().get("mcp-protocol-version");
        if(protocol!=null && !supported(protocol))
            return error(Response.Status.BAD_REQUEST,-32600,"Unsupported MCP protocol",null);
        String type=request.getHeaders().get("content-type");
        if(type==null || !type.split(";",2)[0].trim().equalsIgnoreCase("application/json"))
            return error(Response.Status.UNSUPPORTED_MEDIA_TYPE,-32600,"JSON required",null);
        JSONObject input;Object id=null;
        try {
            byte[] content=McpHttpBody.readRequestBody(request);
            if(content.length==0)throw new IllegalArgumentException("Empty body");
            input=new JSONObject(new String(content,StandardCharsets.UTF_8));
            if(!"2.0".equals(input.optString("jsonrpc"))) throw new IllegalArgumentException();
            id=input.opt("id");
            if(id!=null && !(id instanceof String || id instanceof Integer || id instanceof Long)) throw new IllegalArgumentException();
        } catch(Exception error) {
            return error(Response.Status.BAD_REQUEST,-32700,"Invalid JSON-RPC request",id);
        }
        String method=input.optString("method");
        if(!input.has("id")) {
            if(method.startsWith("notifications/")) return respond(Response.Status.ACCEPTED,"");
            return error(Response.Status.BAD_REQUEST,-32600,"Request id required",null);
        }
        JSONObject params=input.optJSONObject("params");
        if(params==null)params=new JSONObject();
        JSONObject result;
        try {
            switch(method) {
                case "initialize" -> result=object("protocolVersion",supported(params.optString("protocolVersion","2025-11-25"))?params.optString("protocolVersion","2025-11-25"):"2025-11-25",
                    "capabilities",object("tools",object("listChanged",false)),"serverInfo",object("name","pocket-agent-development","version",BuildConfig.VERSION_NAME),
                    "instructions","Development-only privileged bridge. All phone tools can affect the real device. Use phone_capabilities and operation_id for mutations; never blindly retry unknown outcomes. debug_history and debug_events inspect saved local evidence. Release virtual displays when finished.");
                case "ping" -> result=new JSONObject();
                case "tools/list" -> {
                    JSONArray tools=new JSONArray();
                    RuntimeHost host=AgentService.peekHost();
                    if(host==null) return error(Response.Status.SERVICE_UNAVAILABLE,-32000,"Agent initializing; retry tools/list",id);
                    JSONArray original;
                    try {original=host.developmentToolList().get(8,TimeUnit.SECONDS);}
                    catch(Exception notReady) {return error(Response.Status.SERVICE_UNAVAILABLE,-32000,"Agent initializing; retry tools/list",id);}
                    for(int i=0;i<original.length();i++)tools.put(original.get(i));
                    tools.put(diagnostics("debug_history"));
                    tools.put(diagnostics("debug_events"));
                    tools.put(diagnostics("debug_state"));
                    result=object("tools",tools);
                }
                case "tools/call" -> {
                    String name=params.getString("name");
                    JSONObject args=params.optJSONObject("arguments");
                    if(args==null)args=new JSONObject();
                    if(closing)return error(Response.Status.SERVICE_UNAVAILABLE,-32000,"Server stopping",id);
                    JSONObject copied=args;Future<JSONObject> future;
                    try {future=workers.submit(()->execute(name,copied));}
                    catch(RejectedExecutionException full) {result=object("isError",true,"content",new JSONArray().put(object("type","text","text","Device action queue full")));break;}
                    try {result=future.get(42,TimeUnit.SECONDS);}
                    catch(TimeoutException timeout) {future.cancel(false);result=object("isError",true,"content",new JSONArray().put(object("type","text","text","Operation outcome unknown; inspect device state before retrying"))); }
                    catch(ExecutionException failure) {result=object("isError",true,"content",new JSONArray().put(object("type","text","text","Tool rejected or unavailable; inspect debug_events"))); }
                    break;
                }
                case "resources/list" -> result=object("resources",new JSONArray());
                case "prompts/list" -> result=object("prompts",new JSONArray());
                default -> {return error(Response.Status.OK,-32601,"Method not found",id);}
            }
        } catch(Exception error) {return error(Response.Status.OK,-32602,"Invalid parameters or runtime unavailable",id);}
        return respond(Response.Status.OK,object("jsonrpc","2.0","id",id,"result",result).toString());
    }
    @Override public void stop() {closing=true;super.stop();workers.shutdownNow();}
}
