package dev.pocket.agent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Only the local detector and microphone run between conversations. The Node agent,
 * Deepgram sockets and voice output start after verified wake-word detection.
 */
public final class WakeWordService extends Service {
    static final String ACTION_ENABLE = "dev.pocket.agent.WAKE_ENABLE";
    static final String ACTION_DISABLE = "dev.pocket.agent.WAKE_DISABLE";
    static final String EXTRA_WAKE_PREROLL = "dev.pocket.agent.WAKE_PREROLL";
    private static final java.util.concurrent.atomic.AtomicInteger enrollmentSessions = new java.util.concurrent.atomic.AtomicInteger();
    private static final int NOTIFICATION = 41;
    private static final String CHANNEL = "wakeword";
    private static volatile WakeWordService running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean recording = new AtomicBoolean();
    private volatile AudioRecord microphone;
    private volatile Thread thread;
    private volatile boolean paused, transitioning;
    private volatile boolean enabled;
    private volatile float threshold = JarvisDetector.DEFAULT_THRESHOLD;
    private JarvisDetector detector;
    private WakeAcknowledgementSound acknowledgementSound;

    static void updateThreshold(float value) {
        WakeWordService service = running;
        if (service != null) service.threshold = value;
    }

    static boolean playAcknowledgement() {
        WakeWordService service = running;
        return service != null && service.acknowledgementSound != null
            && service.acknowledgementSound.play();
    }

    static boolean isListening() {
        WakeWordService service = running;
        AudioRecord record = service == null ? null : service.microphone;
        return service != null && service.recording.get() && record != null &&
            record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
    }

    static boolean enabled(Context context) {
        return context.getSharedPreferences("wakeword", MODE_PRIVATE).getBoolean("enabled", false);
    }

    static void disableImmediately(Context context) {
        context.getSharedPreferences("wakeword", MODE_PRIVATE).edit()
            .putBoolean("enabled", false).commit();
        WakeWordService service = running;
        if (service != null) {
            service.enabled = false;
            service.transitioning = false;
            service.pause();
        }
    }

    static void pauseForConversation() {
        WakeWordService service = running;
        if (service != null) service.pauseAndWait();
    }

    static void resumeAfterConversation(Context context) {
        WakeWordService service = running;
        if (service != null) { service.transitioning = false; service.resume(); }
    }

    static void pauseForEnrollment() {
        enrollmentSessions.incrementAndGet();
        pauseForConversation();
    }

    static void resumeAfterEnrollment() {
        if (enrollmentSessions.updateAndGet(n -> Math.max(0, n - 1)) > 0) return;
        WakeWordService service = running;
        if (service != null) { service.transitioning = false; service.resume(); }
    }

