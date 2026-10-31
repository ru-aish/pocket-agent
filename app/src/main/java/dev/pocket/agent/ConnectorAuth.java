package dev.pocket.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Phone-owner pairing plus OAuth authorization-code/PKCE and rotating refresh tokens. */
final class ConnectorAuth {
    interface Store {String read() throws Exception;void write(String value) throws Exception;}
    static final long PAIRING_MS=300_000, ACCESS_MS=3_600_000, REFRESH_MS=30L*24*3_600_000;
    private final Store store;
    private final LongSupplier clock;
    private final SecureRandom random=new SecureRandom();
    private final Map<String,JSONObject> pending=new HashMap<>(),codes=new HashMap<>();
    private JSONObject state;
    private String pairing="",origin="";
    private long pairingUntil,failedUntil;
    private int attempts;

    ConnectorAuth(Store store,LongSupplier clock) throws Exception {
        this.store=store;this.clock=clock;
        String saved=store.read();state=saved.isEmpty()?new JSONObject():new JSONObject(saved);
        if(!state.has("clients"))state.put("clients",new JSONObject());
        if(!state.has("grants"))state.put("grants",new JSONObject());
    }
    synchronized void origin(String value) throws Exception {
        URI uri=URI.create(value);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getPath().isEmpty())
            throw new IllegalArgumentException("Use a public HTTPS origin without a path");
        origin=value;
        if(!value.equals(state.optString("origin"))) {state.put("origin",value).put("grants",new JSONObject());save();}
    }
    synchronized String origin(){return origin;}
    private String secret(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static String hash(String value) throws Exception {return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private void save() throws Exception {store.write(state.toString());}
    private static boolean equal(String left,String right){return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),right.getBytes(StandardCharsets.UTF_8));}
    synchronized JSONObject pairing() throws Exception {
        long now=clock.getAsLong();
        if(now>=pairingUntil){
            String alphabet="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";StringBuilder value=new StringBuilder();
            for(int i=0;i<12;i++)value.append(alphabet.charAt(random.nextInt(alphabet.length())));
            pairing=value.toString();pairingUntil=now+PAIRING_MS;
        }
        return new JSONObject().put("code",pairing.substring(0,4)+"-"+pairing.substring(4,8)+"-"+pairing.substring(8)).put("expiresAt",pairingUntil);
    }
    synchronized void rotatePairing(){pairing="";pairingUntil=0;}
    synchronized void revoke() throws Exception {state.put("grants",new JSONObject()).put("clients",new JSONObject());pending.clear();codes.clear();rotatePairing();save();}
    synchronized int connections() throws Exception {prune();return state.getJSONObject("grants").length();}
    static boolean redirectAllowed(String value) {
        try {
            URI uri=URI.create(value);
            return "https".equals(uri.getScheme())&&"chatgpt.com".equals(uri.getHost())&&uri.getPort()==-1&&uri.getUserInfo()==null&&uri.getRawQuery()==null&&uri.getRawFragment()==null
                &&(uri.getPath().equals("/connector_platform_oauth_redirect")||uri.getPath().matches("/connector/oauth/[A-Za-z0-9_-]+"));
        } catch(IllegalArgumentException error){return false;}
    }
    synchronized JSONObject register(JSONObject input) throws Exception {
        prune();JSONArray redirects=input.optJSONArray("redirect_uris");
        if(redirects==null||redirects.length()<1||redirects.length()>4||!input.optString("token_endpoint_auth_method","none").equals("none"))throw new IllegalArgumentException("Unsupported OAuth client");
        for(int i=0;i<redirects.length();i++)if(!redirectAllowed(redirects.getString(i)))throw new IllegalArgumentException("Only ChatGPT HTTPS callbacks are supported");
        JSONObject clients=state.getJSONObject("clients");
        if(clients.length()>=64)throw new IllegalStateException("Client registration limit reached; revoke unused clients on the phone");
        String id=secret();JSONObject client=new JSONObject().put("client_id",id).put("redirect_uris",redirects).put("created",clock.getAsLong()).put("authorized",false)
            .put("token_endpoint_auth_method","none").put("grant_types",new JSONArray().put("authorization_code").put("refresh_token")).put("response_types",new JSONArray().put("code"));
        clients.put(id,client);save();return new JSONObject(client.toString());
    }
    private void prune() throws Exception {
        long now=clock.getAsLong();pending.entrySet().removeIf(entry->entry.getValue().optLong("expires")<=now);codes.entrySet().removeIf(entry->entry.getValue().optLong("expires")<=now);
        JSONObject grants=state.getJSONObject("grants"),clients=state.getJSONObject("clients");
        for(String id:keys(grants))if(grants.getJSONObject(id).optLong("refreshUntil")<=now)grants.remove(id);
        for(String id:keys(clients)){JSONObject client=clients.getJSONObject(id);if(!client.optBoolean("authorized")&&now-client.optLong("created")>3_600_000)clients.remove(id);}
    }
    private static java.util.List<String> keys(JSONObject object){java.util.List<String> result=new java.util.ArrayList<>();object.keys().forEachRemaining(result::add);return result;}
    private boolean resource(String value){return value.equals(origin+"/mcp")||value.equals(origin);}
    synchronized String begin(Map<String,String> input) throws Exception {
        prune();
        if(origin.isEmpty()||pending.size()>=32)throw new IllegalStateException("Authorization is not ready");
        JSONObject client=state.getJSONObject("clients").optJSONObject(input.get("client_id"));
        String redirect=input.getOrDefault("redirect_uri",""),challenge=input.getOrDefault("code_challenge",""),scope=input.getOrDefault("scope","pocket.tools"),resource=input.getOrDefault("resource","");
        boolean registered=false;if(client!=null){JSONArray redirects=client.getJSONArray("redirect_uris");for(int i=0;i<redirects.length();i++)if(redirect.equals(redirects.getString(i)))registered=true;}
        if(!registered||!redirectAllowed(redirect)||!"code".equals(input.get("response_type"))||!"S256".equals(input.get("code_challenge_method"))||!challenge.matches("[A-Za-z0-9_-]{43}")||!scope.equals("pocket.tools")||!resource(resource)||input.getOrDefault("state","").length()>2048)
            throw new IllegalArgumentException("Invalid authorization request");
        String handle=secret();JSONObject request=new JSONObject(input).put("resource",resource).put("scope",scope).put("expires",clock.getAsLong()+PAIRING_MS);
        pending.put(handle,request);return handle;
    }
    synchronized String approve(String handle,String supplied) throws Exception {
        prune();JSONObject request=pending.get(handle);long now=clock.getAsLong();
        if(request==null||now<failedUntil)throw new IllegalArgumentException("Authorization expired or temporarily locked; start again in ChatGPT");
        if(now>=pairingUntil||pairing.isEmpty()||!equal(pairing,supplied.replace("-","").replace(" ","").toUpperCase(java.util.Locale.ROOT))){
            if(++attempts>=5){failedUntil=now+60_000;attempts=0;}throw new IllegalArgumentException("Incorrect or expired code. Use the code shown on this phone.");
        }
        if(state.getJSONObject("grants").length()>=16)throw new IllegalStateException("Connection limit reached; revoke connections on the phone");
        pending.remove(handle);rotatePairing();attempts=0;
        String code=secret();codes.put(hash(code),new JSONObject(request.toString()).put("expires",now+60_000));
        return request.getString("redirect_uri")+"?code="+code+"&iss="+encode(origin)+"&state="+encode(request.optString("state"));
    }
    static String encode(String value){try{return java.net.URLEncoder.encode(value,"UTF-8");}catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);}}
    synchronized JSONObject token(Map<String,String> input) throws Exception {
        prune();String client=input.getOrDefault("client_id",""),resource=input.getOrDefault("resource",""),type=input.getOrDefault("grant_type","");
        if(!state.getJSONObject("clients").has(client)||!resource(resource))throw new IllegalArgumentException("invalid_grant");
        String id;JSONObject grant;long now=clock.getAsLong();
        if(type.equals("authorization_code")) {
            String key=hash(input.getOrDefault("code",""));JSONObject request=codes.get(key);
            String verifier=input.getOrDefault("code_verifier","");
            if(request==null||!client.equals(request.getString("client_id"))||!resource.equals(request.getString("resource"))||!input.getOrDefault("redirect_uri","").equals(request.getString("redirect_uri"))||!verifier.matches("[A-Za-z0-9._~-]{43,128}")||!equal(hash(verifier),request.getString("code_challenge")))throw new IllegalArgumentException("invalid_grant");
            codes.remove(key);id=secret();grant=new JSONObject().put("client",client).put("resource",resource).put("refreshUntil",now+REFRESH_MS);
            state.getJSONObject("clients").getJSONObject(client).put("authorized",true);
        } else if(type.equals("refresh_token")) {
            String refresh=hash(input.getOrDefault("refresh_token",""));id="";grant=null;
            JSONObject grants=state.getJSONObject("grants");
            for(String candidate:keys(grants)){JSONObject value=grants.getJSONObject(candidate);if(equal(value.optString("refresh"),refresh)&&value.optString("client").equals(client)&&value.optString("resource").equals(resource)){id=candidate;grant=value;break;}}
            if(grant==null)throw new IllegalArgumentException("invalid_grant");
        } else throw new IllegalArgumentException("unsupported_grant_type");
        String access=secret(),refresh=secret();grant.put("access",hash(access)).put("accessUntil",now+ACCESS_MS).put("refresh",hash(refresh));
        state.getJSONObject("grants").put(id,grant);save();
        return new JSONObject().put("access_token",access).put("token_type","Bearer").put("expires_in",ACCESS_MS/1000).put("refresh_token",refresh).put("scope","pocket.tools");
    }
    synchronized String principal(String authorization) throws Exception {
        if(authorization==null||!authorization.startsWith("Bearer ")||authorization.length()>256||origin.isEmpty())return null;
        String hashed=hash(authorization.substring(7));JSONObject grants=state.getJSONObject("grants");
        for(String id:keys(grants)){JSONObject grant=grants.getJSONObject(id);if(grant.optLong("accessUntil")>clock.getAsLong()&&grant.optLong("refreshUntil")>clock.getAsLong()&&resource(grant.optString("resource"))&&equal(hashed,grant.optString("access")))return id;}
        return null;
    }
}
