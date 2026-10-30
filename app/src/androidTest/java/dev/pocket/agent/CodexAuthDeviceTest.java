package dev.pocket.agent;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
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
    @Test public void mcpSecretsStayEncryptedAcrossEveryCredentialWritePath() throws Exception {
        Assume.assumeTrue("Synthetic credential tests require an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());JSONObject saved=credentials.get();
        String bearer="synthetic-mcp-bearer-secret",custom="synthetic-mcp-header-secret";
        try {
            JSONObject server=new JSONObject().put("name","Test MCP").put("url","https://mcp.example.test/mcp")
                    .put("enabled",false).put("authMode","headers").put("headers",new JSONArray()
                            .put(new JSONObject().put("name","X-First").put("value",custom))
                            .put(new JSONObject().put("name","X-Second").put("value","second-secret")));
            credentials.saveMcpServer(server);
            JSONObject first=credentials.mcpServers().getJSONObject(0);
            assertFalse(first.getBoolean("enabled"));assertEquals(2,first.getJSONArray("headers").length());
            credentials.save(new JSONObject().put("authMode","scripted").put("model","scripted"));
            assertEquals(custom,credentials.mcpServers().getJSONObject(0).getJSONArray("headers").getJSONObject(0).getString("value"));
            JSONObject synthetic=account("mcp-refresh-before","mcp-provider");credentials.save(synthetic);
            JSONObject rotated=account("mcp-refresh-after","mcp-provider").put("oldRefreshToken","mcp-refresh-before");
            credentials.updateCodex(rotated);
            assertEquals(2,credentials.mcpServers().getJSONObject(0).getJSONArray("headers").length());
            credentials.signOutCodex();assertEquals("Test MCP",credentials.mcpServers().getJSONObject(0).getString("name"));
            credentials.saveMcpServer(new JSONObject().put("id",first.getString("id")).put("name","Test MCP")
                    .put("url","https://mcp.example.test/mcp").put("authMode","bearer").put("bearerToken",bearer));
            String encrypted=context().getSharedPreferences("provider-secrets",Context.MODE_PRIVATE).getString("encrypted","");
            assertFalse("MCP bearer token leaked to preferences",encrypted.contains(bearer));
            assertFalse("MCP custom header leaked to preferences",encrypted.contains(custom));
        } finally { credentials.save(saved); }
    }
    @Test public void startupDiagnosticsRedactAllMcpAuthenticationFormats() throws Exception {
        JSONObject credentials=new JSONObject().put("mcpServers",new JSONArray()
                .put(new JSONObject().put("bearerToken","bearer-secret"))
                .put(new JSONObject().put("headerValue","legacy-secret"))
                .put(new JSONObject().put("headers",new JSONArray().put(new JSONObject().put("value","custom-secret")))));
        String result=RuntimeHost.redactMcpStartupSecrets("bearer-secret legacy-secret custom-secret",credentials);
        assertEquals("[redacted] [redacted] [redacted]",result);
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
    @Test public void verifiedReasoningLevelsSurviveLowAndAllowLaterReuse() throws Exception {
        assertTrue("Persisted model proof must allow any saved verified level",
                MainActivity.hasPersistedReasoningProof("gpt-6-luna","medium","gpt-6-luna",
                        new String[]{"low","medium","high"}));
        assertFalse("Persisted proof must remain model-bound",
                MainActivity.hasPersistedReasoningProof("other-model","medium","gpt-6-luna",
                        new String[]{"low","medium","high"}));
        Assume.assumeTrue("Synthetic account tests require an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());JSONObject saved=credentials.get();
        try {
            JSONArray levels=new JSONArray().put("low").put("medium").put("high").put("ultra");
            JSONObject synthetic=account("synthetic-level-refresh","levels");
            credentials.save(synthetic);
            JSONObject connected=credentials.get();
            assertFalse("New account credentials must not create reasoning proof",connected.has("reasoningEffortModel"));
            credentials.save(new JSONObject().put("authMode","codex-account").put("model","gpt-6-luna")
                    .put("reasoningEffort","high").put("preferredReasoningEffort","high")
                    .put("reasoningEffortModel","gpt-6-luna").put("reasoningEfforts",levels));
            credentials.save(new JSONObject().put("authMode","codex-account").put("model","gpt-6-luna")
                    .put("reasoningEffort","low").put("preferredReasoningEffort","low")
                    .put("reasoningEffortModel","gpt-6-luna").put("reasoningEfforts",levels));
            JSONObject reopened=new CredentialStore(context()).get();
            assertEquals("Explicit verified low should remain active","low",reopened.optString("reasoningEffort"));
            assertEquals("Verified model proof was lost at low","gpt-6-luna",reopened.optString("reasoningEffortModel"));
            assertTrue("Verified high level was lost at low",
                    reopened.optJSONArray("reasoningEfforts").toString().contains("high"));
            assertFalse("Unsupported reasoning level was not sanitized",
                    reopened.optJSONArray("reasoningEfforts").toString().contains("ultra"));

            JSONObject rotated=account("synthetic-level-refresh-rotated","levels-rotated")
                    .put("oldRefreshToken","synthetic-level-refresh");
            JSONObject refreshed=credentials.updateCodex(rotated);
            assertEquals("Token refresh must retain model proof at low","gpt-6-luna",refreshed.optString("reasoningEffortModel"));
            assertTrue("Token refresh must retain verified levels at low",
                    refreshed.optJSONArray("reasoningEfforts").toString().contains("medium"));

            credentials.save(new JSONObject().put("authMode","codex-account").put("model","gpt-6-luna")
                    .put("reasoningEffort","high").put("preferredReasoningEffort","high"));
            JSONObject high=credentials.get();
            assertEquals("Retained proof should allow high without rediscovery","high",high.optString("reasoningEffort"));

            credentials.save(new JSONObject().put("authMode","codex-account").put("model","gpt-6-luna")
                    .put("reasoningEffort","medium").put("preferredReasoningEffort","medium"));
            JSONObject medium=credentials.get();
            assertEquals("Retained proof should allow medium without rediscovery","medium",medium.optString("reasoningEffort"));
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
    @Test public void reasoningPreferenceRemembersExplicitSelections() throws Exception {
        String[] verified=new String[]{"low","medium","high"};
        assertEquals("Verified Codex low must become the global preference","low",
                MainActivity.reasoningPreferenceAfterSelection("codex-account","gpt-6-luna","low","high",
                        verified,"",new String[0],false));
        assertEquals("Persisted verified Codex low must become the global preference","low",
                MainActivity.reasoningPreferenceAfterSelection("codex-account","gpt-6-luna","low","high",
                        null,"gpt-6-luna",verified,false));
        assertEquals("Explicit manual low must become the preference","low",
                MainActivity.reasoningPreferenceAfterSelection("codex-account","gpt-6-luna","low","high",
                        null,"",new String[0],true));
        assertEquals("Compatible provider selection must become the global preference","high",
                MainActivity.reasoningPreferenceAfterSelection("openai","gpt-5","high","low",
                        null,"",new String[0],false));
        assertEquals("Saving an unchanged low fallback must preserve the prior preference","high",
                MainActivity.reasoningPreferenceAfterSelection("codex-account","gpt-6-luna","low","high",
                        null,"",new String[0],false));
        Assume.assumeTrue("Synthetic account tests require an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());JSONObject saved=credentials.get();
        try {
            JSONObject openai=new JSONObject().put("authMode","openai").put("endpoint","https://api.openai.com/v1")
                    .put("model","gpt-5").put("apiKey","synthetic-provider-key")
                    .put("reasoningEffort","high").put("preferredReasoningEffort","high");
            credentials.save(openai);
            JSONObject before=credentials.get();
            assertEquals("high",before.optString("reasoningEffort"));
            assertEquals("high",before.optString("preferredReasoningEffort"));

            JSONObject codex=account("synthetic-preference-refresh","preference")
                    .put("preferredReasoningEffort",before.optString("preferredReasoningEffort"));
            credentials.save(codex);
            JSONObject clamped=new CredentialStore(context()).get();
            assertEquals("Codex without model proof must stay at low",CredentialStore.DEFAULT_REASONING_EFFORT,clamped.optString("reasoningEffort"));
            assertEquals("Forced Codex low must not erase the global preference","high",clamped.optString("preferredReasoningEffort"));
            assertFalse("Unverified Codex model must not gain reasoning proof",clamped.has("reasoningEffortModel"));
        } finally { credentials.save(saved); }
    }

    @Test public void reasoningChoicesFollowManualOrCatalogSelection() {
        String[] catalog={"medium","high"};
        assertArrayEquals(new String[]{"minimal","low","medium","high","xhigh","max"},
                MainActivity.reasoningChoices(true,catalog,"listed","listed",catalog));
        assertArrayEquals(catalog,MainActivity.reasoningChoices(false,catalog,"listed","",new String[0]));
        assertArrayEquals(catalog,MainActivity.reasoningChoices(false,null,"listed","listed",catalog));
        assertArrayEquals(new String[]{"low"},MainActivity.reasoningChoices(false,new String[0],"listed","listed",catalog));
        assertArrayEquals(new String[]{"low"},MainActivity.reasoningChoices(false,null,"other","listed",catalog));
    }
    @Test public void manualReasoningSurvivesReopenAndRefreshWhileCatalogRemainsRestricted() throws Exception {
        Assume.assumeTrue("Synthetic account tests require an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());JSONObject saved=credentials.get();
        try {
            credentials.save(account("manual-refresh","manual"));
            assertEquals("low",credentials.get().getString("reasoningEffort"));
            credentials.save(new JSONObject().put("authMode","codex-account").put("model","gpt-6-luna")
                    .put("modelSource","manual").put("reasoningEffort","max"));
            JSONObject reopened=new CredentialStore(context()).get();
            assertEquals("manual",reopened.getString("modelSource"));
            assertEquals("max",reopened.getString("reasoningEffort"));
            assertFalse(reopened.has("reasoningEfforts"));
            JSONObject rotated=credentials.updateCodex(account("manual-refresh-next","manual-next")
                    .put("oldRefreshToken","manual-refresh"));
            assertEquals("manual",rotated.getString("modelSource"));
            assertEquals("max",rotated.getString("reasoningEffort"));

            JSONObject catalog=new JSONObject().put("authMode","codex-account").put("model","gpt-6-luna")
                    .put("modelSource","catalog").put("reasoningEffort","medium")
                    .put("reasoningEffortModel","gpt-6-luna").put("reasoningEfforts",new JSONArray().put("medium").put("high"));
            credentials.save(catalog);
            assertEquals("catalog",new CredentialStore(context()).get().getString("modelSource"));
            for(String effort:new String[]{"low","max"}) {
                try { credentials.save(new JSONObject(catalog.toString()).put("reasoningEffort",effort));
                    fail("Catalog accepted unsupported effort: "+effort);
                } catch(IllegalArgumentException expected) { /* Catalog levels remain authoritative. */ }
            }
            // Editing the same model ID manually must clear its old catalog restriction.
            credentials.save(new JSONObject().put("authMode","codex-account").put("model","gpt-6-luna")
                    .put("modelSource","manual").put("reasoningEffort","max"));
            assertEquals("max",new CredentialStore(context()).get().getString("reasoningEffort"));
            assertFalse(credentials.get().has("reasoningEfforts"));
        } finally { credentials.save(saved); }
    }

    @Test public void catalogProofCannotCrossEndpointOrCredentialChanges() throws Exception {
        Assume.assumeTrue("Synthetic credential tests require an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());JSONObject saved=credentials.get();
        try {
            JSONObject base=new JSONObject().put("authMode","openai").put("endpoint","https://a.example.test/v1")
                    .put("apiKey","synthetic-key-a").put("model","shared-model").put("modelSource","manual").put("reasoningEffort","low");
            JSONObject catalog=new JSONObject(base.toString()).put("modelSource","catalog").put("reasoningEffort","high")
                    .put("reasoningEffortModel","shared-model").put("reasoningEfforts",new JSONArray().put("medium").put("high"));
            for(String field:new String[]{"endpoint","apiKey"}) {
                credentials.save(base);credentials.save(catalog);
                JSONObject changed=new JSONObject(catalog.toString()).put(field,
                        field.equals("endpoint")?"https://b.example.test/v1":"synthetic-key-b");
                for(boolean explicitProof:new boolean[]{true,false}) {
                    JSONObject attempt=new JSONObject(changed.toString());
                    if(!explicitProof){attempt.remove("reasoningEffortModel");attempt.remove("reasoningEfforts");}
                    try {credentials.save(attempt);fail("Stale catalog proof crossed "+field);}
                    catch(IllegalArgumentException expected) { /* A new connection requires fresh metadata. */ }
                }
                changed.put("modelSource","manual").put("reasoningEffort","low");
                credentials.save(changed);
                JSONObject reopened=new CredentialStore(context()).get();
                assertEquals("manual",reopened.getString("modelSource"));
                assertEquals("low",reopened.getString("reasoningEffort"));
                assertFalse(reopened.has("reasoningEfforts"));
            }
        } finally {credentials.save(saved);}
    }

    private static View findDialogControl(View root,String value,boolean inputOnly) {
        if(root instanceof TextView && (!inputOnly || root instanceof EditText)) {
            TextView text=(TextView)root;
            if(value.contentEquals(text.getText()) || (text.getHint()!=null && value.contentEquals(text.getHint()))) return root;
        }
        if(root instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++) {
                View found=findDialogControl(group.getChildAt(i),value,inputOnly);
                if(found!=null)return found;
            }
        }
        return null;
    }
    private static Spinner settingsSpinner(MainActivity activity,String name) throws Exception {
        java.lang.reflect.Field field=MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);return (Spinner)field.get(activity);
    }
    private static String pickedEffort(Spinner reasoning) {
        return ((String[])reasoning.getTag())[reasoning.getSelectedItemPosition()];
    }
    private interface MainAction { void run() throws Exception; }
    /** Keeps UI failures on the instrumentation thread so JUnit reports them normally. */
    private static void runOnMainChecked(MainAction action) throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            try {action.run();} catch(Throwable error) {failure.set(error);}
        });
        Throwable error=failure.get();
        if(error instanceof Error)throw (Error)error;
        if(error instanceof Exception)throw (Exception)error;
        if(error!=null)throw new AssertionError(error);
    }
    @Test public void mainThreadAssertionIsReportedOnInstrumentationThread() throws Exception {
        AssertionError expected=new AssertionError("Synthetic UI assertion");
        try {
            runOnMainChecked(()->{throw expected;});
            fail("UI assertion did not propagate to the test thread");
        } catch(AssertionError actual) {assertSame(expected,actual);}
    }
    @Test public void revertingConnectionEditsRestoresCatalogAndPreservesSelectedEffort() throws Exception {
        Assume.assumeTrue("Synthetic credential tests require an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());JSONObject saved=credentials.get();
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        MainActivity activity=null;
        try {
            JSONObject base=new JSONObject().put("authMode","openai").put("endpoint","https://a.example.test/v1")
                    .put("apiKey","synthetic-ui-key").put("model","shared-model").put("modelSource","manual").put("reasoningEffort","low");
            credentials.save(base);
            credentials.save(new JSONObject(base.toString()).put("modelSource","catalog").put("reasoningEffort","high")
                    .put("reasoningEffortModel","shared-model").put("reasoningEfforts",new JSONArray().put("medium").put("high")));
            activity=(MainActivity)instrumentation.startActivitySync(new Intent(context(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity current=activity;
            runOnMainChecked(()->{
                java.lang.reflect.Field advanced=MainActivity.class.getDeclaredField("showAllSettings");
                advanced.setAccessible(true);advanced.setBoolean(current,true);
                java.lang.reflect.Method settings=MainActivity.class.getDeclaredMethod("settings");
                settings.setAccessible(true);settings.invoke(current);
            });
            instrumentation.waitForIdleSync();
            Spinner models=settingsSpinner(current,"modelPicker"),reasoning=settingsSpinner(current,"reasoningPicker");
            View root=models.getRootView();
            EditText endpoint=(EditText)findDialogControl(root,"https://a.example.test/v1",true);
            EditText key=(EditText)findDialogControl(root,"API key / relay token (blank keeps saved value)",true);
            assertNotNull(endpoint);assertNotNull(key);
            runOnMainChecked(()->{
                assertTrue("API connection controls must be visible in Advanced settings",endpoint.isShown()&&key.isShown());
                assertEquals("Advanced settings must start with the saved effort","high",pickedEffort(reasoning));
            });
            for(boolean changeKey:new boolean[]{true,false}) {
                EditText field=changeKey?key:endpoint;
                runOnMainChecked(()->field.setText(changeKey?"temporary-ui-key":"https://b.example.test/v1"));
                instrumentation.waitForIdleSync();
                runOnMainChecked(()->{
                    assertEquals("Connection edits must retain the selected effort","high",pickedEffort(reasoning));
                    assertFalse("Old catalog must be unavailable for a changed connection",models.isEnabled());
                    assertArrayEquals(new String[]{"minimal","low","medium","high","xhigh","max"},(String[])reasoning.getTag());
                    field.setText(changeKey?"":"https://a.example.test/v1");
                });
                instrumentation.waitForIdleSync();
                runOnMainChecked(()->{
                    assertEquals("high",pickedEffort(reasoning));
                    assertTrue("Restored connection must recover its catalog",models.isEnabled());
                    assertArrayEquals(new String[]{"medium","high"},(String[])reasoning.getTag());
                });
            }
            runOnMainChecked(()->{
                View save=findDialogControl(root,"Save",false);assertNotNull(save);save.performClick();
            });
            JSONObject reopened=new CredentialStore(context()).get();
            assertEquals("catalog",reopened.getString("modelSource"));
            assertEquals("high",reopened.getString("reasoningEffort"));
            assertEquals("synthetic-ui-key",reopened.getString("apiKey"));
            assertEquals(new JSONArray().put("medium").put("high").toString(),reopened.getJSONArray("reasoningEfforts").toString());
        } finally {
            if(activity!=null){MainActivity current=activity;runOnMainChecked(current::finish);}
            credentials.save(saved);
        }
    }

}
