package dev.pocket.agent;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Separate Android Keystore envelope for voice credentials, independent of the model provider. */
final class VoiceCredentialStore {
    private final Context context;
    private final String alias, preferences, provider;
    VoiceCredentialStore(Context context) { this(context, "deepgram"); }
    VoiceCredentialStore(Context context, String provider) {
        if (!provider.equals("deepgram") && !provider.equals("groq")) throw new IllegalArgumentException("Unsupported voice provider");
        this.context = context.getApplicationContext(); this.provider = provider;
        alias = "pocket-agent-" + provider + "-v1";
        preferences = provider.equals("deepgram") ? "voice-credentials" : "groq-voice-credentials";
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    synchronized boolean configured() { return !read().isEmpty(); }

    synchronized String read() {
        String saved = context.getSharedPreferences(preferences, Context.MODE_PRIVATE)
            .getString("secret", null);
        if (saved == null) return "";
        try {
            JSONObject envelope = new JSONObject(saved);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Protected voice credential is unavailable");
        }
    }

    synchronized void save(String apiKey) throws Exception {
        String value = apiKey == null ? "" : apiKey.trim();
        if (value.isEmpty() || value.length() > 512 || value.contains("\n") || value.contains("\r"))
            throw new IllegalArgumentException("Enter a valid " + (provider.equals("groq") ? "Groq" : "Deepgram") + " API key");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        JSONObject envelope = new JSONObject()
            .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
        if (!context.getSharedPreferences(preferences, Context.MODE_PRIVATE)
            .edit().putString("secret", envelope.toString()).commit())
            throw new IllegalStateException("Unable to save voice credentials");
    }
}
