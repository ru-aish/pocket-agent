package dev.pocket.agent;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.widget.Toast;

/** The assistant key activates notification-based voice; no on-screen assistant panel remains. */
public final class PocketVoiceSession extends VoiceInteractionSession {
    PocketVoiceSession(Context context) { super(context); }

    @Override public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        Context context = getContext();
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Pocket Agent: allow microphone access in Settings", Toast.LENGTH_LONG).show();
        } else {
            try {
                if (!SubscriptionVoiceSettings.enabled(context)
                    && new VoiceCredentialStore(context).read().isEmpty()) {
                    Toast.makeText(context, "Pocket Agent: configure Deepgram voice key", Toast.LENGTH_LONG).show();
                } else {
                    context.startForegroundService(SubscriptionVoiceSettings.wakeIntent(context));
                }
            } catch (Exception error) {
                Toast.makeText(context, "Pocket Agent: could not start background voice", Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }
}
