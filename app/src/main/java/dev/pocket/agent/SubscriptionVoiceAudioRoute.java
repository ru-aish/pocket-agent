package dev.pocket.agent;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;

/** Main-looper-owned speaker route, released with the voice session. */
final class SubscriptionVoiceAudioRoute implements AutoCloseable {
    private final AudioManager audio;
    private boolean started;
    private int previousMode;

    SubscriptionVoiceAudioRoute(Context context) {
        audio = context.getSystemService(AudioManager.class);
    }

    boolean start() {
        if (audio == null) return false;
        if (!started) {
            previousMode = audio.getMode();
            // Do not take over an actual phone call or another communication app.
            if (previousMode != AudioManager.MODE_NORMAL) return false;
            started = true;
        }
        try {
            audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
            for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    if (audio.setCommunicationDevice(device)) return true;
                    break;
                }
            }
        } catch (RuntimeException unavailable) {
            Log.w("PocketSubscriptionVoice", "Speaker route unavailable ("
                + unavailable.getClass().getSimpleName() + ")");
        }
        close();
        return false;
    }

    @Override public void close() {
        if (!started) return;
        started = false;
        try { audio.clearCommunicationDevice(); } catch (RuntimeException ignored) { }
        try {
            if (audio.getMode() == AudioManager.MODE_IN_COMMUNICATION)
                audio.setMode(previousMode);
        } catch (RuntimeException ignored) { }
    }
}
