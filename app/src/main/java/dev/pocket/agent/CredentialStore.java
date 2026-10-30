package dev.pocket.agent;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Allowlisted credentials encrypted with a non-exportable Android Keystore key. */
public final class CredentialStore {
    private static final String ALIAS = "pocket-agent-provider-v1";
    // Encrypted alongside the provider, but never exposed to Pi's provider config.
    private static final String VOICE_ACCOUNT = "subscriptionVoiceAccount";
    // Inactive connections remain inside the same Keystore envelope, not in runtime config.
    private static final String PROFILES = "providerProfiles";
    // Runtime refresh and the settings Activity use different store instances.
    private static final Object LOCK = new Object();
    public static final String CODEX_ACCOUNT = "codex-account";
    public static final String CODEX_MODEL = "gpt-6-luna";
    public static final String DEFAULT_REASONING_EFFORT = "low";
    public static final String OPENCODE = "opencode";
    public static final String OPENCODE_ENDPOINT = "https://opencode.ai/inference/openai/v1";
    public static final String OPENCODE_MODEL = "big-pickle";
    public static final String CHATGPT_DESKTOP = "chatgpt-desktop";
    public static final String NEBIUS = "nebius";
    public static final String NEBIUS_ENDPOINT = "https://api.tokenfactory.nebius.com/v1";
    public static final String NEBIUS_MODEL = "zai-org/GLM-5.3-Flash";
    private static final String BUILTIN_PROVIDER_REVISION = "builtin-scripted-v1";
    private final Context context;
    public CredentialStore(Context context) { this.context = context.getApplicationContext(); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public JSONObject get() throws Exception { synchronized (LOCK) {
        JSONObject current = read();
        boolean changed = false;
        if (!current.has("providerRevision")) { current.put("providerRevision", UUID.randomUUID().toString()); changed = true; }
        String effort = reasoningEffort(current.optString("reasoningEffort", DEFAULT_REASONING_EFFORT));
        String preferredEffort = reasoningEffort(current.optString("preferredReasoningEffort", effort));
        if (!effort.equals(current.optString("reasoningEffort"))) { current.put("reasoningEffort", effort); changed = true; }
        if (!preferredEffort.equals(current.optString("preferredReasoningEffort"))) { current.put("preferredReasoningEffort", preferredEffort); changed = true; }
        JSONArray supported = reasoningEfforts(current.optJSONArray("reasoningEfforts"));
        boolean codexAccount = CODEX_ACCOUNT.equals(current.optString("authMode"));
        String currentModel = current.optString("model");
        boolean manualModel = "manual".equals(current.optString("modelSource"));
        boolean validProof = !manualModel && !currentModel.isEmpty()
                && currentModel.equals(current.optString("reasoningEffortModel"))
                && supported.length() > 0;
        String effectiveEffort = effectiveReasoningEffort(effort, supported, validProof, manualModel, codexAccount || "catalog".equals(current.optString("modelSource")));
        if (!effectiveEffort.equals(effort)) {
            current.put("reasoningEffort", effectiveEffort);
            changed = true;
        }
        if (validProof) {
            JSONArray existing = current.optJSONArray("reasoningEfforts");
            if (existing == null || !supported.toString().equals(existing.toString())) {
                current.put("reasoningEfforts", supported); changed = true;
            }
        } else if (current.has("reasoningEffortModel") || current.has("reasoningEfforts")) {
            current.remove("reasoningEffortModel"); current.remove("reasoningEfforts"); changed = true;
        }
        if (changed) write(current);
        current.remove(VOICE_ACCOUNT);
        current.remove(PROFILES);
        current.put("mcpServers", sanitizedMcpServers(current.optJSONArray("mcpServers")));
        return current;
    } }
    /** Settings-only view of one encrypted connection, including inactive providers. */
    public JSONObject getProviderProfile(String mode) throws Exception { synchronized (LOCK) {
        JSONObject current = read();
        String canonical = canonicalMode(mode);
        JSONObject profiles = current.optJSONObject(PROFILES);
        JSONObject profile = canonical.equals(canonicalMode(current.optString("authMode")))
                ? current : profiles == null ? null : profiles.optJSONObject(canonical);
        return profile == null ? new JSONObject() : profileSnapshot(profile);
    } }
    private JSONObject read() throws Exception {
        String saved = context.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE).getString("encrypted", null);
        if (saved == null) return new JSONObject().put("authMode", "scripted").put("endpoint", "https://api.openai.com/v1").put("model", "gpt-4.1-mini")
                .put("reasoningEffort", DEFAULT_REASONING_EFFORT).put("preferredReasoningEffort", DEFAULT_REASONING_EFFORT).put("providerRevision", BUILTIN_PROVIDER_REVISION);
        JSONObject envelope = new JSONObject(saved);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), StandardCharsets.UTF_8));
    }
    private void write(JSONObject allowed) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] data = cipher.doFinal(allowed.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject envelope = new JSONObject().put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).put("data", Base64.encodeToString(data, Base64.NO_WRAP));
        if (!context.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE).edit().putString("encrypted", envelope.toString()).commit())
            throw new IllegalStateException("Could not save protected connection");
    }
    public void save(JSONObject settings) throws Exception {
        synchronized (LOCK) {
            JSONObject current = read();
            String mode = settings.optString("authMode");
            JSONObject profiles = current.optJSONObject(PROFILES);
            if (profiles == null) profiles = new JSONObject();
            JSONObject previous = canonicalMode(mode).equals(canonicalMode(current.optString("authMode")))
                    ? current : profiles.optJSONObject(canonicalMode(mode));
            if (previous == null) previous = new JSONObject();
            String endpoint = fixedEndpoint(mode, settings.optString("endpoint"));
            validateEndpoint(endpoint, mode);
            String apiKey = settings.optString("apiKey").trim();
            // A blank field reuses a key only for the same endpoint, never a new host.
            if (apiKey.isEmpty() && endpoint.equals(previous.optString("endpoint")))
                apiKey = previous.optString("apiKey");
            if (OPENCODE.equals(mode) && apiKey.trim().isEmpty()) throw new IllegalArgumentException("Enter an OpenCode Console service-account key");
            if (NEBIUS.equals(mode) && apiKey.trim().isEmpty()) throw new IllegalArgumentException("Enter a Nebius API key");
            boolean newAccountCredential = CODEX_ACCOUNT.equals(mode) && settings.has("accessToken");
            String effort = reasoningEffort(settings.has("reasoningEffort")
                    ? settings.optString("reasoningEffort")
                    : newAccountCredential ? DEFAULT_REASONING_EFFORT : previous.optString("reasoningEffort", DEFAULT_REASONING_EFFORT));
            String currentPreferred = reasoningEffort(current.optString("preferredReasoningEffort",
                    current.optString("reasoningEffort", DEFAULT_REASONING_EFFORT)));
            String preferredEffort = reasoningEffort(settings.has("preferredReasoningEffort")
                    ? settings.optString("preferredReasoningEffort")
                    : settings.has("reasoningEffort") && !CODEX_ACCOUNT.equals(mode) ? effort : currentPreferred);
            JSONObject allowed = new JSONObject().put("authMode", mode).put("reasoningEffort", effort)
                    .put("preferredReasoningEffort", preferredEffort);
            allowed.put("mcpServers", sanitizedMcpServers(settings.optJSONArray("mcpServers") == null
                    ? current.optJSONArray("mcpServers") : settings.optJSONArray("mcpServers")));
            if (CODEX_ACCOUNT.equals(mode)) {
                JSONObject source = settings.has("accessToken") ? settings : previous;
                if (!CODEX_ACCOUNT.equals(source.optString("authMode")) && !settings.has("accessToken")) throw new IllegalArgumentException("Sign in or import an account first");
                JSONObject account = CodexAccountAuth.validateCredentials(source, false);
                for (String field : new String[]{"accessToken", "refreshToken", "accountId", "expiresAt", "idToken"})
                    if (account.has(field)) allowed.put(field, account.get(field));
                String selectedModel = model(settings.optString("model", CODEX_MODEL));
                allowed.put("model", selectedModel);
            } else {
                allowed.put("endpoint", endpoint);
                allowed.put("apiKey", apiKey);
                allowed.put("model", model(settings.optString("model")));
            }
            String selectedModel = allowed.getString("model");
            boolean sameProvider = sameProviderIdentity(previous, allowed);
            String source = settings.optString("modelSource");
            if (!settings.has("modelSource") && !newAccountCredential
                    && sameProvider && selectedModel.equals(previous.optString("model")))
                source = previous.optString("modelSource");
            if (source.equals("manual") || source.equals("catalog")) allowed.put("modelSource", source);
            boolean manualModel = source.equals("manual");
            boolean proofSpecified = settings.has("reasoningEffortModel") || settings.has("reasoningEfforts");
            JSONArray supported = newAccountCredential || manualModel ? new JSONArray()
                    : reasoningEfforts(settings.optJSONArray("reasoningEfforts"));
            boolean validProof = sameProvider && !newAccountCredential && !manualModel
                    && selectedModel.equals(settings.optString("reasoningEffortModel")) && supported.length() > 0;
            if (!proofSpecified && !newAccountCredential && !manualModel
                    && sameProvider
                    && selectedModel.equals(previous.optString("reasoningEffortModel"))) {
                supported = reasoningEfforts(previous.optJSONArray("reasoningEfforts"));
                validProof = supported.length() > 0;
            }
            String effectiveEffort = effectiveReasoningEffort(effort, supported, validProof, manualModel, CODEX_ACCOUNT.equals(mode) || source.equals("catalog"));
            if (!effort.equals(effectiveEffort))
                throw new IllegalArgumentException("Choose a reasoning effort supported by this model, or enter a manual model ID");
            if (validProof) {
                allowed.put("reasoningEffortModel", selectedModel);
                allowed.put("reasoningEfforts", supported);
            }
            allowed.put("providerRevision", providerRevision(previous, allowed));
            JSONObject voice = current.optJSONObject(VOICE_ACCOUNT);
            // Preserve an independently selected voice account across chat settings.
            if (CODEX_ACCOUNT.equals(mode) && (voice == null || sameCodexAccount(voice, allowed)))
                voice = accountSnapshot(allowed);
            else if (voice == null && CODEX_ACCOUNT.equals(current.optString("authMode")))
                voice = accountSnapshot(current);
            if (voice != null) allowed.put(VOICE_ACCOUNT, voice);
            profiles.put(canonicalMode(current.optString("authMode")), profileSnapshot(current));
            profiles.put(canonicalMode(mode), profileSnapshot(allowed));
            allowed.put(PROFILES, profiles);
            write(allowed);
        }
    }
    public JSONArray mcpServers() throws Exception { synchronized (LOCK) {
        return sanitizedMcpServers(read().optJSONArray("mcpServers"));
    } }
    public JSONObject saveMcpServer(JSONObject server) throws Exception { synchronized (LOCK) {
        JSONArray servers = sanitizedMcpServers(read().optJSONArray("mcpServers"));
        String id = server.optString("id").trim();
        if (id.isEmpty()) id = UUID.randomUUID().toString();
        if (!id.matches("[a-fA-F0-9-]{36}")) throw new IllegalArgumentException("Invalid MCP server identifier");
        JSONObject validated = validateMcpServer(server, id);
        JSONArray next = new JSONArray(); boolean replaced = false;
        for (int i = 0; i < servers.length(); i++) {
            JSONObject item = servers.optJSONObject(i);
            if (item == null) continue;
            if (id.equals(item.optString("id"))) { next.put(validated); replaced = true; }
            else next.put(item);
        }
        if (!replaced) {
            if (next.length() >= 10) throw new IllegalArgumentException("Up to 10 MCP servers can be saved");
            next.put(validated);
        }
        JSONObject current = read(); current.put("mcpServers", next); write(current);
        return new JSONObject(validated.toString());
    } }
    public void deleteMcpServer(String id) throws Exception { synchronized (LOCK) {
        JSONArray next = new JSONArray(), servers = sanitizedMcpServers(read().optJSONArray("mcpServers"));
        for (int i = 0; i < servers.length(); i++) {
            JSONObject item = servers.optJSONObject(i);
            if (item != null && !id.equals(item.optString("id"))) next.put(item);
        }
        JSONObject current = read(); current.put("mcpServers", next); write(current);
    } }
    private static JSONArray sanitizedMcpServers(JSONArray values) throws Exception {
        JSONArray result = new JSONArray(); Set<String> ids = new HashSet<>();
        if (values == null) return result;
        if (values.length() > 10) throw new IllegalArgumentException("Up to 10 MCP servers can be saved");
        for (int i = 0; i < values.length(); i++) {
            JSONObject raw = values.optJSONObject(i);
            if (raw == null) continue;
            String id = raw.optString("id");
            if (id.isEmpty() || !ids.add(id)) continue;
            result.put(validateMcpServer(raw, id));
        }
        return result;
    }
    private static JSONObject validateMcpServer(JSONObject value, String id) throws Exception {
        String name=value.optString("name").trim(), url=value.optString("url").trim();
        String authMode=value.optString("authMode", value.has("headerName")?"headers":"none");
        String bearerToken=value.optString("bearerToken");
        JSONArray headers=value.optJSONArray("headers");
        if(value.has("headers")&&headers==null)throw new IllegalArgumentException("MCP custom headers are invalid");
        if(headers==null){headers=new JSONArray();String legacyName=value.optString("headerName").trim();
            if(!legacyName.isEmpty())headers.put(new JSONObject().put("name",legacyName).put("value",value.optString("headerValue")));}
        if(name.isEmpty()||name.length()>60)throw new IllegalArgumentException("MCP server name must be 1..60 characters");
        if(!authMode.equals("none")&&!authMode.equals("bearer")&&!authMode.equals("headers"))throw new IllegalArgumentException("Choose None, Bearer token, or Custom headers");
        if(bearerToken.length()>2000||hasLineBreakOrNul(bearerToken))throw new IllegalArgumentException("MCP authentication value is invalid or too long");
        if(headers.length()>8)throw new IllegalArgumentException("MCP supports up to 8 custom headers");
        JSONArray cleanHeaders=new JSONArray(); Set<String> headerNames=new HashSet<>();
        for(int i=0;i<headers.length();i++){
            JSONObject header=headers.optJSONObject(i);if(header==null)throw new IllegalArgumentException("MCP custom header is invalid");
            String headerName=header.optString("name").trim(),headerValue=header.optString("value");
            if(headerName.length()>100||headerValue.length()>2000||hasLineBreakOrNul(headerValue)
                    ||!headerName.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}"))throw new IllegalArgumentException("MCP authentication header is invalid or too long");
            if(!headerNames.add(headerName.toLowerCase(java.util.Locale.ROOT)))throw new IllegalArgumentException("MCP custom header names must be unique");
            cleanHeaders.put(new JSONObject().put("name",headerName).put("value",headerValue));
        }
        if((authMode.equals("none")&&(!bearerToken.isEmpty()||cleanHeaders.length()>0||!value.optString("headerValue").isEmpty()))
                ||(authMode.equals("bearer")&&(cleanHeaders.length()>0||!value.optString("headerValue").isEmpty()))
                ||(authMode.equals("headers")&&!bearerToken.isEmpty()))throw new IllegalArgumentException("MCP authentication fields do not match the selected mode");
        URI uri;
        try { uri=new URI(url); } catch(Exception invalid) { throw new IllegalArgumentException("Enter a valid MCP HTTPS URL"); }
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||url.length()>2048)
            throw new IllegalArgumentException("MCP server URL must use HTTPS and contain no embedded credentials, query or fragment");
        JSONObject clean=new JSONObject().put("id",id).put("name",name).put("url",uri.toASCIIString())
                .put("enabled",value.optBoolean("enabled",true)).put("authMode",authMode);
        if(authMode.equals("bearer")&&!bearerToken.isEmpty())clean.put("bearerToken",bearerToken);
        if(authMode.equals("headers"))clean.put("headers",cleanHeaders);
        return clean;
    }
    private static boolean hasLineBreakOrNul(String value) { return value.indexOf('\r')>=0||value.indexOf('\n')>=0||value.indexOf('\0')>=0; }
    /** Compare-and-save prevents an older concurrent refresh from overwriting rotated tokens. */
    public JSONObject updateCodex(JSONObject update) throws Exception {
        synchronized (LOCK) {
            JSONObject current = read(); String old = update.optString("oldRefreshToken");
            if (!CODEX_ACCOUNT.equals(current.optString("authMode")) || old.isEmpty()
                    || !MessageDigest.isEqual(old.getBytes(StandardCharsets.UTF_8), current.optString("refreshToken").getBytes(StandardCharsets.UTF_8)))
                throw new IllegalStateException("Account changed while refreshing; reload protected credentials");
            for (String field : new String[]{"accessToken", "refreshToken", "accountId", "expiresAt"})
                if (!update.has(field)) throw new IllegalArgumentException("Refreshed account credentials are incomplete");
            JSONObject account = CodexAccountAuth.validateCredentials(update, true);
            if (!current.optString("accountId").equals(account.getString("accountId"))) throw new IllegalArgumentException("Refreshed account does not match the signed-in account");
            String selectedModel = model(current.optString("model", CODEX_MODEL));
            String effort = reasoningEffort(current.optString("reasoningEffort", DEFAULT_REASONING_EFFORT));
            String preferredEffort = reasoningEffort(current.optString("preferredReasoningEffort", effort));
            JSONArray supported = reasoningEfforts(current.optJSONArray("reasoningEfforts"));
            boolean manualModel = "manual".equals(current.optString("modelSource"));
            boolean validProof = !manualModel && selectedModel.equals(current.optString("reasoningEffortModel"))
                    && supported.length() > 0;
            effort = effectiveReasoningEffort(effort, supported, validProof, manualModel, true);
            JSONObject allowed = new JSONObject().put("authMode", CODEX_ACCOUNT)
                    .put("model", selectedModel).put("reasoningEffort", effort).put("preferredReasoningEffort", preferredEffort);
            String source = current.optString("modelSource");
            if (source.equals("manual") || source.equals("catalog")) allowed.put("modelSource", source);
            allowed.put("mcpServers", sanitizedMcpServers(current.optJSONArray("mcpServers")));
            if (validProof) {
                allowed.put("reasoningEffortModel", selectedModel);
                allowed.put("reasoningEfforts", supported);
            }
            for (String field : new String[]{"accessToken", "refreshToken", "accountId", "expiresAt", "idToken"})
                if (account.has(field)) allowed.put(field, account.get(field));
            String revision = current.optString("providerRevision");
            allowed.put("providerRevision", revision.isEmpty() ? UUID.randomUUID().toString() : revision);
            JSONObject profiles = current.optJSONObject(PROFILES);
            if (profiles == null) profiles = new JSONObject();
            profiles.put(CODEX_ACCOUNT, profileSnapshot(allowed));
            allowed.put(PROFILES, profiles);
            JSONObject voice = current.optJSONObject(VOICE_ACCOUNT);
            if (voice == null || current.optString("accountId").equals(voice.optString("accountId")))
                voice = accountSnapshot(allowed);
            allowed.put(VOICE_ACCOUNT, voice);
            write(allowed);
            allowed.remove(VOICE_ACCOUNT);
            allowed.remove(PROFILES);
            return new JSONObject(allowed.toString());
        }
    }
    /** The voice account is separate from the currently selected chat provider. */
    public JSONObject getVoiceCodex() throws Exception {
        synchronized (LOCK) {
            JSONObject current = read();
            JSONObject voice = current.optJSONObject(VOICE_ACCOUNT);
            if (voice != null) return new JSONObject(voice.toString());
            return CODEX_ACCOUNT.equals(current.optString("authMode"))
                    ? accountSnapshot(current) : new JSONObject();
        }
    }

    public void saveVoiceCodex(JSONObject input) throws Exception {
        synchronized (LOCK) {
            JSONObject checked = CodexAccountAuth.validateCredentials(input, false);
            JSONObject current = read();
            current.put(VOICE_ACCOUNT, accountSnapshot(checked));
            write(current);
        }
    }

    /** Rotates the voice account without switching or overwriting the selected chat model. */
    public JSONObject updateVoiceCodex(JSONObject update) throws Exception {
        synchronized (LOCK) {
            JSONObject current = read();
            JSONObject voice = current.optJSONObject(VOICE_ACCOUNT);
            if (voice == null && CODEX_ACCOUNT.equals(current.optString("authMode")))
                voice = accountSnapshot(current);
            String old = update.optString("oldRefreshToken");
            if (voice == null || old.isEmpty() || !MessageDigest.isEqual(
                    old.getBytes(StandardCharsets.UTF_8),
                    voice.optString("refreshToken").getBytes(StandardCharsets.UTF_8)))
                throw new IllegalStateException("Voice account changed while refreshing");
            JSONObject refreshed = CodexAccountAuth.validateCredentials(update, true);
            if (!voice.optString("accountId").equals(refreshed.optString("accountId")))
                throw new IllegalArgumentException("Refreshed voice account does not match");
            if (CODEX_ACCOUNT.equals(current.optString("authMode"))
                    && old.equals(current.optString("refreshToken"))
                    && voice.optString("accountId").equals(current.optString("accountId"))) {
                updateCodex(update);
            } else {
                current.put(VOICE_ACCOUNT, accountSnapshot(refreshed));
                JSONObject profiles = current.optJSONObject(PROFILES);
                JSONObject cached = profiles == null ? null : profiles.optJSONObject(CODEX_ACCOUNT);
                if (cached != null && old.equals(cached.optString("refreshToken"))
                        && cached.optString("accountId").equals(refreshed.optString("accountId"))) {
                    JSONObject next = profileSnapshot(cached);
                    for (String field : new String[]{"accessToken", "refreshToken", "accountId", "expiresAt", "idToken"})
                        if (refreshed.has(field)) next.put(field, refreshed.get(field));
                    profiles.put(CODEX_ACCOUNT, next);
                }
                write(current);
            }
            return getVoiceCodex();
        }
    }

    public void signOutVoiceCodex() throws Exception {
        synchronized (LOCK) {
            JSONObject current = read();
            JSONObject voice = current.optJSONObject(VOICE_ACCOUNT);
            if (CODEX_ACCOUNT.equals(current.optString("authMode"))
                    && (voice == null || sameCodexAccount(voice, current))) {
                signOutCodex();
            } else {
                current.remove(VOICE_ACCOUNT);
                write(current);
            }
        }
    }

    public boolean voiceSharesChatAccount() throws Exception {
        synchronized (LOCK) {
            JSONObject current = read();
            JSONObject voice = current.optJSONObject(VOICE_ACCOUNT);
            return CODEX_ACCOUNT.equals(current.optString("authMode"))
                && (voice == null || sameCodexAccount(voice, current));
        }
    }

    private static boolean sameCodexAccount(JSONObject left, JSONObject right) {
        String id = left.optString("accountId");
        return !id.isEmpty() && id.equals(right.optString("accountId"));
    }

    private static JSONObject accountSnapshot(JSONObject source) throws Exception {
        JSONObject snapshot = new JSONObject().put("authMode", CODEX_ACCOUNT);
        for (String field : new String[]{"accessToken", "refreshToken", "accountId", "expiresAt", "idToken"})
            if (source.has(field)) snapshot.put(field, source.get(field));
        return snapshot;
    }

    /** Signs out the saved chat account (active or inactive), preserving independent voice. */
    public boolean signOutCodex() throws Exception {
        synchronized (LOCK) {
            JSONObject current = read();
            boolean active = CODEX_ACCOUNT.equals(current.optString("authMode"));
            JSONObject profiles = current.optJSONObject(PROFILES);
            if (profiles == null) profiles = new JSONObject();
            JSONObject account = active ? current : profiles.optJSONObject(CODEX_ACCOUNT);
            if (account == null) return false;
            JSONObject voice = current.optJSONObject(VOICE_ACCOUNT);
            boolean sharedVoice = (active && voice == null)
                    || (voice != null && sameCodexAccount(voice, account));
            profiles.remove(CODEX_ACCOUNT);
            if (!active) {
                if (sharedVoice) current.remove(VOICE_ACCOUNT);
                current.put(PROFILES, profiles);
                write(current);
                return sharedVoice;
            }
            String effort = reasoningEffort(current.optString("reasoningEffort", DEFAULT_REASONING_EFFORT));
            String preferredEffort = reasoningEffort(current.optString("preferredReasoningEffort", effort));
            JSONObject allowed = new JSONObject().put("authMode", "scripted").put("model", "scripted").put("endpoint", "https://api.openai.com/v1")
                    .put("reasoningEffort", preferredEffort).put("preferredReasoningEffort", preferredEffort).put("providerRevision", UUID.randomUUID().toString())
                    .put("mcpServers", sanitizedMcpServers(current.optJSONArray("mcpServers")));
            if (!sharedVoice) allowed.put(VOICE_ACCOUNT, voice);
            profiles.put("scripted", profileSnapshot(allowed));
            allowed.put(PROFILES, profiles);
            write(allowed);
            return sharedVoice;
        }
    }
    public void removeKeyProvider(String mode) throws Exception { synchronized(LOCK) {
        if(!NEBIUS.equals(mode)&&!OPENCODE.equals(mode))throw new IllegalArgumentException("Choose a key provider");
        JSONObject current=read(),profiles=current.optJSONObject(PROFILES);if(profiles==null)profiles=new JSONObject();profiles.remove(mode);
        if(mode.equals(current.optString("authMode"))){
            for(String field:new String[]{"apiKey","accessToken","refreshToken","accountId","expiresAt","idToken","modelSource","reasoningEffortModel","reasoningEfforts"})current.remove(field);
            current.put("authMode","scripted").put("model","scripted").put("endpoint","https://api.openai.com/v1").put("providerRevision",UUID.randomUUID().toString());
        }
        current.put(PROFILES,profiles);write(current);
    } }
    private static JSONObject profileSnapshot(JSONObject source) throws Exception {
        JSONObject result = new JSONObject();
        for (String field : new String[]{"authMode", "endpoint", "apiKey", "model", "modelSource",
                "reasoningEffort", "preferredReasoningEffort", "reasoningEffortModel",
                "reasoningEfforts", "providerRevision", "accessToken", "refreshToken",
                "accountId", "expiresAt", "idToken"})
            if (source.has(field)) result.put(field, source.get(field));
        return result;
    }
    private static String canonicalMode(String mode) {
        if ("api-key".equals(mode)) return "openai";
        if ("codexRelay".equals(mode) || "codex-relay".equals(mode)) return "codex";
        return mode;
    }
    private static boolean sameProviderIdentity(JSONObject left, JSONObject right) {
        String mode = canonicalMode(left.optString("authMode"));
        if (!mode.equals(canonicalMode(right.optString("authMode")))) return false;
        if (CODEX_ACCOUNT.equals(mode)) {
            String accountId = left.optString("accountId");
            return !accountId.isEmpty() && accountId.equals(right.optString("accountId"));
        }
        if ("scripted".equals(mode)) return true;
        if (!left.optString("endpoint").equals(right.optString("endpoint"))) return false;
        return MessageDigest.isEqual(left.optString("apiKey").getBytes(StandardCharsets.UTF_8), right.optString("apiKey").getBytes(StandardCharsets.UTF_8));
    }
    private static String providerRevision(JSONObject current, JSONObject next) {
        String revision = current.optString("providerRevision");
        return !revision.isEmpty() && sameProviderIdentity(current, next) ? revision : UUID.randomUUID().toString();
    }
    private static String model(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:/+@-]{1,160}")) throw new IllegalArgumentException("Enter a valid model name");
        return value;
    }
    private static String reasoningEffort(String value) {
        return switch (value) {
            case "minimal", "low", "medium", "high", "xhigh", "max", "standard", "extended" -> value;
            default -> DEFAULT_REASONING_EFFORT;
        };
    }
    private static String effectiveReasoningEffort(String effort, JSONArray supported,
            boolean validProof, boolean manualModel, boolean restrictUnverified) {
        if (manualModel) return effort;
        if (validProof) return containsReasoningEffort(supported, effort) ? effort
                : containsReasoningEffort(supported, DEFAULT_REASONING_EFFORT)
                        ? DEFAULT_REASONING_EFFORT : supported.optString(0);
        return restrictUnverified ? DEFAULT_REASONING_EFFORT : effort;
    }
    private static JSONArray reasoningEfforts(JSONArray values) {
        JSONArray allowed = new JSONArray();
        if (values == null) return allowed;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            String value = values.optString(i);
            if (!value.isEmpty() && value.equals(reasoningEffort(value)) && seen.add(value)) allowed.put(value);
        }
        return allowed;
    }
    private static boolean containsReasoningEffort(JSONArray values, String effort) {
        for (int i = 0; i < values.length(); i++) if (effort.equals(values.optString(i))) return true;
        return false;
    }
    private static String fixedEndpoint(String authMode, String endpoint) {
        if (OPENCODE.equals(authMode)) return OPENCODE_ENDPOINT;
        if (NEBIUS.equals(authMode)) return NEBIUS_ENDPOINT;
        return endpoint;
    }
    public static void validateEndpoint(String endpoint, String authMode) throws Exception {
        if (authMode.equals("scripted") || authMode.equals(CODEX_ACCOUNT)) return;
        if (!authMode.equals("openai") && !authMode.equals("api-key") && !authMode.equals(OPENCODE) && !authMode.equals(NEBIUS) && !authMode.equals(CHATGPT_DESKTOP) && !authMode.equals("codex") && !authMode.equals("codexRelay") && !authMode.equals("codex-relay")) throw new IllegalArgumentException("Unknown authentication mode");
        URI uri;
        try { uri = new URI(endpoint); } catch (Exception invalid) { throw new IllegalArgumentException("Enter a valid API base URL"); }
        if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null || uri.getHost() == null)
            throw new IllegalArgumentException("Endpoint must be an API base URL without credentials, query or fragment");
        if (CHATGPT_DESKTOP.equals(authMode)) {
            String host = uri.getHost();
            String[] parts = host.split("\\.");
            boolean tailnet = parts.length == 4;
            int[] numbers = new int[4];
            for (int i = 0; tailnet && i < 4; i++) {
                try { numbers[i] = Integer.parseInt(parts[i]); tailnet = numbers[i] >= 0 && numbers[i] <= 255; }
                catch (NumberFormatException invalid) { tailnet = false; }
            }
            tailnet = tailnet && numbers[0] == 100 && numbers[1] >= 64 && numbers[1] <= 127;
            boolean local = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
            if ((!tailnet && !local) || (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")))
                throw new IllegalArgumentException("Use a Tailscale IPv4 or loopback desktop URL without a path");
            return;
        }
        boolean relay = authMode.equals("codex") || authMode.equals("codexRelay") || authMode.equals("codex-relay");
        if (relay) {
            if (!isEmulator() || !"http".equals(uri.getScheme()) || !"10.0.2.2".equals(uri.getHost()) || uri.getPort() < 1)
                throw new IllegalArgumentException("Codex development relay requires an emulator and http://10.0.2.2:port/v1");
        } else if (!"https".equals(uri.getScheme())) throw new IllegalArgumentException("API connections require HTTPS");
    }
    public static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for") || Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu");
    }
}
