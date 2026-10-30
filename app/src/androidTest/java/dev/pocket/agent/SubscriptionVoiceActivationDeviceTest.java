package dev.pocket.agent;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Locale;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.webrtc.PeerConnectionFactory;
import static org.junit.Assert.*;

/** Disposable-emulator regressions for the dedicated Codex voice conversation. */
@RunWith(AndroidJUnit4.class)
public final class SubscriptionVoiceActivationDeviceTest {
    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static String base64(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static JSONObject fakeAccount(String refresh) throws Exception {
        return fakeAccount("emulator-voice-test", refresh);
    }

    private static JSONObject fakeAccount(String account, String refresh) throws Exception {
        String header = base64(new JSONObject().put("alg", "RS256").toString());
        JSONObject claims = new JSONObject()
                .put("https://api.openai.com/auth",
                        new JSONObject().put("chatgpt_account_id", account))
                .put("exp", System.currentTimeMillis() / 1000L + 7200L);
        return new JSONObject().put("accessToken", header + "." + base64(claims.toString()) + ".c2ln")
                .put("refreshToken", refresh).put("accountId", account)
                .put("expiresAt", System.currentTimeMillis() + 3_600_000L);
    }

    @Test public void voiceLoginSurvivesChatProviderSwitchAndRotation() throws Exception {
        Context ctx = context();
        SharedPreferences protectedPrefs = ctx.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE);
        String prior = protectedPrefs.getString("encrypted", null);
        try {
            assertTrue(protectedPrefs.edit().remove("encrypted").commit());
            CredentialStore store = new CredentialStore(ctx);
            store.saveVoiceCodex(fakeAccount("voice-refresh-a"));
            store.save(new JSONObject().put("authMode", "scripted")
                    .put("model", "scripted").put("endpoint", "https://api.openai.com/v1"));
            assertEquals("scripted", store.get().getString("authMode"));
            assertFalse(store.get().has("subscriptionVoiceAccount"));
            assertEquals("voice-refresh-a", store.getVoiceCodex().getString("refreshToken"));
            store.updateVoiceCodex(fakeAccount("voice-refresh-b")
                    .put("oldRefreshToken", "voice-refresh-a"));
            assertEquals("voice-refresh-b", store.getVoiceCodex().getString("refreshToken"));
            assertEquals("scripted", store.get().getString("authMode"));
            store.signOutVoiceCodex();
            assertEquals(0, store.getVoiceCodex().length());
        } finally {
            SharedPreferences.Editor restore = protectedPrefs.edit();
            if (prior == null) restore.remove("encrypted"); else restore.putString("encrypted", prior);
            assertTrue(restore.commit());
        }
    }

