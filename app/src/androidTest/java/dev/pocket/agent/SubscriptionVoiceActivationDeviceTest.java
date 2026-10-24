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
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.webrtc.PeerConnectionFactory;
import static org.junit.Assert.*;

/** Disposable-emulator regressions for the power-button subscription voice path. */
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
        String header = base64(new JSONObject().put("alg", "RS256").toString());
        JSONObject claims = new JSONObject()
                .put("https://api.openai.com/auth",
                        new JSONObject().put("chatgpt_account_id", "emulator-voice-test"))
                .put("exp", System.currentTimeMillis() / 1000L + 7200L);
        return new JSONObject().put("accessToken", header + "." + base64(claims.toString()) + ".c2ln")
                .put("refreshToken", refresh).put("accountId", "emulator-voice-test")
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

    @Test public void failureClassificationNeverPrintsRemoteErrorText() {
        assertEquals("ChatGPT rejected voice authentication",
                SubscriptionVoiceFailureStore.safeReason("Subscription call rejected (HTTP 401)"));
        assertEquals("Native voice audio failed to initialize",
                SubscriptionVoiceFailureStore.safeReason("Unable to initialize native audio"));
        assertEquals("Subscription voice setup failed",
                SubscriptionVoiceFailureStore.safeReason("token=do-not-log-this"));
    }

    @Test public void missingLoginWakeLeavesPersistentActionableFailure() throws Exception {
        Context ctx = context();
        SharedPreferences protectedPrefs = ctx.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE);
        String prior = protectedPrefs.getString("encrypted", null);
        try {
            assertTrue(protectedPrefs.edit().remove("encrypted").commit());
            SubscriptionVoiceFailureStore.ready(ctx);
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(ctx.getPackageName(), Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33)
                InstrumentationRegistry.getInstrumentation().getUiAutomation()
                        .grantRuntimePermission(ctx.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
            Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                    new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                ctx.startForegroundService(new Intent(ctx, SubscriptionVoiceService.class)
                        .setAction(SubscriptionVoiceService.ACTION_WAKE));
                boolean found = false;
                long until = SystemClock.elapsedRealtime() + 12_000L;
                while (SystemClock.elapsedRealtime() < until) {
                    if (SubscriptionVoiceFailureStore.lastStatus(ctx)
                            .contains("ChatGPT voice sign-in required")) {
                        found = true;
                        break;
                    }
                    SystemClock.sleep(100);
                }
                assertTrue("Wake failure must remain visible after service teardown: "
                        + SubscriptionVoiceFailureStore.lastStatus(ctx), found);
            } finally {
                activity.finish();
            }
        } finally {
            ctx.stopService(new Intent(ctx, SubscriptionVoiceService.class));
            SharedPreferences.Editor restore = protectedPrefs.edit();
            if (prior == null) restore.remove("encrypted"); else restore.putString("encrypted", prior);
            assertTrue(restore.commit());
            SubscriptionVoiceFailureStore.ready(ctx);
            ctx.getSystemService(NotificationManager.class).cancel(35);
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
