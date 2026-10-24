package dev.pocket.agent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Opt-in, standalone Codex-subscription voice foreground session. Pi remains the
 * sole agent harness; this service never exposes device tools to the voice model.
 * SubscriptionVoiceTransport owns live audio; output-only announcements use
 * Android speech synthesis without opening a microphone or a subscription call.
 */
public final class SubscriptionVoiceService extends Service implements AgentService.Observer {
    static final String ACTION_WAKE = "dev.pocket.agent.SUBSCRIPTION_VOICE_WAKE";
    static final String ACTION_ANNOUNCE = "dev.pocket.agent.SUBSCRIPTION_VOICE_ANNOUNCE";
    static final String ACTION_STOP = "dev.pocket.agent.SUBSCRIPTION_VOICE_STOP";
    private static final int NOTIFICATION_ID = 34;
    private static final int FAILURE_NOTIFICATION_ID = 35;
    private static final String CHANNEL = "voice_subscription";
    private static final String FAILURE_CHANNEL = "voice_subscription_failures";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService credentialWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "PocketVoiceCredentials");
        thread.setDaemon(true);
        return thread;
    });
    private SubscriptionVoiceLifecycle lifecycle;
    private SubscriptionVoiceTransport transport;
    private SubscriptionVoiceAudioRoute audioRoute;
    private SubscriptionVoiceOutputSpeech outputSpeech;
    private AndroidDefaultSpeechRecognizer initialSpeech;
    private long initialCaptureEpoch = -1;
    private final Runnable initialCaptureTimeout = () -> finishInitialCapture(null);
    private Runnable microphoneStartTimeout;
    private boolean started;
    private boolean wakeNeedsReadyCue;
    private String startupPhase = "startup";
    private static volatile boolean active;
    static boolean isActive() { return active; }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!started || lifecycle == null) return;
            lifecycle.tick(SystemClock.elapsedRealtime());
            if (started && lifecycle.connecting(lifecycle.epoch()))
                main.postDelayed(this, 250);
        }
    };
    private final SubscriptionVoiceTransport.Callback callbacks = new SubscriptionVoiceTransport.Callback() {
        @Override public void onConnected(long epoch) {
            main.post(() -> {
                if (!started || !lifecycle.connecting(epoch)) return;
                main.removeCallbacks(tick);
                SubscriptionVoiceFailureStore.ready(SubscriptionVoiceService.this);
                getSystemService(NotificationManager.class).cancel(FAILURE_NOTIFICATION_ID);
                lifecycle.connected(epoch, SystemClock.elapsedRealtime());
            });
        }
        @Override public void onDelegation(long epoch, String id, String text) {
            main.post(() -> { if (started) lifecycle.delegated(epoch, id, text); });
        }
        @Override public void onPlaybackFinished(long epoch) {
            main.post(() -> { if (started) lifecycle.playbackFinished(epoch, SystemClock.elapsedRealtime()); });
        }
        @Override public void onActivity(long epoch) {
            main.post(() -> { if (started) lifecycle.activity(epoch, SystemClock.elapsedRealtime()); });
        }
        @Override public void onTranscript(long epoch, String text) {
            main.post(() -> {
                if (started) lifecycle.transcriptActivity(epoch, text, SystemClock.elapsedRealtime());
            });
        }
        @Override public void onSpeechStarted(long epoch) {
            main.post(() -> { if (started) lifecycle.speechStarted(epoch, SystemClock.elapsedRealtime()); });
        }
        @Override public void onSpeechStopped(long epoch) {
            main.post(() -> { if (started) lifecycle.speechStopped(epoch, SystemClock.elapsedRealtime()); });
        }
        @Override public void onMicrophoneReady(long epoch) {
            main.post(() -> {
                if (!started || lifecycle.epoch() != epoch
                        || lifecycle.state() == SubscriptionVoiceLifecycle.State.CLOSED) return;
                if (microphoneStartTimeout != null) main.removeCallbacks(microphoneStartTimeout);
                lifecycle.microphoneReady(epoch);
                if (wakeNeedsReadyCue) {
                    wakeNeedsReadyCue = false;
                    playReadyCue();
                }
            });
        }
        @Override public void onError(long epoch, String safeMessage) {
            main.post(() -> {
                if (!started || lifecycle.epoch() != epoch) return;
                Log.w("PocketSubscriptionVoice", "Subscription voice transport unavailable");
                lifecycle.failed(epoch, safeMessage);
            });
        }
        @Override public void onDisconnected(long epoch) {
            main.post(() -> { if (started) lifecycle.disconnected(epoch); });
        }
        @Override public void onInterrupted(long epoch) {
            main.post(() -> {
                if (started) lifecycle.inputInterrupted(epoch, SystemClock.elapsedRealtime());
            });
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "ChatGPT subscription voice", NotificationManager.IMPORTANCE_LOW));
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(FAILURE_CHANNEL, "ChatGPT voice failures",
                NotificationManager.IMPORTANCE_DEFAULT));
        transport = new SubscriptionVoiceTransport(getApplicationContext(), callbacks);
        audioRoute = new SubscriptionVoiceAudioRoute(this);
        outputSpeech = new SubscriptionVoiceOutputSpeech(this, new SubscriptionVoiceOutputSpeech.Listener() {
            @Override public void ready(long epoch) { callbacks.onConnected(epoch); }
            @Override public void finished(long epoch) { callbacks.onPlaybackFinished(epoch); }
            @Override public void failed(long epoch) { callbacks.onError(epoch, "Phone speech audio unavailable"); }
        });
        lifecycle = new SubscriptionVoiceLifecycle(new SubscriptionVoiceLifecycle.Port() {
            @Override public boolean startInitialCapture(long epoch) {
                try { return startInitialCaptureForWake(epoch); }
                catch (RuntimeException unavailable) {
                    Log.w("PocketSubscriptionVoice", "Android recognition startup unavailable ("
                        + unavailable.getClass().getSimpleName() + ")");
                    stopInitialCapture();
                    showInitialCaptureFallback();
                    return false;
                }
            }
            @Override public void setMicrophoneEnabled(boolean enabled) {
                if (enabled && !ensureSpeakerRoute()) return;
                transport.setMicrophoneEnabled(enabled);
                if (microphoneStartTimeout != null) main.removeCallbacks(microphoneStartTimeout);
                if (enabled && !lifecycle.microphoneStarted()) {
                    long epoch = lifecycle.epoch();
                    microphoneStartTimeout = () -> {
                        if (started && lifecycle.epoch() == epoch && !lifecycle.microphoneStarted())
                            lifecycle.failed(epoch, "Native microphone audio start timed out");
                    };
                    main.postDelayed(microphoneStartTimeout, 10_000);
                }
            }
            @Override public void sendUserContext(String text) {
                transport.sendUserContext(text);
            }
            @Override public void connect(long epoch, boolean allowMicrophone) {
                if (!allowMicrophone) {
                    startupPhase = "playback";
                    SubscriptionVoiceFailureStore.phase(SubscriptionVoiceService.this, startupPhase);
                    notifyStatus("Preparing phone speech");
                    outputSpeech.connect(epoch);
                    return;
                }
                if (allowMicrophone && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    lifecycle.failed(epoch, "Microphone permission is required for subscription voice");
                    return;
                }
                startupPhase = "authentication";
                SubscriptionVoiceFailureStore.phase(SubscriptionVoiceService.this, startupPhase);
                notifyStatus("Authorizing ChatGPT voice");
                // Token refresh can require HTTPS and must never block Android's main thread.
                credentialWorker.execute(() -> {
                    SubscriptionVoiceCredentials.Token token = null;
                    boolean networkFailure = false;
                    try {
                        token = SubscriptionVoiceCredentials.load(getApplicationContext());
                    } catch (java.io.IOException unavailable) {
                        networkFailure = true;
                    } catch (Exception rejected) {
                        // OAuth errors can include secret-bearing response metadata.
                    }
                    final SubscriptionVoiceCredentials.Token ready = token;
                    final boolean retryable = networkFailure;
                    main.post(() -> {
                        if (!started || lifecycle == null || !lifecycle.connecting(epoch))
                            return;
                        if (ready == null) {
                            lifecycle.failed(epoch, retryable
                                    ? "Voice connection unavailable"
                                    : "ChatGPT voice sign-in required");
                            return;
                        }
                        startupPhase = "webrtc";
                        SubscriptionVoiceFailureStore.phase(SubscriptionVoiceService.this, startupPhase);
                        notifyStatus("Connecting ChatGPT voice audio");
                        try { transport.connect(epoch, ready.access, ready.account); }
                        catch (RuntimeException invalid) {
                            lifecycle.failed(epoch, "Unable to initialize native voice audio");
                        }
                    });
                });
            }
            @Override public void close(long epoch) {
                stopInitialCapture();
                if (microphoneStartTimeout != null) main.removeCallbacks(microphoneStartTimeout);
                transport.close(epoch);
                outputSpeech.close(epoch);
                audioRoute.close();
            }
            @Override public void sendToPi(String turnId, String utterance) {
                try {
                    sendPi(new JSONObject().put("action", "send")
                        .put("voiceSessionId", turnId).put("text", utterance));
                } catch (Exception rejected) {
                    lifecycle.close();
                }
            }
            @Override public void sendSpeech(String delegationId, String text) {
                if (!ensureSpeakerRoute()) return;
                if (lifecycle.microphoneAllowed()) transport.sendSpeech(delegationId, text);
                else outputSpeech.speak(lifecycle.epoch(), text);
            }
            @Override public void interruptSpeech() { transport.interruptSpeech(); }
            @Override public void announcementFailed(String id, String reason) {
                AgentService.reportVoiceFailure(SubscriptionVoiceService.this, id, reason);
            }
            @Override public void failure(String safeMessage) {
                String reason = SubscriptionVoiceFailureStore.safeReason(safeMessage);
                SubscriptionVoiceFailureStore.failed(SubscriptionVoiceService.this, startupPhase, safeMessage);
                notifyFailure(reason);
            }
            @Override public void status(String status) {
                notifyStatus(status);
                if (lifecycle != null && lifecycle.state() == SubscriptionVoiceLifecycle.State.CLOSED)
                    main.post(() -> {
                        if (started && lifecycle.state() == SubscriptionVoiceLifecycle.State.CLOSED)
                            stopSelf();
                    });
            }
        });
        started = true;
        active = true;
        AgentService.observe(this);
    }

    private boolean startInitialCaptureForWake(long epoch) {
        stopInitialCapture();
        if (!AndroidDefaultSpeechRecognizer.supported(this)) {
            showInitialCaptureFallback();
            return false;
        }
        initialCaptureEpoch = epoch;
        initialSpeech = new AndroidDefaultSpeechRecognizer(this,
            new AndroidDefaultSpeechRecognizer.Callback() {
                private boolean readyCued;
                private boolean current() { return started && initialCaptureEpoch == epoch; }
                @Override public void onReady() {
                    if (!current()) return;
                    notifyStatus("Listening now · speak after the cue");
                    if (!readyCued) {
                        readyCued = true;
                        playReadyCue();
                    }
                }
                @Override public void onSpeechStarted() { }
                @Override public void onSpeechEnded() {
                    if (!current()) return;
                    main.removeCallbacks(initialCaptureTimeout);
                    main.postDelayed(initialCaptureTimeout, 10_000);
                    notifyStatus("Finishing your first transcript");
                }
                @Override public void onPartial(String text) { }
                @Override public void onResult(String text) {
                    if (current()) finishInitialCapture(text);
                }
                @Override public void onError(int error) {
                    if (current()) finishInitialCapture(null);
                }
            });
        if (!initialSpeech.start()) {
            stopInitialCapture();
            showInitialCaptureFallback();
            return false;
        }
        main.postDelayed(initialCaptureTimeout, 60_000);
        return true;
    }

    private void finishInitialCapture(String text) {
        long epoch = initialCaptureEpoch;
        if (epoch < 0) return;
        stopInitialCapture();
        if (!started || lifecycle == null || lifecycle.epoch() != epoch) return;
        if (text != null && !text.trim().isEmpty()) {
            wakeNeedsReadyCue = false;
            lifecycle.initialCaptured(epoch, text);
        } else {
            // Never execute an incomplete partial transcript as a full command.
            showInitialCaptureFallback();
            lifecycle.initialCaptureUnavailable(epoch);
        }
    }

    private void showInitialCaptureFallback() {
        android.widget.Toast.makeText(this,
            "First transcript unavailable · speak after the voice-ready cue",
            android.widget.Toast.LENGTH_LONG).show();
    }

    private void stopInitialCapture() {
        initialCaptureEpoch = -1;
        main.removeCallbacks(initialCaptureTimeout);
        if (initialSpeech != null) initialSpeech.close();
        initialSpeech = null;
    }

    private boolean ensureSpeakerRoute() {
        if (audioRoute.start()) return true;
        lifecycle.failed(lifecycle.epoch(), "Phone speaker audio route unavailable");
        return false;
    }

    private void playReadyCue() {
        try {
            android.media.ToneGenerator tone = new android.media.ToneGenerator(
                android.media.AudioManager.STREAM_NOTIFICATION, 65);
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 180);
            main.postDelayed(tone::release, 450);
        } catch (RuntimeException unavailable) {
            Log.w("PocketSubscriptionVoice", "Ready cue unavailable");
        }
    }

    private void sendPi(JSONObject command) {
        try {
            startService(new Intent(this, AgentService.class)
                .setAction(AgentService.ACTION_COMMAND).putExtra("command", command.toString()));
        } catch (Exception rejected) {
            Log.w("PocketSubscriptionVoice", "Unable to submit Pi command");
            lifecycle.failed(lifecycle.epoch(), "Unable to submit Pi command");
        }
    }

    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 1,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2,
            new Intent(this, SubscriptionVoiceService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Pocket Agent · ChatGPT voice")
            .setContentText(message).setOngoing(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Stop voice", stop).build()).build();
    }

    private void notifyStatus(String message) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message));
    }

    private void notifyFailure(String reason) {
        PendingIntent open = PendingIntent.getActivity(this, 1,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification failure = new Notification.Builder(this, FAILURE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Pocket Agent voice stopped")
            .setContentText(reason)
            .setStyle(new Notification.BigTextStyle().bigText(
                reason + ". Open Pocket Agent settings for the last startup phase and build."))
            .setContentIntent(open).setAutoCancel(true).build();
        getSystemService(NotificationManager.class).notify(FAILURE_NOTIFICATION_ID, failure);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        if (ACTION_STOP.equals(intent.getAction())) { lifecycle.close(); stopSelf(); return START_NOT_STICKY; }
        if (!ACTION_WAKE.equals(intent.getAction()) && !ACTION_ANNOUNCE.equals(intent.getAction())) {
            if (lifecycle.state() == SubscriptionVoiceLifecycle.State.CLOSED) stopSelf(startId);
            return START_NOT_STICKY;
        }
        VoiceAnnouncement pendingAnnouncement = null;
        boolean wake = ACTION_WAKE.equals(intent.getAction());
        if (!wake) {
            try { pendingAnnouncement = VoiceAnnouncement.fromIntent(intent); }
            catch (RuntimeException invalid) {
                Log.w("PocketSubscriptionVoice", "Invalid subscription speech request");
                if (lifecycle.state() == SubscriptionVoiceLifecycle.State.CLOSED) stopSelf(startId);
                return START_NOT_STICKY;
            }
        }
        boolean needsMicrophone = wake || pendingAnnouncement.listenAfter
            || (lifecycle.state() != SubscriptionVoiceLifecycle.State.CLOSED && lifecycle.microphoneAllowed());
        if (lifecycle.state() == SubscriptionVoiceLifecycle.State.CLOSED) {
            startupPhase = "foreground";
            SubscriptionVoiceFailureStore.starting(this, startupPhase);
        }
        try {
            int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (needsMicrophone) serviceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, notification("Preparing ChatGPT voice"), serviceType);
        } catch (RuntimeException denied) {
            Log.w("PocketSubscriptionVoice", "Voice foreground service denied");
            SubscriptionVoiceFailureStore.failed(this, "foreground", "Microphone permission unavailable");
            notifyFailure("Android could not start the voice service");
            if (pendingAnnouncement != null) AgentService.reportVoiceFailure(
                this, pendingAnnouncement.id, "Android did not permit background voice playback");
            stopSelf();
            return START_NOT_STICKY;
        }
        // Release the wake detector's microphone before native WebRTC takes it.
        // Assistant-gesture and speak_to_user entry points did not already pause wake.
        WakeWordService.pauseForConversation();
        if (!AgentService.isRunning())
            startForegroundService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_START));
        if (wake) {
            // The default Android recognizer owns the first utterance; WebRTC
            // recording starts only after it releases the microphone.
            byte[] preroll = intent.getByteArrayExtra(WakeWordService.EXTRA_WAKE_PREROLL);
            if (preroll != null) java.util.Arrays.fill(preroll, (byte) 0);
            wakeNeedsReadyCue = lifecycle.state() == SubscriptionVoiceLifecycle.State.CLOSED
                || !lifecycle.microphoneAllowed();
            lifecycle.wake(SystemClock.elapsedRealtime());
        } else {
            if (!lifecycle.announce(pendingAnnouncement.id, pendingAnnouncement.message,
                    pendingAnnouncement.listenAfter, SystemClock.elapsedRealtime()))
                AgentService.reportVoiceFailure(this, pendingAnnouncement.id,
                    "Speech request could not be queued or microphone access was unavailable");
        }
        if (lifecycle.state() == SubscriptionVoiceLifecycle.State.CLOSED) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        main.removeCallbacks(tick);
        main.postDelayed(tick, 250);
        return START_NOT_STICKY;
    }

    @Override public void onAgentEvent(JSONObject event) {
        if (!started || lifecycle == null || event == null) return;
        // AgentService observers can run off the main looper. Serialize Pi output
        // with WebRTC callbacks, timeout ticks and the foreground-service lifecycle.
        String turn = event.optString("sessionId", "");
        String type = event.optString("type", "");
        String delta = event.optString("text", "");
        main.post(() -> {
            if (!started || lifecycle == null) return;
            switch (type) {
                case "voice_delta":
                    lifecycle.piDelta(turn, delta);
                    break;
                case "voice_end":
                    lifecycle.piFinished(turn);
                    break;
                case "voice_error":
                    lifecycle.piDelta(turn, "The phone assistant could not complete that request.");
                    lifecycle.piFinished(turn);
                    break;
                case "stopped":
                    lifecycle.close();
                    break;
            }
        });
    }

    @Override public void onDestroy() {
        started = false;
        active = false;
        main.removeCallbacks(tick);
        AgentService.unobserve(this);
        stopInitialCapture();
        if (lifecycle != null) lifecycle.close();
        if (transport != null) transport.close();
        if (outputSpeech != null) outputSpeech.close();
        if (audioRoute != null) audioRoute.close();
        SubscriptionVoiceFailureStore.normalStop(this);
        credentialWorker.shutdownNow();
        WakeWordService.resumeAfterConversation(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
