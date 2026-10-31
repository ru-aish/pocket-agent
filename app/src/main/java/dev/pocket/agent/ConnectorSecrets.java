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

/** Credentials encrypted with a non-exportable Android Keystore key. No cloud backup. */
final class ConnectorSecrets {
    private static final Object KEY_LOCK = new Object();
    private final android.content.SharedPreferences prefs;
    ConnectorSecrets(Context c) {prefs=c.getSharedPreferences("chatgpt-mcp-secrets",Context.MODE_PRIVATE);}
    private SecretKey key() throws Exception {
        synchronized (KEY_LOCK) { return loadKey(); }
    }
    private SecretKey loadKey() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias("pocket-agent-chatgpt-mcp")) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("pocket-agent-chatgpt-mcp",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();
        }
        return (SecretKey)store.getKey("pocket-agent-chatgpt-mcp",null);
    }
    synchronized String read(String name) throws Exception {
        String stored=prefs.getString(name,"");if(stored.isEmpty()) return "";
        String[] parts=stored.split(":",2);if(parts.length!=2) throw new IllegalStateException("Invalid credential storage");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    synchronized void write(String name,String value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String encrypted=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!prefs.edit().putString(name,encrypted).commit()) throw new IllegalStateException("Credential storage failed");
    }
    String ngrok() throws Exception {return read("ngrok");}
}
