package dev.pocket.agent;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class VoiceSttSettingsDeviceTest {
    @Test public void modeDefaultsToHybridAndPersistsSingleProviderChoices() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("voice_stt", Context.MODE_PRIVATE);
        Object previous = prefs.getAll().get("mode");
        try {
            prefs.edit().remove("mode").commit();
            assertEquals(VoiceSttSettings.Mode.HYBRID_THRESHOLD, VoiceSttSettings.mode(context));
            assertTrue(VoiceSttSettings.usesThreshold(context));

            VoiceSttSettings.setMode(context, VoiceSttSettings.Mode.ANDROID_DEFAULT_ONLY);
            assertEquals(VoiceSttSettings.Mode.ANDROID_DEFAULT_ONLY, VoiceSttSettings.mode(context));
            assertFalse(VoiceSttSettings.usesThreshold(context));

            VoiceSttSettings.setMode(context, VoiceSttSettings.Mode.DEEPGRAM_ONLY);
            assertEquals(VoiceSttSettings.Mode.DEEPGRAM_ONLY, VoiceSttSettings.mode(context));
            assertFalse(VoiceSttSettings.usesThreshold(context));
        } finally {
            SharedPreferences.Editor restore = prefs.edit().remove("mode");
            if (previous instanceof String) restore.putString("mode", (String) previous);
            restore.commit();
        }
    }

    @Test public void modePolicyRoutesExactlyTheRequestedSttProviders() {
        VoiceSttSettings.Mode hybrid = VoiceSttSettings.Mode.HYBRID_THRESHOLD;
        assertTrue(hybrid.usesHybridArbitration());
        assertFalse(hybrid.usesDirectAndroidRecognizer());
        assertTrue(hybrid.usesDeepgramStt());
        assertTrue(VoiceSttSettings.thresholdEnabled(hybrid));

        VoiceSttSettings.Mode android = VoiceSttSettings.Mode.ANDROID_DEFAULT_ONLY;
        assertFalse(android.usesHybridArbitration());
        assertTrue(android.usesDirectAndroidRecognizer());
        assertFalse(android.usesDeepgramStt());
        assertFalse(VoiceSttSettings.thresholdEnabled(android));

        VoiceSttSettings.Mode deepgram = VoiceSttSettings.Mode.DEEPGRAM_ONLY;
        assertFalse(deepgram.usesHybridArbitration());
        assertFalse(deepgram.usesDirectAndroidRecognizer());
        assertTrue(deepgram.usesDeepgramStt());
        assertFalse(VoiceSttSettings.thresholdEnabled(deepgram));
    }

    @Test public void changingSavedModeIsObservedByTheNextSettingsRead() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("voice_stt", Context.MODE_PRIVATE);
        Object previous = prefs.getAll().get("mode");
        try {
            VoiceSttSettings.setMode(context, VoiceSttSettings.Mode.ANDROID_DEFAULT_ONLY);
            assertEquals(VoiceSttSettings.Mode.ANDROID_DEFAULT_ONLY, VoiceSttSettings.mode(context));

            VoiceSttSettings.setMode(context, VoiceSttSettings.Mode.DEEPGRAM_ONLY);
            assertEquals(VoiceSttSettings.Mode.DEEPGRAM_ONLY, VoiceSttSettings.mode(context));

            VoiceSttSettings.setMode(context, VoiceSttSettings.Mode.HYBRID_THRESHOLD);
            assertEquals(VoiceSttSettings.Mode.HYBRID_THRESHOLD, VoiceSttSettings.mode(context));
        } finally {
            SharedPreferences.Editor restore = prefs.edit().remove("mode");
            if (previous instanceof String) restore.putString("mode", (String) previous);
            restore.commit();
        }
    }

    @Test public void thresholdRemainsConfigurableForHybridMode() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("voice_stt", Context.MODE_PRIVATE);
        Object previousMode = prefs.getAll().get("mode");
        Object previousThreshold = prefs.getAll().get("short_utterance_ms");
        try {
            VoiceSttSettings.setMode(context, VoiceSttSettings.Mode.HYBRID_THRESHOLD);
            VoiceSttSettings.setShortUtteranceMs(context, 4250);
            assertTrue(VoiceSttSettings.usesThreshold(context));
            assertEquals(4250, VoiceSttSettings.shortUtteranceMs(context));
        } finally {
            SharedPreferences.Editor restore = prefs.edit()
                .remove("mode").remove("short_utterance_ms");
            if (previousMode instanceof String)
                restore.putString("mode", (String) previousMode);
            if (previousThreshold instanceof Integer)
                restore.putInt("short_utterance_ms", (Integer) previousThreshold);
            restore.commit();
        }
    }

    @Test public void thresholdHonorsMinimumMaximumAndQuarterSecondSnapping() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("voice_stt", Context.MODE_PRIVATE);
        Object previous = prefs.getAll().get("short_utterance_ms");
        try {
            VoiceSttSettings.setShortUtteranceMs(context, VoiceSttSettings.MIN_SHORT_UTTERANCE_MS);
            assertEquals(500, VoiceSttSettings.shortUtteranceMs(context));

            VoiceSttSettings.setShortUtteranceMs(context, VoiceSttSettings.MAX_SHORT_UTTERANCE_MS);
            assertEquals(8000, VoiceSttSettings.shortUtteranceMs(context));

            VoiceSttSettings.setShortUtteranceMs(context, 626);
            assertEquals(750, VoiceSttSettings.shortUtteranceMs(context));

            try {
                VoiceSttSettings.setShortUtteranceMs(context, 499);
                fail("Threshold below minimum should be rejected");
            } catch (IllegalArgumentException expected) { }

            try {
                VoiceSttSettings.setShortUtteranceMs(context, 8001);
                fail("Threshold above maximum should be rejected");
            } catch (IllegalArgumentException expected) { }
        } finally {
            SharedPreferences.Editor restore = prefs.edit().remove("short_utterance_ms");
            if (previous instanceof Integer)
                restore.putInt("short_utterance_ms", (Integer) previous);
            restore.commit();
        }
    }

    @Test public void unknownModeFallsBackToHybrid() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("voice_stt", Context.MODE_PRIVATE);
        Object previous = prefs.getAll().get("mode");
        try {
            prefs.edit().putString("mode", "future_mode").commit();
            assertEquals(VoiceSttSettings.Mode.HYBRID_THRESHOLD, VoiceSttSettings.mode(context));
            assertTrue(VoiceSttSettings.usesThreshold(context));
        } finally {
            SharedPreferences.Editor restore = prefs.edit().remove("mode");
            if (previous instanceof String) restore.putString("mode", (String) previous);
            restore.commit();
        }
    }
}