    @Test public void separateVoiceAccountSurvivesCodexChatSaveAndDisconnect() throws Exception {
        Context ctx = context();
        SharedPreferences prefs = ctx.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE);
        String prior = prefs.getString("encrypted", null);
        try {
            assertTrue(prefs.edit().remove("encrypted").commit());
            CredentialStore store = new CredentialStore(ctx);
            store.save(fakeAccount("chat-account", "chat-refresh").put("authMode", CredentialStore.CODEX_ACCOUNT)
                .put("model", CredentialStore.CODEX_MODEL));
            assertTrue(store.voiceSharesChatAccount());
            store.saveVoiceCodex(fakeAccount("voice-account", "voice-refresh"));
            store.save(new JSONObject().put("authMode", CredentialStore.CODEX_ACCOUNT)
                .put("model", CredentialStore.CODEX_MODEL));
            assertFalse(store.voiceSharesChatAccount());
            assertEquals("voice-account", store.getVoiceCodex().getString("accountId"));
            store.signOutVoiceCodex();
            assertEquals(CredentialStore.CODEX_ACCOUNT, store.get().getString("authMode"));
            assertEquals("chat-account", store.get().getString("accountId"));
            assertEquals("chat-refresh", store.get().getString("refreshToken"));
            // With the separate login removed, voice falls back to chat's shared login.
            assertTrue(store.voiceSharesChatAccount());
            store.signOutVoiceCodex();
            assertEquals("scripted", store.get().getString("authMode"));
            assertEquals(0, store.getVoiceCodex().length());
        } finally {
            SharedPreferences.Editor restore = prefs.edit();
            if (prior == null) restore.remove("encrypted"); else restore.putString("encrypted", prior);
            assertTrue(restore.commit());
        }
    }

    @Test public void failureClassificationNeverPrintsRemoteErrorText() {
        assertEquals("ChatGPT rejected voice authentication",
                SubscriptionVoiceFailureStore.safeReason("Subscription call rejected (HTTP 401)"));
        assertEquals("Native voice audio failed to initialize",
                SubscriptionVoiceFailureStore.safeReason("Unable to initialize native audio"));
        assertEquals("Subscription voice setup failed",
                SubscriptionVoiceFailureStore.safeReason("token=do-not-log-this"));
        assertEquals("ChatGPT rejected voice authentication", SubscriptionVoiceFailureStore.safeReason(
            SubscriptionVoiceFailureStore.safeReason("Subscription call rejected (HTTP 401)")));
    }

    @Test public void chatSignOutPreservesIndependentVoiceAndIdentifiesSharedLogout() throws Exception {
        SharedPreferences prefs = context().getSharedPreferences("provider-secrets", Context.MODE_PRIVATE);
        String prior = prefs.getString("encrypted", null);
        try {
            assertTrue(prefs.edit().remove("encrypted").commit());
            CredentialStore store = new CredentialStore(context());
            store.save(fakeAccount("chat-account", "chat-refresh").put("authMode", CredentialStore.CODEX_ACCOUNT)
                .put("model", CredentialStore.CODEX_MODEL));
            store.saveVoiceCodex(fakeAccount("voice-account", "voice-refresh"));
            assertFalse("Independent voice must not be disconnected by chat sign-out", store.signOutCodex());
            assertEquals("scripted", store.get().getString("authMode"));
            assertEquals("voice-account", store.getVoiceCodex().getString("accountId"));
            assertEquals("voice-refresh", store.getVoiceCodex().getString("refreshToken"));
            assertFalse("Non-Codex chat sign-out must leave voice alone", store.signOutCodex());
            assertEquals("voice-refresh", store.getVoiceCodex().getString("refreshToken"));
            store.signOutVoiceCodex();
            store.save(fakeAccount("shared-account", "shared-refresh").put("authMode", CredentialStore.CODEX_ACCOUNT)
                .put("model", CredentialStore.CODEX_MODEL));
            assertTrue("Removing the shared login must tell the UI to disconnect voice", store.signOutCodex());
            assertEquals("scripted", store.get().getString("authMode"));
            assertEquals(0, store.getVoiceCodex().length());
        } finally {
            SharedPreferences.Editor restore = prefs.edit();
            if (prior == null) restore.remove("encrypted"); else restore.putString("encrypted", prior);
            assertTrue(restore.commit());
        }
    }

    @Test public void sharedTokenRotationsKeepChatAndVoiceInSyncInEitherOrder() throws Exception {
        SharedPreferences prefs = context().getSharedPreferences("provider-secrets", Context.MODE_PRIVATE);
        String prior = prefs.getString("encrypted", null);
        try {
            assertTrue(prefs.edit().remove("encrypted").commit());
            CredentialStore store = new CredentialStore(context());
            store.save(fakeAccount("shared-account", "refresh-one").put("authMode", CredentialStore.CODEX_ACCOUNT)
                .put("model", CredentialStore.CODEX_MODEL));
            String revision = store.get().getString("providerRevision");
            store.updateCodex(fakeAccount("shared-account", "refresh-two").put("oldRefreshToken", "refresh-one"));
            assertEquals("refresh-two", store.getVoiceCodex().getString("refreshToken"));
            store.updateVoiceCodex(fakeAccount("shared-account", "refresh-three").put("oldRefreshToken", "refresh-two"));
            assertEquals("refresh-three", store.get().getString("refreshToken"));
            assertEquals(revision, store.get().getString("providerRevision"));
            try {
                store.updateCodex(fakeAccount("shared-account", "stale-refresh").put("oldRefreshToken", "refresh-two"));
                fail("A stale Pi refresh must not overwrite the newer voice rotation");
            } catch (IllegalStateException expected) { }
            assertEquals("refresh-three", store.getVoiceCodex().getString("refreshToken"));
        } finally {
            SharedPreferences.Editor restore = prefs.edit();
            if (prior == null) restore.remove("encrypted"); else restore.putString("encrypted", prior);
            assertTrue(restore.commit());
        }
    }

    private static WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWebView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String evaluateJavascript(WebView webView, String script) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                webView.evaluateJavascript(script, value -> {
                    result.set(value);
                    completed.countDown();
                }));
        assertTrue("WebView JavaScript did not complete", completed.await(5, TimeUnit.SECONDS));
        return result.get();
    }

    @Test public void systemBackReturnsThroughMenusBeforeLeavingChat() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Activity activity = instrumentation.startActivitySync(
                new Intent(context(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            WebView web = findWebView(activity.getWindow().getDecorView());
            assertNotNull(web);
            long until = SystemClock.elapsedRealtime() + 12_000L;
            while (!"true".equals(evaluateJavascript(web, "typeof window.pocketBack === 'function'"))
                    && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
            assertEquals("true", evaluateJavascript(web,
                    "(()=>{document.querySelector('[data-action=settings]').click();return !document.querySelector('#settings-screen').hidden;})()"));
            evaluateJavascript(web, "Array.from(document.querySelectorAll('.settings-row')).find(n=>n.textContent.includes('Background work')).click()");
            evaluateJavascript(web, "Array.from(document.querySelectorAll('.settings-row')).find(n=>n.textContent.includes('Schedules, subagents')).click()");
            assertEquals("false", evaluateJavascript(web, "document.querySelector('#activity-screen').hidden"));
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            assertEquals("true", evaluateJavascript(web, "!document.querySelector('#settings-screen').hidden && document.querySelector('#settings-screen').textContent.includes('Let Pocket do the legwork.')"));
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            assertEquals("true", evaluateJavascript(web, "!document.querySelector('#settings-screen').hidden && document.querySelector('#settings-screen').textContent.includes('Your assistant')"));
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            assertEquals("true", evaluateJavascript(web, "document.querySelector('#settings-screen').hidden && document.querySelector('#activity-screen').hidden"));
            assertFalse(activity.isFinishing());
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void manualVoiceTapClearsRetainedInvitationBeforeRequestingPermissions() throws Exception {
        Context ctx=context();
        SharedPreferences secrets=ctx.getSharedPreferences("provider-secrets",Context.MODE_PRIVATE);
        String prior=secrets.getString("encrypted",null);
        try {
            assertTrue(secrets.edit().remove("encrypted").commit());
            ctx.stopService(new Intent(ctx,SubscriptionVoiceService.class));
            Activity activity=InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            try {
                java.lang.reflect.Field retained=MainActivity.class.getDeclaredField("pendingVoiceInvitation");retained.setAccessible(true);
                java.lang.reflect.Method tap=MainActivity.class.getDeclaredMethod("toggleVoiceConversation");tap.setAccessible(true);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                    try {retained.set(activity,"stale-invitation-id");tap.invoke(activity);}
                    catch(Exception failure){throw new AssertionError(failure);}
                });
                assertNull("A manual tap must never reuse an old invitation",retained.get(activity));
            } finally {InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);}
        } finally {
            ctx.stopService(new Intent(ctx,SubscriptionVoiceService.class));
            SharedPreferences.Editor restore=secrets.edit();if(prior==null)restore.remove("encrypted");else restore.putString("encrypted",prior);assertTrue(restore.commit());
        }
    }

    @Test public void mainChatVoiceControlOpensSetupWhenAccountIsMissing() throws Exception {
        Context ctx = context();
        SharedPreferences protectedPrefs = ctx.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE);
        String prior = protectedPrefs.getString("encrypted", null);
        try {
            ctx.stopService(new Intent(ctx, SubscriptionVoiceService.class));
            assertTrue(protectedPrefs.edit().remove("encrypted").commit());
            assertTrue("Test setup must leave the voice account disconnected",
                    new CredentialStore(ctx).getVoiceCodex().optString("accessToken").isEmpty());
            SubscriptionVoiceFailureStore.ready(ctx);
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(ctx.getPackageName(), Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33)
                InstrumentationRegistry.getInstrumentation().getUiAutomation()
                        .grantRuntimePermission(ctx.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
            // A prior permission dialog can leave a task that NEW_TASK reuses without creating an Activity.
            Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                    new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            try {
                WebView webView = findWebView(activity.getWindow().getDecorView());
                assertNotNull("Main chat WebView must be present", webView);
                String domReady = "false";
                long domUntil = SystemClock.elapsedRealtime() + 12_000L;
                while (SystemClock.elapsedRealtime() < domUntil) {
                    domReady = evaluateJavascript(webView, "!!document.querySelector('#message')");
                    if ("true".equals(domReady)) break;
                    SystemClock.sleep(100L);
                }
                assertEquals("Main chat DOM must finish loading", "true", domReady);
                assertEquals("true", evaluateJavascript(webView,
                        "(()=>{localStorage.removeItem('pocket-native-draft');const input=document.querySelector('#message');if(!input)return false;input.value='';input.dispatchEvent(new Event('input',{bubbles:true}));return true;})()"));
                String expectedLabel = JSONObject.quote("Start Codex voice");
                String label = "";
                long pageUntil = SystemClock.elapsedRealtime() + 12_000L;
                while (SystemClock.elapsedRealtime() < pageUntil) {
                    label = evaluateJavascript(webView,
                            "document.querySelector('#send')?.getAttribute('aria-label') || ''");
                    if (expectedLabel.equals(label)) break;
                    SystemClock.sleep(100);
                }
                assertEquals("Main chat must expose its own Codex voice button", expectedLabel, label);
                assertEquals("true", evaluateJavascript(webView,
                        "(()=>{const button=document.querySelector('#send');if(!button)return false;button.click();return true;})()"));
                boolean setupVisible = false;
                String setupState = "false";
                long setupUntil = SystemClock.elapsedRealtime() + 5_000L;
                while (SystemClock.elapsedRealtime() < setupUntil) {
                    setupState = evaluateJavascript(webView,
                            "(()=>{const screen=document.querySelector('#settings-screen');return !!screen&&!screen.hidden&&screen.textContent.includes('A conversation, out loud.');})()");
                    if ("true".equals(setupState)) { setupVisible = true; break; }
                    SystemClock.sleep(50L);
                }
                assertTrue("The missing voice account should route to setup, not start a broken session; state="
                        + setupState, setupVisible);
                assertFalse("No voice session should start without a connected account",
                        SubscriptionVoiceService.isActive());
                assertEquals("Setup guidance should not be recorded as a failed voice activation",
                        "", SubscriptionVoiceFailureStore.lastStatus(ctx));
                Intent assistant = SubscriptionVoiceSettings.wakeIntent(ctx);
                assertEquals(VoiceListeningService.class.getName(), assistant.getComponent().getClassName());
            } finally {
                activity.finish();
            }
        } finally {
            ctx.stopService(new Intent(ctx, SubscriptionVoiceService.class));
            SharedPreferences.Editor restore = protectedPrefs.edit();
            if (prior == null) restore.remove("encrypted"); else restore.putString("encrypted", prior);
            assertTrue(restore.commit());
            SubscriptionVoiceFailureStore.ready(ctx);
            ctx.getSystemService(NotificationManager.class).cancel(SubscriptionVoiceService.FAILURE_NOTIFICATION_ID);
        }
    }

    @Test public void oldBackendPreferenceCannotReplaceAssistantOrStartAnUnrequestedCall() {
        Context ctx = context();
        SharedPreferences old = ctx.getSharedPreferences("voice_subscription_v1", Context.MODE_PRIVATE);
        boolean had = old.contains("enabled"); boolean prior = old.getBoolean("enabled", false);
        try {
            assertTrue(old.edit().putBoolean("enabled", true).commit());
            assertEquals(VoiceListeningService.class.getName(),
                SubscriptionVoiceSettings.wakeIntent(ctx).getComponent().getClassName());
            assertEquals(SubscriptionVoiceService.class.getName(),
                SubscriptionVoiceSettings.conversationIntent(ctx).getComponent().getClassName());
        } finally {
            SharedPreferences.Editor restore = old.edit();
            if (had) restore.putBoolean("enabled", prior); else restore.remove("enabled");
            assertTrue(restore.commit());
        }
    }

    @Test public void webRtcNetworkMonitorPermissionIsGranted() {
        assertEquals("WebRTC network monitoring requires ACCESS_NETWORK_STATE",
                PackageManager.PERMISSION_GRANTED,
                context().checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE));
    }

    @Test public void nativeWebRtcJniLoadsInMinifiedRelease() {
        // Loads org.webrtc's JNI mappings without a network call or microphone.
        // A historical R8/JNI_OnLoad SIGABRT would abort this emulator test process.
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(context()).createInitializationOptions());
    }

    @Test public void subscriptionVoiceSelectsSpeakerAndRestoresAudioMode() {
        Context ctx = context();
        assertEquals(PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS));
        AudioManager audio = ctx.getSystemService(AudioManager.class);
        org.junit.Assume.assumeTrue("No other call may own audio during this test",
            audio.getMode() == AudioManager.MODE_NORMAL);
        SubscriptionVoiceAudioRoute route = new SubscriptionVoiceAudioRoute(ctx);
        try {
            boolean[] selected = {false};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> selected[0] = route.start());
            assertTrue("Built-in phone speaker must be available", selected[0]);
            long deadline = SystemClock.elapsedRealtime() + 2000;
            while (SystemClock.elapsedRealtime() < deadline) {
                AudioDeviceInfo device = audio.getCommunicationDevice();
                if (device != null && device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) break;
                SystemClock.sleep(50);
            }
            assertNotNull(audio.getCommunicationDevice());
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.getCommunicationDevice().getType());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(route::close);
        }
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (audio.getMode() != AudioManager.MODE_NORMAL && SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(50);
        assertEquals(AudioManager.MODE_NORMAL, audio.getMode());
    }

    private static final class FakeRoutePlatform implements SubscriptionVoiceAudioRoute.Platform {
        int mode = AudioManager.MODE_NORMAL, current = 1;
        List<SubscriptionVoiceAudioRoute.Device> available = new ArrayList<>();
        final List<Integer> selections = new ArrayList<>();
        Runnable changed;
        Runnable recheck;
        long now;
        long recheckDelay, recheckAt;
        int rejectedId = -1;
        boolean reject, cleared, unobserved;
        void devices(int... types) {
            available.clear();
            for (int type : types) available.add(new SubscriptionVoiceAudioRoute.Device(type, type));
            if (changed != null) changed.run();
        }
        @Override public int mode() { return mode; }
        @Override public void mode(int value) { mode = value; }
        @Override public List<SubscriptionVoiceAudioRoute.Device> devices() { return available; }
        @Override public int currentDeviceId() { return current; }
        @Override public boolean select(int id) {
            selections.add(id);
            // Deliberately asynchronous: Android has not confirmed the new route yet.
            return !reject && id != rejectedId;
        }
        @Override public void clear() { cleared = true; }
        @Override public void observe(Runnable value) { changed = value; }
        @Override public void unobserve() { unobserved = true; changed = null; recheck = null; }
        @Override public long nowMs() { return now; }
        @Override public void recheckAfter(long delayMs) {
            recheckDelay = delayMs;
            recheckAt = now + delayMs;
            recheck = changed;
        }
    }

    @Test public void liveHeadsetChangesKeepOneRouteOwnerAndFallBackToSpeaker() {
        FakeRoutePlatform platform = new FakeRoutePlatform();
        int speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        int bluetooth = AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
        int usb = AudioDeviceInfo.TYPE_USB_HEADSET;
        platform.devices(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, speaker);
        SubscriptionVoiceAudioRoute route = new SubscriptionVoiceAudioRoute(platform);
        try {
            assertTrue(route.start());
            assertEquals(List.of(speaker), platform.selections);
            platform.changed.run(); // Route request is still being applied.
            assertTrue(route.start()); // Another reply must not force a new route.
            assertEquals(1, platform.selections.size());
            platform.current = speaker;
            platform.devices(speaker, bluetooth);
            assertEquals(List.of(speaker, bluetooth), platform.selections);
            platform.current = bluetooth;
            platform.devices(speaker, bluetooth, usb);
            assertEquals(List.of(speaker, bluetooth, usb), platform.selections);
            platform.current = usb;
            platform.changed.run();
            platform.devices(speaker, bluetooth); // Unplug USB while voice is active.
            platform.current = bluetooth;
            platform.changed.run();
            platform.devices(speaker); // Disconnect Bluetooth while voice is active.
            assertEquals(List.of(speaker, bluetooth, usb, bluetooth, speaker), platform.selections);
            Runnable lateCallback = platform.changed;
            route.close();
            lateCallback.run();
            assertEquals(5, platform.selections.size());
            assertTrue(platform.unobserved && platform.cleared);
            assertEquals(AudioManager.MODE_NORMAL, platform.mode);
        } finally { route.close(); }
    }

    @Test public void confirmedRouteCanRecoverFromDriftAndUnconfirmedRequestsRetry() {
        FakeRoutePlatform platform = new FakeRoutePlatform();
        int speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        platform.devices(speaker, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
        SubscriptionVoiceAudioRoute route = new SubscriptionVoiceAudioRoute(platform);
        try {
            assertTrue(route.start());
            platform.changed.run();
            assertEquals(List.of(speaker), platform.selections); // Pending, no duplicate request.
            platform.current = speaker;
            platform.changed.run(); // Confirm the first request.
            platform.current = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
            platform.changed.run(); // Android moved away while speaker remained available.
            assertEquals(List.of(speaker, speaker), platform.selections);
            platform.changed.run();
            assertEquals(2, platform.selections.size());
            assertEquals("Route retry must allow Android's 30-second request window", 30_000L,
                platform.recheckDelay);
            platform.now = platform.recheckAt - 1;
            platform.changed.run();
            assertEquals("A pending request must not retry before its deadline", 2,
                platform.selections.size());
            platform.now = platform.recheckAt;
            platform.recheck.run(); // A request that never confirms cannot suppress recovery forever.
            assertEquals(List.of(speaker, speaker, speaker), platform.selections);
        } finally { route.close(); }
        assertNull(platform.recheck);
    }

    @Test public void rejectedHeadsetFallsBackWithoutEndingTheVoiceRoute() {
        FakeRoutePlatform platform = new FakeRoutePlatform();
        int usb = AudioDeviceInfo.TYPE_USB_HEADSET;
        int bluetooth = AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
        int speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        platform.devices(usb, speaker, bluetooth);
        platform.rejectedId = usb;
        SubscriptionVoiceAudioRoute route = new SubscriptionVoiceAudioRoute(platform);
        try {
            assertTrue(route.start());
            assertEquals(List.of(usb, bluetooth), platform.selections);
            platform.current = bluetooth;
            platform.changed.run();
            platform.selections.clear();
            platform.devices(usb, speaker);
            assertEquals(List.of(usb, speaker), platform.selections);
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform.mode);
            assertFalse(platform.unobserved || platform.cleared);
        } finally { route.close(); }
        // Also cover a rejected headset present before startup, with only speaker fallback.
        platform = new FakeRoutePlatform();
        platform.devices(usb, speaker);
        platform.rejectedId = usb;
        route = new SubscriptionVoiceAudioRoute(platform);
        try {
            assertTrue(route.start());
            assertEquals(List.of(usb, speaker), platform.selections);
        } finally { route.close(); }
    }

    @Test public void alreadyConnectedHeadsetsBeatSpeakerAtStartup() {
        for (int headset : new int[]{AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET}) {
            FakeRoutePlatform platform = new FakeRoutePlatform();
            platform.devices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, headset);
            SubscriptionVoiceAudioRoute route = new SubscriptionVoiceAudioRoute(platform);
            try {
                assertTrue(route.start());
                assertEquals(List.of(headset), platform.selections);
            } finally { route.close(); }
        }
    }

    @Test public void rejectedAudioRouteReleasesCallbacksAndDoesNotTakeOverPhoneCalls() {
        FakeRoutePlatform platform = new FakeRoutePlatform();
        platform.devices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        platform.reject = true;
        SubscriptionVoiceAudioRoute route = new SubscriptionVoiceAudioRoute(platform);
        assertFalse(route.start());
        assertTrue(platform.unobserved && platform.cleared);
        assertEquals(AudioManager.MODE_NORMAL, platform.mode);
        platform.mode = AudioManager.MODE_IN_CALL;
        platform.selections.clear();
        assertFalse(route.start());
        assertTrue(platform.selections.isEmpty());
        assertEquals(AudioManager.MODE_IN_CALL, platform.mode);
    }

    private static final class FakeSpeechEngine implements SubscriptionVoiceOutputSpeech.Engine {
        final TextToSpeech.OnInitListener init;
        UtteranceProgressListener progress;
        String utterance;
        AudioAttributes attributes;
        boolean closed;
        int speakResult = TextToSpeech.SUCCESS;
        FakeSpeechEngine(TextToSpeech.OnInitListener init) { this.init = init; }
        @Override public int setLanguage(Locale locale) { return TextToSpeech.LANG_AVAILABLE; }
        @Override public int setAudioAttributes(AudioAttributes value) { attributes = value; return TextToSpeech.SUCCESS; }
        @Override public int setOnUtteranceProgressListener(UtteranceProgressListener value) {
            progress = value; return TextToSpeech.SUCCESS;
        }
        @Override public int speak(String text, String value) { utterance = value; return speakResult; }
        @Override public void stop() { }
        @Override public void shutdown() { closed = true; }
    }

    private static SubscriptionVoiceOutputSpeech.Listener speechEvents(List<String> events) {
        return new SubscriptionVoiceOutputSpeech.Listener() {
            @Override public void ready(long epoch) { events.add("ready:" + epoch); }
            @Override public void finished(long epoch) { events.add("finished:" + epoch); }
            @Override public void failed(long epoch) { events.add("failed:" + epoch); }
        };
    }

    @Test public void outputOnlySpeechIgnoresRetiredEnginesAndMatchesPlaybackCompletion() {
        List<String> events = new ArrayList<>();
        List<FakeSpeechEngine> engines = new ArrayList<>();
        SubscriptionVoiceOutputSpeech output = new SubscriptionVoiceOutputSpeech(context(), speechEvents(events),
            (ctx, init) -> { FakeSpeechEngine engine = new FakeSpeechEngine(init); engines.add(engine); return engine; });
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try {
            instrumentation.runOnMainSync(() -> {
                output.connect(1);
                output.connect(2);
                engines.get(0).init.onInit(TextToSpeech.SUCCESS);
                engines.get(1).init.onInit(TextToSpeech.SUCCESS);
            });
            instrumentation.waitForIdleSync();
            assertEquals(List.of("ready:2"), events);
            assertTrue(engines.get(0).closed);
            assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, engines.get(1).attributes.getUsage());
            instrumentation.runOnMainSync(() -> output.speak(2, "The task is finished."));
            instrumentation.runOnMainSync(() -> output.connect(3));
            engines.get(2).init.onInit(TextToSpeech.SUCCESS);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> output.speak(3, "The next announcement."));
            engines.get(1).progress.onDone(engines.get(1).utterance);
            engines.get(2).progress.onDone("stale-utterance");
            instrumentation.waitForIdleSync();
            assertEquals(List.of("ready:2", "ready:3"), events);
            engines.get(2).progress.onDone(engines.get(2).utterance);
            instrumentation.waitForIdleSync();
            assertEquals(List.of("ready:2", "ready:3", "finished:3"), events);
            // A duplicated completion must not drain the next announcement.
            engines.get(2).progress.onDone(engines.get(2).utterance);
            instrumentation.waitForIdleSync();
            assertEquals(3, events.size());
        } finally { instrumentation.runOnMainSync(output::close); }
        assertTrue(engines.get(1).closed);
        assertTrue(engines.get(2).closed);
    }

    @Test public void outputOnlySpeechReportsEngineFailureWithoutClaimingPlayback() {
        List<String> events = new ArrayList<>();
        List<FakeSpeechEngine> engines = new ArrayList<>();
        SubscriptionVoiceOutputSpeech output = new SubscriptionVoiceOutputSpeech(context(), speechEvents(events),
            (ctx, init) -> { FakeSpeechEngine engine = new FakeSpeechEngine(init); engines.add(engine); return engine; });
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try {
            instrumentation.runOnMainSync(() -> output.connect(3));
            engines.get(0).init.onInit(TextToSpeech.SUCCESS);
            instrumentation.waitForIdleSync();
            engines.get(0).speakResult = TextToSpeech.ERROR;
            instrumentation.runOnMainSync(() -> output.speak(3, "An announcement."));
            engines.get(0).progress.onDone(engines.get(0).utterance);
            instrumentation.waitForIdleSync();
            assertEquals(List.of("ready:3", "failed:3"), events);
            assertTrue(engines.get(0).closed);
        } finally { instrumentation.runOnMainSync(output::close); }
    }
}
