package dev.pocket.agent;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

/**
 * Small, non-secret voice activation receipt. Survives service teardown and
 * process death; never retains tokens, SDP, audio, transcripts, or raw server errors.
 */
final class SubscriptionVoiceFailureStore {
    private static final String PREFS = "subscription-voice-startup";
    private static final String STATE = "state";
    private static final String PHASE = "phase";
    private static final String REASON = "reason";
    private static final String VERSION = "version";
    private static final String WHEN = "when";
    private static final long PENDING_GRACE_MS = 10_000L;

    private SubscriptionVoiceFailureStore() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void starting(Context context, String phase) {
        prefs(context).edit().putString(STATE, "starting").putString(PHASE, safePhase(phase))
                .remove(REASON).putString(VERSION, BuildConfig.VERSION_NAME)
                .putLong(WHEN, System.currentTimeMillis()).commit();
    }

    static void phase(Context context, String phase) {
        SharedPreferences state = prefs(context);
        if ("starting".equals(state.getString(STATE, "")))
            state.edit().putString(PHASE, safePhase(phase)).commit();
    }

    static void ready(Context context) { prefs(context).edit().clear().commit(); }

    static void failed(Context context, String phase, String reason) {
        prefs(context).edit().putString(STATE, "failed")
                .putString(PHASE, safePhase(phase))
                .putString(REASON, safeReason(reason))
                .putString(VERSION, BuildConfig.VERSION_NAME)
                .putLong(WHEN, System.currentTimeMillis()).commit();
    }

    static void normalStop(Context context) {
        SharedPreferences state = prefs(context);
        if ("starting".equals(state.getString(STATE, ""))) state.edit().clear().commit();
    }

    static String lastStatus(Context context) {
        SharedPreferences state = prefs(context);
        String kind = state.getString(STATE, "");
        if (kind.isEmpty()) return "";
        String version = state.getString(VERSION, "");
        String phase = state.getString(PHASE, "startup");
        if ("starting".equals(kind)) {
            if (SubscriptionVoiceService.isActive()
                    || System.currentTimeMillis() - state.getLong(WHEN, 0) < PENDING_GRACE_MS)
                return "ChatGPT voice is starting (" + phase + ")";
            return "Previous voice activation stopped before readiness (" + phase
                    + "). Check the build's Android crash log. Build: " + version;
        }
        return "Last voice failure (" + phase + "): "
                + state.getString(REASON, "Voice unavailable") + ". Build: " + version;
    }

    /** Only known local categories are displayed; untrusted transport text is discarded. */
    static String safeReason(String message) {
        String value = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (value.contains("microphone") || value.contains("permission"))
            return "Microphone access unavailable";
        if (value.contains("sign-in") || value.contains("expired")
                || value.contains("credential") || value.contains("account"))
            return "ChatGPT voice sign-in required";
        if (value.contains("http 401") || value.contains("http 403")
                || value.contains("status 401") || value.contains("status 403"))
            return "ChatGPT rejected voice authentication";
        if (value.contains("http 429") || value.contains("rate limit"))
            return "ChatGPT voice rate limited";
        if (value.contains("network") || value.contains("connection") || value.contains("timed out"))
            return "Voice connection unavailable or timed out";
        if (value.contains("phone speech audio")) return "Phone speech audio unavailable";
        if (value.contains("webrtc") || value.contains("native") || value.contains("audio"))
            return "Native voice audio failed to initialize";
        return "Subscription voice setup failed";
    }

    private static String safePhase(String phase) {
        if ("permissions".equals(phase) || "authentication".equals(phase)
                || "webrtc".equals(phase) || "foreground".equals(phase)
                || "connected".equals(phase) || "playback".equals(phase)) return phase;
        return "startup";
    }
}
