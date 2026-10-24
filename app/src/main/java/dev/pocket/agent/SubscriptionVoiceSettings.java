package dev.pocket.agent;

import android.content.Context;
import android.content.Intent;

/** Explicit opt-in for experimental subscription WebRTC voice. Legacy remains default. */
final class SubscriptionVoiceSettings {
    private static final String PREFS = "voice_subscription_v1";
    private static final String ENABLED = "enabled";
    static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
    }
    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, enabled).apply();
    }
    static Intent wakeIntent(Context context) {
        return enabled(context)
            ? new Intent(context, SubscriptionVoiceService.class).setAction(SubscriptionVoiceService.ACTION_WAKE)
            : new Intent(context, VoiceListeningService.class).setAction(VoiceListeningService.ACTION_START);
    }
    static Intent announcementIntent(Context context, VoiceAnnouncement announcement) {
        if (!enabled(context)) return VoiceAnnouncement.intent(context, announcement);
        return new Intent(context, SubscriptionVoiceService.class)
            .setAction(SubscriptionVoiceService.ACTION_ANNOUNCE)
            .putExtra("id", announcement.id)
            .putExtra("message", announcement.message)
            .putExtra("listenAfter", announcement.listenAfter);
    }
}
