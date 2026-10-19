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
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Allowlisted credentials encrypted with a non-exportable Android Keystore key. */
public final class CredentialStore {
    private static final String ALIAS = "pocket-agent-provider-v1";
    // Runtime refresh and the settings Activity use different store instances.
    private static final Object LOCK = new Object();
    public static final String CODEX_ACCOUNT = "codex-account";
    public static final String CODEX_MODEL = "gpt-6-luna";
    public static final String DEFAULT_REASONING = "low";
    public static final String OPENCODE = "opencode";
    public static final String OPENCODE_ENDPOINT = "https://opencode.ai/inference/openai/v1";
    public static final String OPENCODE_MODEL = "big-pickle";
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
        JSONObject current = read(); boolean changed = false;
        if (!current.has("providerRevision")) { current.put("providerRevision", UUID.randomUUID().toString()); changed = true; }
        if (!current.has("reasoning")) { current.put("reasoning", DEFAULT_REASONING); changed = true; }
        if (changed) write(current);
        return current;
    } }
    private JSONObject read() throws Exception {
        String saved = context.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE).getString("encrypted", null);
        if (saved == null) return new JSONObject().put("authMode", "scripted").put("endpoint", "https://api.openai.com/v1").put("model", "gpt-4.1-mini").put("reasoning", DEFAULT_REASONING).put("providerRevision", BUILTIN_PROVIDER_REVISION);
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
            String endpoint = fixedEndpoint(mode, settings.optString("endpoint"));
            validateEndpoint(endpoint, mode);
            if (OPENCODE.equals(mode) && settings.optString("apiKey").trim().isEmpty()) throw new IllegalArgumentException("Enter an OpenCode Console service-account key");
            if (NEBIUS.equals(mode) && settings.optString("apiKey").trim().isEmpty()) throw new IllegalArgumentException("Enter a Nebius API key");
            JSONObject allowed = new JSONObject().put("authMode", mode)
                    .put("reasoning", reasoning(settings.optString("reasoning", current.optString("reasoning", DEFAULT_REASONING))));
            if (CODEX_ACCOUNT.equals(mode)) {
                JSONObject source = settings.has("accessToken") ? settings : current;
                if (!CODEX_ACCOUNT.equals(source.optString("authMode")) && !settings.has("accessToken")) throw new IllegalArgumentException("Sign in or import an account first");
                JSONObject account = CodexAccountAuth.validateCredentials(source, false);
                for (String field : new String[]{"accessToken", "refreshToken", "accountId", "expiresAt", "idToken"})
                    if (account.has(field)) allowed.put(field, account.get(field));
                allowed.put("model", model(settings.optString("model", CODEX_MODEL)));
            } else {
                allowed.put("endpoint", endpoint);
                allowed.put("apiKey", settings.optString("apiKey"));
                allowed.put("model", model(settings.optString("model")));
            }
            allowed.put("providerRevision", providerRevision(current, allowed));
            write(allowed);
        }
    }
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
            JSONObject allowed = new JSONObject().put("authMode", CODEX_ACCOUNT).put("model", model(current.optString("model", CODEX_MODEL)))
                    .put("reasoning", reasoning(current.optString("reasoning", DEFAULT_REASONING)));
            for (String field : new String[]{"accessToken", "refreshToken", "accountId", "expiresAt", "idToken"})
                if (account.has(field)) allowed.put(field, account.get(field));
            String revision = current.optString("providerRevision");
            allowed.put("providerRevision", revision.isEmpty() ? UUID.randomUUID().toString() : revision);
            write(allowed);
            return new JSONObject(allowed.toString());
        }
    }
    public void signOutCodex() throws Exception {
        synchronized (LOCK) {
            JSONObject current = read();
            if (CODEX_ACCOUNT.equals(current.optString("authMode"))) write(new JSONObject().put("authMode", "scripted").put("model", "scripted").put("reasoning", DEFAULT_REASONING).put("endpoint", "https://api.openai.com/v1").put("providerRevision", UUID.randomUUID().toString()));
        }
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
    private static String reasoning(String value) {
        if (value == null || !value.matches("minimal|low|medium|high|xhigh")) throw new IllegalArgumentException("Choose a valid reasoning effort");
        return value;
    }
    private static String model(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:/+@-]{1,160}")) throw new IllegalArgumentException("Enter a valid model name");
        return value;
    }
    private static String fixedEndpoint(String authMode, String endpoint) {
        if (OPENCODE.equals(authMode)) return OPENCODE_ENDPOINT;
        if (NEBIUS.equals(authMode)) return NEBIUS_ENDPOINT;
        return endpoint;
    }
    public static void validateEndpoint(String endpoint, String authMode) throws Exception {
        if (authMode.equals("scripted") || authMode.equals(CODEX_ACCOUNT)) return;
        if (!authMode.equals("openai") && !authMode.equals("api-key") && !authMode.equals(OPENCODE) && !authMode.equals(NEBIUS) && !authMode.equals("codex") && !authMode.equals("codexRelay") && !authMode.equals("codex-relay")) throw new IllegalArgumentException("Unknown authentication mode");
        URI uri;
        try { uri = new URI(endpoint); } catch (Exception invalid) { throw new IllegalArgumentException("Enter a valid API base URL"); }
        if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null || uri.getHost() == null)
            throw new IllegalArgumentException("Endpoint must be an API base URL without credentials, query or fragment");
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
