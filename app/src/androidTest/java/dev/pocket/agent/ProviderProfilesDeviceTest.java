package dev.pocket.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Provider switching must never discard or expose another provider's secret. */
@RunWith(AndroidJUnit4.class)
public class ProviderProfilesDeviceTest {
    @Test public void desktopEndpointRequiresTailnetAndKeepsTheExistingProviderProfile() throws Exception {
        Assume.assumeTrue("Protected provider test requires an emulator", CredentialStore.isEmulator());
        for (String endpoint : new String[]{"http://100.64.0.1:19238", "http://100.127.255.254:19238", "http://127.0.0.1:19238"})
            CredentialStore.validateEndpoint(endpoint, CredentialStore.CHATGPT_DESKTOP);
        for (String endpoint : new String[]{"http://100.63.0.1:19238", "http://100.128.0.1:19238", "http://example.com:19238", "http://user@100.64.0.1:19238", "http://100.64.0.1:19238/path"}) {
            try { CredentialStore.validateEndpoint(endpoint, CredentialStore.CHATGPT_DESKTOP); fail(endpoint); }
            catch (IllegalArgumentException expected) { }
        }
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE);
        String before = prefs.getString("encrypted", null);
        try {
            assertTrue(prefs.edit().remove("encrypted").commit());
            CredentialStore store = new CredentialStore(context);
            store.save(new JSONObject().put("authMode", CredentialStore.NEBIUS).put("model", CredentialStore.NEBIUS_MODEL).put("apiKey", "fixture-nebius-key"));
            store.save(new JSONObject().put("authMode", CredentialStore.CHATGPT_DESKTOP).put("endpoint", "http://100.64.0.1:19238")
                    .put("model", "gpt-6-thinking").put("reasoningEffort", "extended").put("modelSource", "manual"));
            assertEquals("extended", store.get().getString("reasoningEffort"));
            assertEquals("http://100.64.0.1:19238", store.get().getString("endpoint"));
            assertEquals("fixture-nebius-key", store.getProviderProfile(CredentialStore.NEBIUS).getString("apiKey"));
            assertEquals("", store.get().optString("apiKey"));
            store.removeKeyProvider(CredentialStore.CHATGPT_DESKTOP);
            assertEquals("scripted", store.get().getString("authMode"));
            assertEquals("", store.getProviderProfile(CredentialStore.CHATGPT_DESKTOP).optString("endpoint"));
            assertEquals("fixture-nebius-key", store.getProviderProfile(CredentialStore.NEBIUS).getString("apiKey"));
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            if (before == null) editor.remove("encrypted"); else editor.putString("encrypted", before);
            assertTrue(editor.commit());
        }
    }

    private static String encode(JSONObject value) {
        return Base64.encodeToString(value.toString().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static JSONObject account(String refresh, String nonce) throws Exception {
        long expiry = System.currentTimeMillis() / 1000 + 3600;
        String access = encode(new JSONObject().put("alg", "RS256")) + "."
                + encode(new JSONObject().put("exp", expiry).put("nonce", nonce)
                .put("https://api.openai.com/auth", new JSONObject().put("chatgpt_account_id", "saved-account")))
                + ".c3ludGhldGlj";
        return new JSONObject().put("authMode", CredentialStore.CODEX_ACCOUNT)
                .put("model", CredentialStore.CODEX_MODEL)
                .put("accessToken", access).put("refreshToken", refresh)
                .put("accountId", "saved-account").put("expiresAt", expiry * 1000);
    }

    @Test public void switchingRestoresKeysCatalogAndRotatedAccountWithoutExposingProfiles() throws Exception {
        Assume.assumeTrue("Protected provider test requires an emulator", CredentialStore.isEmulator());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE);
        String before = prefs.getString("encrypted", null);
        try {
            assertTrue(prefs.edit().remove("encrypted").commit());
            CredentialStore store = new CredentialStore(context);
            store.save(new JSONObject().put("authMode", "openai")
                    .put("endpoint", "https://api.openai.com/v1").put("apiKey", "first-secret-key")
                    .put("model", "saved-model").put("modelSource", "manual"));
            store.save(new JSONObject().put("authMode", "openai")
                    .put("endpoint", "https://api.openai.com/v1").put("apiKey", "")
                    .put("model", "saved-model").put("modelSource", "catalog")
                    .put("reasoningEffort", "high").put("reasoningEffortModel", "saved-model")
                    .put("reasoningEfforts", new JSONArray().put("medium").put("high")));
            String openaiRevision = store.get().getString("providerRevision");

            store.save(new JSONObject().put("authMode", CredentialStore.NEBIUS)
                    .put("model", CredentialStore.NEBIUS_MODEL).put("apiKey", "nebius-secret-key"));
            store.save(account("first-refresh", "first"));
            assertEquals("first-secret-key", store.getProviderProfile("openai").getString("apiKey"));
            assertEquals("nebius-secret-key", store.getProviderProfile(CredentialStore.NEBIUS).getString("apiKey"));
            assertFalse("Runtime must see only active credentials", store.get().has("providerProfiles"));
            assertFalse("Runtime must not receive an inactive API key", store.get().has("apiKey"));

            store.save(new JSONObject().put("authMode", CredentialStore.NEBIUS)
                    .put("model", CredentialStore.NEBIUS_MODEL).put("apiKey", ""));
            assertEquals("nebius-secret-key", store.get().getString("apiKey"));
            store.save(new JSONObject().put("authMode", "openai")
                    .put("endpoint", "https://api.openai.com/v1").put("model", "saved-model")
                    .put("modelSource", "catalog").put("reasoningEffort", "high")
                    .put("reasoningEffortModel", "saved-model")
                    .put("reasoningEfforts", new JSONArray().put("medium").put("high")));
            JSONObject restored = store.get();
            assertEquals("first-secret-key", restored.getString("apiKey"));
            assertEquals("high", restored.getString("reasoningEffort"));
            assertEquals(openaiRevision, restored.getString("providerRevision"));

            store.updateVoiceCodex(account("second-refresh", "second")
                    .put("oldRefreshToken", "first-refresh"));
            store.save(new JSONObject().put("authMode", CredentialStore.CODEX_ACCOUNT)
                    .put("model", CredentialStore.CODEX_MODEL));
            assertEquals("second-refresh", store.get().getString("refreshToken"));
            store.save(new JSONObject().put("authMode", CredentialStore.NEBIUS)
                    .put("model", CredentialStore.NEBIUS_MODEL));
            assertTrue("Signing out a cached account must also clear shared voice", store.signOutCodex());
            assertEquals(0, store.getProviderProfile(CredentialStore.CODEX_ACCOUNT).length());
            assertEquals(0, store.getVoiceCodex().length());
            assertEquals("nebius-secret-key", store.get().getString("apiKey"));
            try {
                store.save(new JSONObject().put("authMode", CredentialStore.CODEX_ACCOUNT)
                        .put("model", CredentialStore.CODEX_MODEL));
                fail("Signed-out account must require a new login");
            } catch (IllegalArgumentException expected) { /* Sign-in required. */ }

            String encrypted = prefs.getString("encrypted", "");
            assertFalse(encrypted.contains("first-secret-key"));
            assertFalse(encrypted.contains("nebius-secret-key"));
            assertFalse(encrypted.contains("second-refresh"));
        } finally {
            SharedPreferences.Editor restore = prefs.edit();
            if (before == null) restore.remove("encrypted");
            else restore.putString("encrypted", before);
            assertTrue(restore.commit());
        }
    }
    @Test public void blankKeyDoesNotFollowAnEndpointChange() throws Exception {
        Assume.assumeTrue("Protected provider test requires an emulator", CredentialStore.isEmulator());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE);
        String before = prefs.getString("encrypted", null);
        try {
            assertTrue(prefs.edit().remove("encrypted").commit());
            CredentialStore store = new CredentialStore(context);
            store.save(new JSONObject().put("authMode", "openai")
                    .put("endpoint", "https://original.example.test/v1")
                    .put("apiKey", "original-host-secret").put("model", "manual-model"));
            JSONObject profile = store.getProviderProfile("openai");
            assertEquals("original-host-secret", MainActivity.savedProviderKeyForEndpoint(profile,
                    "openai", "https://original.example.test/v1", ""));
            assertEquals("", MainActivity.savedProviderKeyForEndpoint(profile,
                    "openai", "https://different.example.test/v1", ""));
            store.save(new JSONObject().put("authMode", "openai")
                    .put("endpoint", "https://different.example.test/v1")
                    .put("apiKey", "").put("model", "manual-model"));
            assertEquals("A new endpoint must not inherit the old host's key", "",
                    store.get().getString("apiKey"));
        } finally {
            SharedPreferences.Editor restore = prefs.edit();
            if (before == null) restore.remove("encrypted");
            else restore.putString("encrypted", before);
            assertTrue(restore.commit());
        }
    }

}
