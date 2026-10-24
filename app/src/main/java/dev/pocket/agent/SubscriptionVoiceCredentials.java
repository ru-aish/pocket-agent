package dev.pocket.agent;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/**
 * One-attempt Codex subscription OAuth refresh for a voice wake. Reuses the
 * encrypted credential store and compare-and-save token rotation contract.
 * Never logs access tokens, refresh tokens or provider response bodies.
 */
final class SubscriptionVoiceCredentials {
    private static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final long EXPIRY_MARGIN_MS = 60_000;
    private static final int MAX_RESPONSE = 32_768;
    static final class Token {
        final String access, account;
        Token(String access, String account) {
            this.access = access; this.account = account;
        }
    }
    private SubscriptionVoiceCredentials() { }

    static Token load(Context context) throws Exception {
        CredentialStore store = new CredentialStore(context);
        JSONObject values = store.getVoiceCodex();
        if (!CredentialStore.CODEX_ACCOUNT.equals(values.optString("authMode")))
            throw new IllegalStateException("Sign in to a ChatGPT account for subscription voice");
        String account = values.optString("accountId");
        String access = values.optString("accessToken");
        if (account.isEmpty() || access.isEmpty()) throw new IllegalStateException("No saved ChatGPT session");
        if (values.optLong("expiresAt") > System.currentTimeMillis() + EXPIRY_MARGIN_MS)
            return new Token(access, account);
        String oldRefresh = values.optString("refreshToken");
        if (oldRefresh.isEmpty()) throw new IllegalStateException("ChatGPT sign-in expired");
        JSONObject rotated;
        try {
            rotated = refreshOnce(oldRefresh, account);
        } catch (Exception refreshRejected) {
            // Pi may have already rotated this one-use refresh token. Never retry it,
            // but accept a newer encrypted token for the *same* ChatGPT account.
            Token concurrent = concurrentRefresh(store, account, oldRefresh);
            if (concurrent != null) return concurrent;
            throw refreshRejected;
        }
        // Atomic compare-and-save prevents an older refresh from overwriting newer Pi credentials.
        try {
            store.updateVoiceCodex(rotated.put("oldRefreshToken", oldRefresh));
        } catch (IllegalStateException stale) {
            Token concurrent = concurrentRefresh(store, account, oldRefresh);
            if (concurrent != null) return concurrent;
            throw new IllegalStateException("ChatGPT credentials changed; sign in again");
        }
        JSONObject saved = store.getVoiceCodex();
        if (!account.equals(saved.optString("accountId")) ||
            saved.optLong("expiresAt") <= System.currentTimeMillis() + EXPIRY_MARGIN_MS)
            throw new IllegalStateException("ChatGPT refresh could not be saved");
        return new Token(saved.getString("accessToken"), account);
    }

    private static Token concurrentRefresh(CredentialStore store, String account,
                                           String previousRefresh) throws Exception {
        JSONObject latest = store.getVoiceCodex();
        if (!CredentialStore.CODEX_ACCOUNT.equals(latest.optString("authMode")) ||
            !account.equals(latest.optString("accountId")) ||
            previousRefresh.equals(latest.optString("refreshToken")) ||
            latest.optString("accessToken").isEmpty() ||
            latest.optLong("expiresAt") <= System.currentTimeMillis() + EXPIRY_MARGIN_MS)
            return null;
        return new Token(latest.getString("accessToken"), account);
    }

    private static JSONObject refreshOnce(String refresh, String account) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(TOKEN_URL).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setDoOutput(true);
            String form = "grant_type=refresh_token&client_id=" +
                URLEncoder.encode(CLIENT_ID, "UTF-8") + "&refresh_token=" +
                URLEncoder.encode(refresh, "UTF-8");
            byte[] body = form.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
                throw new IllegalStateException("ChatGPT session refresh rejected");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (bytes.size() + n > MAX_RESPONSE)
                        throw new IllegalStateException("ChatGPT token response too large");
                    bytes.write(buffer, 0, n);
                }
                JSONObject response = new JSONObject(bytes.toString("UTF-8"));
                Object seconds = response.opt("expires_in");
                if (!(seconds instanceof Number) ||
                    ((Number) seconds).longValue() <= 0 ||
                    ((Number) seconds).longValue() > 31 * 86400L)
                    throw new IllegalStateException("Invalid ChatGPT token expiry");
                JSONObject rotated = new JSONObject()
                    .put("accessToken", response.getString("access_token"))
                    .put("refreshToken", response.getString("refresh_token"))
                    .put("accountId", account)
                    .put("expiresAt", System.currentTimeMillis() + ((Number) seconds).longValue() * 1000);
                // Validates JWT claims structurally; server authenticates the signature.
                CodexAccountAuth.validateCredentials(rotated, true);
                return rotated;
            }
        } finally {
            connection.disconnect();
        }
    }
}
