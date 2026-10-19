package dev.pocket.agent;

import android.content.Context;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic local account checks: no sign-in, refresh request or inference. */
@RunWith(AndroidJUnit4.class)
public class CodexAuthDeviceTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static String encoded(JSONObject value) {
        return Base64.encodeToString(value.toString().getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
    }
    private static JSONObject account(String refresh,String nonce) throws Exception {
        long expiry=System.currentTimeMillis()/1000+3600;
        String jwt=encoded(new JSONObject().put("alg","RS256").put("typ","JWT"))+"."
            +encoded(new JSONObject().put("exp",expiry).put("nonce",nonce).put("https://api.openai.com/auth",new JSONObject().put("chatgpt_account_id","synthetic-device-account")))+".c3ludGhldGlj";
        return new JSONObject().put("authMode","codex-account").put("model","gpt-6-luna").put("accessToken",jwt).put("refreshToken",refresh).put("accountId","synthetic-device-account").put("expiresAt",expiry*1000);
    }
    private void assertProtected(JSONObject account) throws Exception {
        String encrypted=context().getSharedPreferences("provider-secrets",Context.MODE_PRIVATE).getString("encrypted",null);
        assertNotNull("Protected credential envelope is missing",encrypted);
        JSONObject envelope=new JSONObject(encrypted);
        assertTrue("AES-GCM nonce is missing",envelope.has("iv"));assertTrue("Encrypted payload is missing",envelope.has("data"));
        for(String field:new String[]{"accessToken","refreshToken","accountId"})
            assertFalse("Credential plaintext leaked to preferences",encrypted.contains(account.getString(field)));
        assertEquals("Only the encrypted envelope may be retained",1,context().getSharedPreferences("provider-secrets",Context.MODE_PRIVATE).getAll().size());
    }
    /** Failure-only runner cleanup, even when the live test never reached its finally block. */
    @Test public void clearProvisionedAccount() throws Exception {
        Assume.assumeTrue("Account cleanup requires an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());credentials.signOutCodex();
        assertFalse("Account remained signed in after test cleanup",credentials.get().optString("authMode").equals("codex-account"));
    }
    @Test public void importEncryptAndSignOut() throws Exception {
        Assume.assumeTrue("Synthetic account tests require an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());JSONObject saved=credentials.get();
        try {
            JSONObject synthetic=account("synthetic-import-refresh","import");
            JSONObject source=new JSONObject().put("tokens",new JSONObject().put("access_token",synthetic.getString("accessToken")).put("refresh_token",synthetic.getString("refreshToken")).put("account_id",synthetic.getString("accountId")));
            JSONObject imported=CodexAccountAuth.readImport(new ByteArrayInputStream(source.toString().getBytes(StandardCharsets.UTF_8)));
            assertEquals("JWT expiry must be inferred in milliseconds",synthetic.getLong("expiresAt"),imported.getLong("expiresAt"));
            imported.put("authMode","codex-account").put("model","gpt-6-luna");credentials.save(imported);
            JSONObject restored=new CredentialStore(context()).get();
            assertTrue("Imported access token did not round trip",synthetic.getString("accessToken").equals(restored.optString("accessToken")));
            assertTrue("Imported refresh token did not round trip",synthetic.getString("refreshToken").equals(restored.optString("refreshToken")));
            assertFalse("Account mode retained an endpoint override",restored.has("endpoint"));assertProtected(synthetic);
            credentials.signOutCodex();JSONObject out=credentials.get();assertEquals("scripted",out.optString("authMode"));
            for(String field:new String[]{"accessToken","refreshToken","accountId","expiresAt","idToken"})assertFalse("Sign-out retained an account field",out.has(field));
            System.out.println("CODEX_AUTH_RESULT="+new JSONObject().put("case","importEncryptAndSignOut").put("synthetic",true).put("imported",true).put("encryptedAtRest",true).put("signedOut",true));
        } finally { credentials.save(saved); }
    }
    @Test public void saveRejectsReasoningUnsupportedByDiscoveredModel() throws Exception {
        Assume.assumeTrue("Synthetic account tests require an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());JSONObject saved=credentials.get();
        try {
            JSONObject initial=account("synthetic-reasoning-refresh","reasoning").put("reasoning","low");
            credentials.save(initial);
            JSONObject invalid=new JSONObject(credentials.get().toString())
                    .put("reasoning","xhigh")
                    .put("reasoningModel","gpt-6-luna")
                    .put("reasoningLevels",new JSONArray().put("low"));
            boolean rejected=false;
            try { credentials.save(invalid); }
            catch(IllegalArgumentException expected) { rejected=true; }
            assertTrue("Unsupported reasoning was persisted for the discovered model",rejected);
            assertEquals("Rejected reasoning changed protected settings","low",credentials.get().optString("reasoning"));
        } finally { credentials.save(saved); }
    }
    @Test public void rotationCompareAndSaveRejectsStaleAndSignedOut() throws Exception {
        Assume.assumeTrue("Synthetic account tests require an emulator",CredentialStore.isEmulator());
        CredentialStore first=new CredentialStore(context()),second=new CredentialStore(context());JSONObject saved=first.get();
        try {
            JSONObject initial=account("synthetic-refresh-initial","initial");first.save(initial);
            JSONObject rotated=account("synthetic-refresh-rotated","rotated").put("oldRefreshToken",initial.getString("refreshToken"));
            JSONObject result=second.updateCodex(rotated);
            assertTrue("Rotation did not return the saved token",result.optString("refreshToken").equals(rotated.getString("refreshToken")));assertProtected(rotated);
            boolean staleRejected=false;
            try { first.updateCodex(account("synthetic-stale-overwrite","stale").put("oldRefreshToken",initial.getString("refreshToken"))); }
            catch(IllegalStateException expected) { staleRejected=true; }
            assertTrue("Stale rotation overwrote newer account credentials",staleRejected);
            assertTrue("Stale rotation changed the retained token",first.get().optString("refreshToken").equals(rotated.getString("refreshToken")));
            first.signOutCodex();boolean signedOutRejected=false;
            try { second.updateCodex(account("synthetic-resurrection","resurrect").put("oldRefreshToken",rotated.getString("refreshToken"))); }
            catch(IllegalStateException expected) { signedOutRejected=true; }
            assertTrue("Late refresh resurrected a signed-out account",signedOutRejected);assertEquals("scripted",first.get().optString("authMode"));
            System.out.println("CODEX_AUTH_RESULT="+new JSONObject().put("case","rotationCompareAndSaveRejectsStaleAndSignedOut").put("synthetic",true).put("rotationSaved",true).put("staleRejected",true).put("signedOutRefreshRejected",true));
        } finally { first.save(saved); }
    }
}
