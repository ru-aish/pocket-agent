package dev.pocket.agent;

import android.content.Context;
import android.content.SharedPreferences;

/** User-tunable speech-to-text routing and hybrid arbitration settings. */
final class VoiceSttSettings {
    enum Mode {
        HYBRID_THRESHOLD("hybrid_threshold"),
        ANDROID_DEFAULT_ONLY("android_default_only"),
        DEEPGRAM_ONLY("deepgram_only");

        final String storedValue;

        Mode(String storedValue) {
            this.storedValue = storedValue;
        }

        boolean usesHybridArbitration() {
            return this == HYBRID_THRESHOLD;
        }

        boolean usesDirectAndroidRecognizer() {
            return this == ANDROID_DEFAULT_ONLY;
        }

        boolean usesDeepgramStt() {
            return this != ANDROID_DEFAULT_ONLY;
        }
    }

    static final int DEFAULT_SHORT_UTTERANCE_MS = 3000;
    static final int MIN_SHORT_UTTERANCE_MS = 500;
    static final int MAX_SHORT_UTTERANCE_MS = 8000;
    static final int SHORT_UTTERANCE_STEP_MS = 250;
    private static final String PREFS = "voice_stt";
    private static final String MODE_KEY = "mode";
    private static final String SHORT_UTTERANCE_KEY = "short_utterance_ms";

    static Mode mode(Context context) {
        String value = prefs(context).getString(MODE_KEY, Mode.HYBRID_THRESHOLD.storedValue);
        if (Mode.ANDROID_DEFAULT_ONLY.storedValue.equals(value)) return Mode.ANDROID_DEFAULT_ONLY;
        if (Mode.DEEPGRAM_ONLY.storedValue.equals(value)) return Mode.DEEPGRAM_ONLY;
        return Mode.HYBRID_THRESHOLD;
    }

    static void setMode(Context context, Mode mode) {
        if (mode == null) throw new IllegalArgumentException("STT mode is required");
        prefs(context).edit().putString(MODE_KEY, mode.storedValue).apply();
    }

    static boolean usesThreshold(Context context) {
        return thresholdEnabled(mode(context));
    }

    static boolean thresholdEnabled(Mode mode) {
        return mode != null && mode.usesHybridArbitration();
    }

    static int shortUtteranceMs(Context context) {
        int value = prefs(context).getInt(SHORT_UTTERANCE_KEY, DEFAULT_SHORT_UTTERANCE_MS);
        return clamp(value);
    }

    static void setShortUtteranceMs(Context context, int value) {
        if (value < MIN_SHORT_UTTERANCE_MS || value > MAX_SHORT_UTTERANCE_MS)
            throw new IllegalArgumentException("Short utterance threshold is out of range");
        int snapped = MIN_SHORT_UTTERANCE_MS
            + Math.round((value - MIN_SHORT_UTTERANCE_MS) / (float) SHORT_UTTERANCE_STEP_MS)
                * SHORT_UTTERANCE_STEP_MS;
        prefs(context).edit().putInt(SHORT_UTTERANCE_KEY, clamp(snapped)).apply();
    }

    static int progressForMs(int value) {
        return (clamp(value) - MIN_SHORT_UTTERANCE_MS) / SHORT_UTTERANCE_STEP_MS;
    }

    static int msForProgress(int progress) {
        int maxProgress = (MAX_SHORT_UTTERANCE_MS - MIN_SHORT_UTTERANCE_MS)
            / SHORT_UTTERANCE_STEP_MS;
        int bounded = Math.max(0, Math.min(maxProgress, progress));
        return MIN_SHORT_UTTERANCE_MS + bounded * SHORT_UTTERANCE_STEP_MS;
    }

    static int maxProgress() {
        return (MAX_SHORT_UTTERANCE_MS - MIN_SHORT_UTTERANCE_MS)
            / SHORT_UTTERANCE_STEP_MS;
    }

    private static int clamp(int value) {
        return Math.max(MIN_SHORT_UTTERANCE_MS, Math.min(MAX_SHORT_UTTERANCE_MS, value));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private VoiceSttSettings() { }
}