    @Override public void onCreate() {
        super.onCreate();
        running = this;
        acknowledgementSound = new WakeAcknowledgementSound(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Hey Jarvis wake-word detector",
                NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISABLE.equals(action)) {
            disableImmediately(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_ENABLE.equals(action)) {
            boolean keyConfigured;
            try { keyConfigured = SubscriptionVoiceSettings.enabled(this)
                    || new VoiceCredentialStore(this).configured(); }
            catch (RuntimeException unavailable) {
                Log.w("JarvisWake", "Deepgram key cannot be read; wake word not enabled");
                stopSelf(); return START_NOT_STICKY;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                !keyConfigured) {
                stopSelf(); return START_NOT_STICKY;
            }
            getSharedPreferences("wakeword", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        }
        enabled = enabled(this);
        threshold = WakeWordSettings.threshold(this);
        if (!enabled) { stopSelf(); return START_NOT_STICKY; }
        try {
            startForeground(NOTIFICATION, notification("Ready for Hey Jarvis"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (RuntimeException rejected) {
            Log.e("JarvisWake", "Android refused foreground mic access", rejected);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!paused && !transitioning) resume();
        return START_STICKY;
    }

    private Notification notification(String description) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, WakeWordService.class)
            .setAction(ACTION_DISABLE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Pocket Agent · Hey Jarvis enabled")
            .setContentText(description).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "Disable wake word", stop).build()).build();
    }

    private synchronized void resume() {
        if (!enabled || transitioning || recording.get() || (enrollmentSessions.get() != 0)) return;
        if (VoiceListeningService.isActive() || SubscriptionVoiceService.isActive()) {
            paused = true; return;
        }
        paused = false;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        recording.set(true);
        thread = new Thread(this::detectLoop, "HeyJarvisDetector");
        thread.setDaemon(true);
        thread.start();
    }

    private void pauseAndWait() {
        pause();
        Thread previous = thread;
        if (previous != null && previous != Thread.currentThread()) {
            try { previous.join(1200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private synchronized void pause() {
        paused = true;
        recording.set(false);
        AudioRecord old = microphone;
        if (old != null) {
            try { old.stop(); } catch (RuntimeException ignored) { }
        }
    }

    private void detectLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioRecord record = null;
        boolean triggered = false;
        boolean failed = false;
        byte[] wakeTail = null;
        try {
            if (detector == null) detector = new JarvisDetector(this);
            else detector.reset();
            int buffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
            if (buffer < 0) throw new IllegalStateException("16 kHz microphone unavailable");
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Microphone permission revoked");
            record = new AudioRecord(WakeWordSettings.audioSource(this), 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(4096, buffer));
            if (record.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("Microphone initialization failed");
            // Pausing while ONNX loads must not allow a late microphone acquisition.
            synchronized (this) {
                if (!recording.get() || paused || (enrollmentSessions.get() != 0)) return;
                microphone = record;
                record.startRecording();
                if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                    throw new IllegalStateException("Microphone record permission unavailable");
            }
            final JarvisProfile.Profile profile = JarvisProfile.read(this);
            short[] data = new short[1280];
            int pending = 0;
            short[][] history = new short[10][1280];
            int historyPosition = 0;
            while (recording.get()) {
                int n = record.read(data, pending, data.length - pending);
                if (n <= 0) { failed = recording.get(); break; }
                pending += n;
                if (pending != data.length) continue;
                pending = 0;
                System.arraycopy(data, 0, history[historyPosition], 0, data.length);
                historyPosition = (historyPosition + 1) % history.length;
                float baseConfidence = detector.process(data);
                float confidence = profile == null ? baseConfidence
                    : profile.adjust(detector.featureSnapshot(), baseConfidence);
                if (confidence >= threshold) {
                    triggered = true;
                    transitioning = true;
                    recording.set(false);
                    wakeTail = new byte[history.length * data.length * 2];
                    for (int block = 0; block < history.length; block++) {
                        short[] pcm = history[(historyPosition + block) % history.length];
                        for (int i = 0; i < pcm.length; i++) {
                            int offset = (block * pcm.length + i) * 2;
                            wakeTail[offset] = (byte) pcm[i];
                            wakeTail[offset + 1] = (byte) (pcm[i] >>> 8);
                        }
                    }
                    Log.i("JarvisWake", "Hey Jarvis detected (confidence=" + confidence + ")");
                }
            }
        } catch (Exception error) {
            failed = true;
            Log.e("JarvisWake", "Wake-word listening stopped", error);
        } finally {
            microphone = null;
            if (record != null) {
                try { record.stop(); } catch (RuntimeException ignored) { }
                try { record.release(); } catch (RuntimeException releaseFailure) { Log.w("JarvisWake","Microphone release failed",releaseFailure); }
            }
            recording.set(false);
            thread = null;
            final boolean handoff = triggered;
            final boolean needsBackoff = failed;
            final byte[] finalWakeTail = wakeTail;
            main.post(() -> {
                if (handoff && enabled && !paused) {
                    paused = true;
                    getSystemService(NotificationManager.class).notify(
                        NOTIFICATION, notification("Active voice conversation"));
                    if (VoiceSttSettings.mode(this).usesDeepgramStt()
                        && !SubscriptionVoiceSettings.enabled(this)) playAcknowledgement();
                    try {
                        startForegroundService(SubscriptionVoiceSettings.wakeIntent(this)
                            .putExtra(EXTRA_WAKE_PREROLL, finalWakeTail));
                    } catch (RuntimeException denied) {
                        Log.e("JarvisWake", "Voice handoff unavailable", denied);
                        paused = false;
                        transitioning = false;
                        resume();
                    }
                } else if (enabled && !paused && !VoiceListeningService.isActive() && !SubscriptionVoiceService.isActive()
                        && !(enrollmentSessions.get() != 0)) {
                    transitioning = false;
                    if (needsBackoff) {
                        getSystemService(NotificationManager.class).notify(NOTIFICATION,
                            notification("Microphone unavailable · retrying in three seconds"));
                        main.postDelayed(this::resume, 3000);
                    } else resume();
                }
            });
        }
    }

    @Override public void onDestroy() {
        enabled = false;
        main.removeCallbacksAndMessages(null);
        pause();
        Thread previous = thread;
        if (previous != null && previous != Thread.currentThread()) {
            try { previous.join(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (detector != null && (previous == null || !previous.isAlive())) {
            try { detector.close(); } catch (Exception problem) { Log.w("JarvisWake", "Failed releasing model", problem); }
            detector = null;
        }
        if (acknowledgementSound != null) {
            try { acknowledgementSound.close(); } catch (RuntimeException releaseFailure) { Log.w("JarvisWake","Acknowledgement sound release failed",releaseFailure); }
            acknowledgementSound = null;
        }
        running = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
