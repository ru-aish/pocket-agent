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
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.SpeechRecognizer;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.Arrays;
import org.json.JSONObject;

/** User-started assistant capture plus on-demand announcements from the running agent. */
public final class VoiceListeningService extends Service implements AgentService.Observer {
    static final String ACTION_START = "dev.pocket.agent.VOICE_START";
    static final String ACTION_STOP = "dev.pocket.agent.VOICE_STOP";
    static final String ACTION_ANNOUNCE = "dev.pocket.agent.VOICE_ANNOUNCE";
    private static final int NOTIFICATION_ID = 32;
    private static final String CHANNEL = "voice";
    private static final long IDLE_TIMEOUT_MS = 3000;
    private static final long SYSTEM_RESULT_GRACE_MS = 1500;
    private static final long SYSTEM_ENDPOINT_TIMEOUT_MS = 5000;
    private static final long DEFAULT_ANDROID_FINAL_TIMEOUT_MS = 5000;
    private static final int MAX_PENDING_ANNOUNCEMENTS = 8;
    private static volatile VoiceListeningService runningService;
    static boolean isRunning() { return runningService != null; }
    private static volatile boolean active;
    static boolean isActive() { return active; }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<VoiceAnnouncement> pendingAnnouncements = new ArrayDeque<>();
    private final LinkedHashSet<String> acceptedIds = new LinkedHashSet<>();
    private DeepgramVoice voice;
    private QuickVoiceCommands quickCommands;
    private AndroidShortSpeechRecognizer shortSpeech;
    private AndroidDefaultSpeechRecognizer defaultSpeech;
    private HybridTranscriptArbiter transcriptArbiter;
    private VoiceSttSettings.Mode sttMode = VoiceSttSettings.Mode.HYBRID_THRESHOLD;
    private long activeRecognitionCycle;
    private VoiceAnnouncement currentAnnouncement;
    private String activeTurn;
    private boolean responding, userSpeaking, started, keepListening, microphoneEnabled, streamingReady, wakeStarted;
    private boolean androidBargeInListening, androidBargeInTurn;
    private boolean systemWakeReadyCuePending;
    private long utteranceAt, firstTokenAt;
    private final Runnable idleStop = () -> {
        if (started && microphoneEnabled && streamingReady && !responding && !userSpeaking && pendingAnnouncements.isEmpty()) {
            Log.i("PocketVoice", "No speech for " + IDLE_TIMEOUT_MS + " ms; stopping microphone");
            stopSelf();
        }
    };
    private Runnable systemResultFallback, deepgramDuplicateFallback;
    private final Runnable restartDefaultSpeech = () -> {
        if (!started || sttMode.usesDeepgramStt()
                || !microphoneEnabled || responding || defaultSpeech == null) return;
        if (!startDefaultSpeechRecognition()) stopSelf();
    };
    private final Runnable defaultSpeechFinalTimeout = () -> {
        if (!started || sttMode.usesDeepgramStt()
                || responding || defaultSpeech == null || !defaultSpeech.isActive()) return;
        defaultSpeech.cancel();
        streamingReady = false;
        userSpeaking = false;
        scheduleDefaultSpeechRestart("Android speech result timed out · listening again");
    };

    @Override public void onCreate() {
        super.onCreate();
        runningService = this;
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Voice listening", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        if (!ACTION_START.equals(action) && !ACTION_ANNOUNCE.equals(action)) return START_NOT_STICKY;
        if (ACTION_START.equals(action) && intent.hasExtra(WakeWordService.EXTRA_WAKE_PREROLL)
                && !WakeWordService.enabled(this)) {
            if (!started) stopSelf();
            return START_NOT_STICKY;
        }
        VoiceAnnouncement request = null;
        if (ACTION_ANNOUNCE.equals(action)) {
            try { request = VoiceAnnouncement.fromIntent(intent); }
            catch (RuntimeException invalid) { Log.w("PocketVoice", "Invalid speech request", invalid); return START_NOT_STICKY; }
            if (acceptedIds.contains(request.id)) return START_NOT_STICKY;
            if (pendingAnnouncements.size() >= MAX_PENDING_ANNOUNCEMENTS) {
                Log.w("PocketVoice", "Announcement queue is full; rejecting request");
                AgentService.reportVoiceFailure(this, request.id, "Too many pending voice announcements");
                return START_NOT_STICKY;
            }
            acceptedIds.add(request.id);
            if (acceptedIds.size() > 64) acceptedIds.remove(acceptedIds.iterator().next());
        }
        if (!started && !startOutput(request == null ? "" : request.id)) return START_NOT_STICKY;
        if (ACTION_START.equals(action)) {
            byte[] wakePreroll = intent.getByteArrayExtra(WakeWordService.EXTRA_WAKE_PREROLL);
            if (wakePreroll != null && wakePreroll.length > 0 && !microphoneEnabled) {
                wakeStarted = true;
                try {
                    if (sttMode.usesDeepgramStt()) {
                        voice.primeWakePcm(wakePreroll);
                        if (sttMode.usesHybridArbitration() && shortSpeech != null)
                            shortSpeech.primePcm(wakePreroll);
                    } else {
                        // The direct system recognizer cannot consume wake preroll. Cue the user
                        // when it actually owns the microphone so the first command word is not lost.
                        systemWakeReadyCuePending = true;
                    }
                }
                catch (RuntimeException invalid) { Log.w("PocketVoice", "Invalid wake audio preroll", invalid); }
                finally { Arrays.fill(wakePreroll, (byte) 0); }
            }
            keepListening = true;
            if (!enableMicrophone()) {
                keepListening = false;
                stopSelf();
                return START_NOT_STICKY;
            }
            if (streamingReady && !responding && !userSpeaking) resetIdle();
        } else {
            if (request.listenAfter) {
                keepListening = true;
                if (!enableMicrophone()) keepListening = false;
            }
            voice.startOutput();
            pendingAnnouncements.addLast(request);
            main.removeCallbacks(idleStop);
            playNextAnnouncement();
        }
        return START_NOT_STICKY;
    }

    private boolean startOutput(String requestId) {
        String key;
        try { key = new VoiceCredentialStore(this).read(); }
        catch (Exception error) {
            AgentService.reportVoiceFailure(this, requestId, "Saved Deepgram voice key is unreadable");
            stopSelf(); return false;
        }
        if (key.isEmpty()) {
            Log.w("PocketVoice", "Speech requires configured Deepgram credentials");
            AgentService.reportVoiceFailure(this, requestId, "Configure a Deepgram voice key in Pocket Agent Settings");
            stopSelf(); return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification("Preparing speech"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFICATION_ID, notification("Preparing speech"));
        } catch (RuntimeException denied) {
            Log.w("PocketVoice", "Android denied speech foreground service", denied);
            AgentService.reportVoiceFailure(this, requestId, "Android denied speech in the background");
            stopSelf(); return false;
        }
        sttMode = VoiceSttSettings.mode(this);
        if (QuickVoiceCommands.enabled(this)) quickCommands = new QuickVoiceCommands(this);
        started = true;
        AgentService.observe(this);
        if (!AgentService.isRunning())
            startForegroundService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_START));
        voice = new DeepgramVoice(this, key, new DeepgramVoice.Callback() {
            @Override public void onUtterance(String text) {
                main.post(() -> {
                    if (sttMode.usesDeepgramStt())
                        deepgramUtterance(text, -1L);
                    else if (androidBargeInTurn)
                        androidBargeInUtterance(text);
                });
            }
            @Override public void onUtterance(String text, long spokenDurationMs) {
                main.post(() -> {
                    if (sttMode.usesDeepgramStt())
                        deepgramUtterance(text, spokenDurationMs);
                    else if (androidBargeInTurn)
                        androidBargeInUtterance(text);
                });
            }
            @Override public void onSpeechStarted() {
                main.post(() -> {
                    if (sttMode.usesDeepgramStt())
                        deepgramSpeechStarted();
                    else if (androidBargeInListening && responding) {
                        androidBargeInTurn = true;
                        speechStarted();
                    }
                });
            }
            @Override public void onEmptyTurn() {
                main.post(() -> {
                    if (!started) return;
                    if (sttMode.usesDirectAndroidRecognizer()) {
                        if (androidBargeInTurn) {
                            androidBargeInTurn = false;
                            userSpeaking = false;
                            stopAndroidBargeInBridge();
                            if (!responding && keepListening && microphoneEnabled)
                                scheduleDefaultSpeechRestart("Listening for follow-up");
                        }
                        return;
                    }
                    boolean waitingForSystem = false;
                    if (transcriptArbiter != null) {
                        transcriptArbiter.deepgramEmptyTurn(activeRecognitionCycle);
                        if (!transcriptArbiter.waitingForMatchingDeepgramFinal())
                            cancelDeepgramDuplicateFallback();
                        waitingForSystem = !transcriptArbiter.turnSettled()
                            && transcriptArbiter.systemEligible();
                        if (waitingForSystem)
                            scheduleSystemResultFallback(activeRecognitionCycle);
                        else if (transcriptArbiter.turnSettled() && responding)
                            prepareNextRecognitionTurn();
                    }
                    userSpeaking = false;
                    if (!responding) {
                        if (waitingForSystem) showStatus("Finalizing speech");
                        else afterPlayback();
                    }
                });
            }
            @Override public void onInterim(String text) {
                main.post(() -> {
                    if (started && sttMode.usesDeepgramStt()
                            && text != null && !text.trim().isEmpty()) {
                        userSpeaking = true;
                        main.removeCallbacks(idleStop);
                    } else if (started && androidBargeInTurn
                            && text != null && !text.trim().isEmpty()) {
                        userSpeaking = true;
                        main.removeCallbacks(idleStop);
                    }
                });
            }
            @Override public void onFirstAudio() {
                main.post(() -> {
                    if (!started || !responding) return;
                    if (currentAnnouncement == null) {
                        long firstAudioMs = SystemClock.elapsedRealtime() - utteranceAt;
                        Log.i("PocketVoiceLatency", "first_token_ms=" +
                            (firstTokenAt == 0 ? -1 : firstTokenAt - utteranceAt) +
                            " first_audio_ms=" + firstAudioMs);
                    }
                    boolean canInterrupt = microphoneEnabled
                        && (sttMode.usesDeepgramStt() || androidBargeInListening);
                    showStatus(canInterrupt ? "Speaking · listening for interruption" : "Speaking");
                });
            }
            @Override public void onPlaybackFinished() {
                main.post(() -> {
                    if (!started || !responding) return;
                    responding = false;
                    activeTurn = null;
                    currentAnnouncement = null;
                    afterPlayback();
                });
            }
            @Override public void onStreamReady() {
                main.post(() -> {
                    if (!started || !microphoneEnabled
                            || sttMode.usesDirectAndroidRecognizer()) return;
                    streamingReady = true;
                    if (!responding && !userSpeaking) {
                        showStatus("Listening");
                        resetIdle();
                    }
                });
            }
            @Override public void onStatus(String status) {
                main.post(() -> {
                    if (!started) return;
                    showStatus(status);
                    boolean inputFailure = status.startsWith("Microphone")
                        || status.startsWith("Speech recognition");
                    if (sttMode.usesDeepgramStt() && inputFailure) {
                        microphoneEnabled = false;
                        active = false;
                        streamingReady = false;
                        userSpeaking = false;
                        keepListening = false;
                        if (!responding) afterPlayback();
                    } else if (sttMode.needsDeepgramInterruptionBridge()
                            && androidBargeInListening && inputFailure && !voice.isCapturing()) {
                        boolean interruptedTurn = androidBargeInTurn;
                        androidBargeInListening = false;
                        androidBargeInTurn = false;
                        if (interruptedTurn) userSpeaking = false;
                        if (!responding && keepListening && microphoneEnabled)
                            scheduleDefaultSpeechRestart("Interruption stream ended · listening again");
                    }
                });
            }
        });
        if (sttMode.usesHybridArbitration()) {
            transcriptArbiter = new HybridTranscriptArbiter(false,
                VoiceSttSettings.shortUtteranceMs(this));
            if (AndroidShortSpeechRecognizer.supported(this)) {
                shortSpeech = new AndroidShortSpeechRecognizer(this,
                    new AndroidShortSpeechRecognizer.Callback() {
                        @Override public void onSpeechStarted() { systemSpeechStarted(); }
                        @Override public void onSpeechEnded() { systemSpeechEnded(); }
                        @Override public void onResult(String text) { systemUtterance(text); }
                        @Override public void onError() { systemRecognitionFailed(); }
                    });
                voice.setPcmObserver(shortSpeech);
            }
        } else if (sttMode.usesDirectAndroidRecognizer()
                && AndroidDefaultSpeechRecognizer.supported(this)) {
            defaultSpeech = new AndroidDefaultSpeechRecognizer(this,
                new AndroidDefaultSpeechRecognizer.Callback() {
                    @Override public void onReady() { defaultSpeechReady(); }
                    @Override public void onSpeechStarted() { defaultSpeechStarted(); }
                    @Override public void onSpeechEnded() { defaultSpeechEnded(); }
                    @Override public void onPartial(String text) { defaultSpeechPartial(text); }
                    @Override public void onResult(String text) { defaultSpeechResult(text); }
                    @Override public void onError(int error) { defaultSpeechError(error); }
                });
        }
        return true;
    }

    private boolean enableMicrophone() {
        if (microphoneEnabled) return true;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("PocketVoice", "Cannot listen without microphone permission");
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification("Listening for your voice"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            WakeWordService.pauseForConversation();
            microphoneEnabled = true;
            active = true;
            streamingReady = false;
            voice.startOutput();
            if (sttMode.usesDirectAndroidRecognizer()) {
                if (defaultSpeech != null && startDefaultSpeechRecognition()) return true;
                showStatus("Android default speech recognition is unavailable");
                microphoneEnabled = false;
                active = false;
                return false;
            }
            if (sttMode.usesHybridArbitration())
                prepareNextRecognitionTurn();
            voice.listen();
            return true;
        } catch (RuntimeException denied) {
            Log.w("PocketVoice", "Android denied microphone reactivation", denied);
            microphoneEnabled = false;
            active = false;
            return false;
        }
    }

    private void playNextAnnouncement() {
        if (!started || responding || userSpeaking || pendingAnnouncements.isEmpty()) return;
        currentAnnouncement = pendingAnnouncements.removeFirst();
        main.removeCallbacks(idleStop);
        pauseDefaultSpeechForOutput();
        responding = true;
        startAndroidBargeInBridge();
        activeTurn = null;
        voice.startReply();
        voice.appendReply(currentAnnouncement.message);
        voice.finishReply();
        showStatus("Speaking agent announcement");
        Log.i("PocketVoice", "Queued agent announcement for playback");
    }

    private void afterPlayback() {
        if (!started || responding) return;
        if (!pendingAnnouncements.isEmpty() && !userSpeaking) { playNextAnnouncement(); return; }
        if (keepListening && microphoneEnabled) {
            showStatus("Listening for follow-up");
            if (!userSpeaking) {
                if (sttMode.usesDirectAndroidRecognizer()) {
                    stopAndroidBargeInBridge();
                    scheduleDefaultSpeechRestart("Listening for follow-up");
                } else {
                    if (sttMode.usesHybridArbitration())
                        prepareNextRecognitionTurn();
                    if (streamingReady) resetIdle();
                }
            }
        } else if (!userSpeaking) stopSelf();
    }

    private boolean startDefaultSpeechRecognition() {
        if (!started || sttMode.usesDeepgramStt()
                || !microphoneEnabled || responding || defaultSpeech == null) return false;
        main.removeCallbacks(restartDefaultSpeech);
        main.removeCallbacks(defaultSpeechFinalTimeout);
        main.removeCallbacks(idleStop);
        streamingReady = false;
        boolean recognitionStarted = defaultSpeech.start();
        if (recognitionStarted)
            showStatus("Starting Android default speech recognition");
        return recognitionStarted;
    }

    private void pauseDefaultSpeechForOutput() {
        if (sttMode.usesDeepgramStt()) return;
        main.removeCallbacks(restartDefaultSpeech);
        main.removeCallbacks(defaultSpeechFinalTimeout);
        main.removeCallbacks(idleStop);
        streamingReady = false;
        if (defaultSpeech != null && defaultSpeech.isActive()) defaultSpeech.cancel();
    }

    private void startAndroidBargeInBridge() {
        if (!started || !sttMode.needsDeepgramInterruptionBridge()
                || !microphoneEnabled || voice == null || androidBargeInListening) return;
        androidBargeInTurn = false;
        voice.listen();
        androidBargeInListening = voice.isCapturing();
    }

    private void stopAndroidBargeInBridge() {
        if (!sttMode.needsDeepgramInterruptionBridge() || voice == null) return;
        androidBargeInListening = false;
        androidBargeInTurn = false;
        voice.stopListening();
    }

    private void defaultSpeechReady() {
        if (!started || sttMode.usesDeepgramStt()
                || !microphoneEnabled || responding) return;
        streamingReady = true;
        if (!userSpeaking) {
            showStatus("Listening · Android default only");
            if (systemWakeReadyCuePending) {
                systemWakeReadyCuePending = false;
                playSystemReadyCue();
            }
            resetIdle();
        }
    }

    private void playSystemReadyCue() {
        if (WakeWordService.playAcknowledgement()) return;
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
            main.postDelayed(tone::release, 300L);
        } catch (RuntimeException unavailable) {
            Log.w("PocketVoice", "Could not play Android STT ready cue", unavailable);
        }
    }

    private void defaultSpeechStarted() {
        if (!started || sttMode.usesDeepgramStt()) return;
        speechStarted();
    }

    private void defaultSpeechEnded() {
        if (!started || sttMode.usesDeepgramStt()) return;
        streamingReady = false;
        main.removeCallbacks(idleStop);
        main.removeCallbacks(defaultSpeechFinalTimeout);
        main.postDelayed(defaultSpeechFinalTimeout, DEFAULT_ANDROID_FINAL_TIMEOUT_MS);
        showStatus("Finalizing Android speech");
    }

    private void defaultSpeechPartial(String text) {
        if (!started || sttMode.usesDeepgramStt()
                || text == null || text.trim().isEmpty()) return;
        userSpeaking = true;
        main.removeCallbacks(idleStop);
    }

    private void defaultSpeechResult(String text) {
        if (!started || sttMode.usesDeepgramStt()) return;
        main.removeCallbacks(defaultSpeechFinalTimeout);
        streamingReady = false;
        if (text == null || text.trim().isEmpty()) {
            userSpeaking = false;
            scheduleDefaultSpeechRestart("No speech match · listening again");
            return;
        }
        sendUtterance(text);
    }

    private void defaultSpeechError(int error) {
        if (!started || sttMode.usesDeepgramStt()) return;
        main.removeCallbacks(defaultSpeechFinalTimeout);
        streamingReady = false;
        userSpeaking = false;
        main.removeCallbacks(idleStop);
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                || error == SpeechRecognizer.ERROR_CLIENT) {
            microphoneEnabled = false;
            active = false;
            keepListening = false;
            showStatus("Android speech recognition stopped (error " + error + ")");
            if (!responding) afterPlayback();
            return;
        }
        if (!responding && keepListening && microphoneEnabled)
            scheduleDefaultSpeechRestart("Android speech recognition ended · retrying");
    }

    private void scheduleDefaultSpeechRestart(String status) {
        if (!started || sttMode.usesDeepgramStt()
                || !keepListening || !microphoneEnabled || responding) return;
        showStatus(status);
        main.removeCallbacks(restartDefaultSpeech);
        main.postDelayed(restartDefaultSpeech, 250L);
    }

    private Notification notification(String status) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 20,
            new Intent(this, VoiceListeningService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(microphoneEnabled ? "Pocket Agent · Microphone active" : "Pocket Agent · Speaking")
            .setContentText(status).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "Stop voice", stop).build())
            .build();
    }

    private void showStatus(String status) {
        if (started) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(status));
    }

    private void cancelSystemResultFallback() {
        if (systemResultFallback != null) {
            main.removeCallbacks(systemResultFallback);
            systemResultFallback = null;
        }
    }

    private void scheduleSystemResultFallback(long cycle) {
        scheduleSystemFallback(cycle, SYSTEM_RESULT_GRACE_MS);
    }

    private void scheduleSystemEndpointFallback(long cycle) {
        scheduleSystemFallback(cycle, SYSTEM_ENDPOINT_TIMEOUT_MS);
    }

    private void scheduleSystemFallback(long cycle, long delayMs) {
        cancelSystemResultFallback();
        Runnable task = () -> {
            if (!started || transcriptArbiter == null || cycle != activeRecognitionCycle) return;
            HybridTranscriptArbiter.Choice choice = transcriptArbiter.systemWaitExpired(cycle);
            if (shortSpeech != null) shortSpeech.cancelCycle();
            acceptTranscriptChoice(choice);
        };
        systemResultFallback = task;
        main.postDelayed(task, delayMs);
    }

    private void cancelDeepgramDuplicateFallback() {
        if (deepgramDuplicateFallback != null) {
            main.removeCallbacks(deepgramDuplicateFallback);
            deepgramDuplicateFallback = null;
        }
    }

    private void scheduleDeepgramDuplicateFallback(long cycle) {
        cancelDeepgramDuplicateFallback();
        Runnable task = () -> {
            if (!started || transcriptArbiter == null || cycle != activeRecognitionCycle) return;
            deepgramDuplicateFallback = null;
            if (!transcriptArbiter.matchingDeepgramWaitExpired(cycle)) return;
            if (transcriptArbiter.turnSettled()) {
                if (responding || userSpeaking) prepareNextRecognitionTurn();
                else afterPlayback();
            }
        };
        deepgramDuplicateFallback = task;
        main.postDelayed(task, SYSTEM_RESULT_GRACE_MS);
    }

    private void resetIdle() {
        main.removeCallbacks(idleStop);
        main.postDelayed(idleStop, quickCommands != null && quickCommands.awaiting() ? 30000 : IDLE_TIMEOUT_MS);
    }

    private void speechStarted() {
        if (!started) return;
        userSpeaking = true;
        main.removeCallbacks(idleStop);
        if (responding) {
            if (quickCommands != null) quickCommands.cancelCurrent();
            String interruptedTurn = activeTurn;
            responding = false;
            activeTurn = null;
            currentAnnouncement = null;
            voice.interruptReply();
            try {
                if (interruptedTurn != null) command(new JSONObject()
                    .put("action", "stop_main").put("voiceSessionId", interruptedTurn));
            } catch (Exception error) { Log.w("PocketVoice", "Could not abort previous response", error); }
            showStatus("Interrupted · listening to you");
        } else showStatus("Hearing you");
    }

    private void markSpeechForArbitration() {
        if (transcriptArbiter == null || !transcriptArbiter.systemEligible()) return;
        transcriptArbiter.speechStarted(activeRecognitionCycle, SystemClock.elapsedRealtime());
    }

    private void deepgramSpeechStarted() {
        if (!started) return;
        if (transcriptArbiter != null) {
            if (transcriptArbiter.turnSettled()) prepareNextRecognitionTurn();
            transcriptArbiter.deepgramSpeechStarted(activeRecognitionCycle);
            if (transcriptArbiter.waitingForMatchingDeepgramFinal()) return;
        }
        speechStarted();
    }

    private void systemSpeechStarted() {
        if (!started) return;
        markSpeechForArbitration();
        if (!responding) {
            userSpeaking = true;
            main.removeCallbacks(idleStop);
            showStatus("Hearing you");
        }
    }

    private void systemSpeechEnded() {
        if (!started || transcriptArbiter == null) return;
        long cycle = activeRecognitionCycle;
        transcriptArbiter.systemSpeechEnded(cycle, SystemClock.elapsedRealtime());

        // Android's callback can include endpointing silence, so it is not authoritative for
        // short/long classification. If Flux already finalized, wait only for Android's final
        // text; otherwise the Flux word timings will make the duration decision when they arrive.
        cancelSystemResultFallback();
        if (transcriptArbiter.hasPendingDeepgramFinal())
            scheduleSystemResultFallback(cycle);
    }

    private void systemUtterance(String text) {
        if (!started || transcriptArbiter == null) return;
        cancelSystemResultFallback();
        if (wakeStarted && text != null
                && text.replaceFirst("(?i)^\\s*hey[,]?\\s+jarvis[,!?.\\s]*", "").trim().isEmpty()) {
            if (shortSpeech != null) shortSpeech.cancelCycle();
            systemRecognitionFailed();
            return;
        }
        HybridTranscriptArbiter.Choice choice = transcriptArbiter.systemFinal(
            activeRecognitionCycle, text, SystemClock.elapsedRealtime());
        if (choice == null && transcriptArbiter.systemEligible()) {
            if (transcriptArbiter.hasPendingDeepgramFinal())
                scheduleSystemResultFallback(activeRecognitionCycle);
            else scheduleSystemEndpointFallback(activeRecognitionCycle);
        }
        acceptTranscriptChoice(choice);
    }

    private void systemRecognitionFailed() {
        if (!started || transcriptArbiter == null) return;
        cancelSystemResultFallback();
        HybridTranscriptArbiter.Choice choice =
            transcriptArbiter.systemFailed(activeRecognitionCycle);
        acceptTranscriptChoice(choice);
        if (choice == null && !responding && !transcriptArbiter.deepgramTurnOpen()) {
            userSpeaking = false;
            if (streamingReady) resetIdle();
        }
    }

    private void deepgramUtterance(String text, long spokenDurationMs) {
        if (!started) return;
        if (transcriptArbiter == null) {
            sendUtterance(text);
            return;
        }
        HybridTranscriptArbiter.Choice choice = transcriptArbiter.deepgramFinal(
            activeRecognitionCycle, text, SystemClock.elapsedRealtime(), spokenDurationMs);
        if (!transcriptArbiter.waitingForMatchingDeepgramFinal())
            cancelDeepgramDuplicateFallback();
        if (choice == null && transcriptArbiter.systemEligible()) {
            if (transcriptArbiter.systemEndpointObserved())
                scheduleSystemResultFallback(activeRecognitionCycle);
            else scheduleSystemEndpointFallback(activeRecognitionCycle);
        }
        acceptTranscriptChoice(choice);
    }

    private void androidBargeInUtterance(String text) {
        if (!started || !sttMode.needsDeepgramInterruptionBridge()
                || !androidBargeInListening || !androidBargeInTurn) return;
        androidBargeInTurn = false;
        if (text == null || text.trim().isEmpty()) {
            userSpeaking = false;
            stopAndroidBargeInBridge();
            if (!responding && keepListening && microphoneEnabled)
                scheduleDefaultSpeechRestart("Listening for follow-up");
            return;
        }
        sendUtterance(text);
    }

    private void acceptTranscriptChoice(HybridTranscriptArbiter.Choice choice) {
        if (choice != null) {
            cancelSystemResultFallback();
            if (choice.source == HybridTranscriptArbiter.Source.DEEPGRAM && shortSpeech != null)
                shortSpeech.cancelCycle();
            Log.i("PocketVoice", "Transcript source=" +
                (choice.source == HybridTranscriptArbiter.Source.SYSTEM ? "android-system" : "deepgram"));
            sendUtterance(choice.text);
            if (choice.source == HybridTranscriptArbiter.Source.SYSTEM
                    && transcriptArbiter != null
                    && transcriptArbiter.waitingForMatchingDeepgramFinal())
                scheduleDeepgramDuplicateFallback(activeRecognitionCycle);
        }
        if (transcriptArbiter != null && transcriptArbiter.turnSettled()) {
            if (!responding && !userSpeaking) afterPlayback();
            else prepareNextRecognitionTurn();
        }
    }

    private void prepareNextRecognitionTurn() {
        cancelSystemResultFallback();
        cancelDeepgramDuplicateFallback();
        if (!started || !microphoneEnabled
                || sttMode != VoiceSttSettings.Mode.HYBRID_THRESHOLD
                || transcriptArbiter == null) return;
        long cycle = ++activeRecognitionCycle;
        boolean systemReady = shortSpeech != null && shortSpeech.startCycle();
        transcriptArbiter.reset(cycle, systemReady, VoiceSttSettings.shortUtteranceMs(this));
    }

    private void sendUtterance(String text) {
        if (!started || voice == null || text == null || text.trim().isEmpty()) return;
        if (wakeStarted) {
            text = text.replaceFirst("(?i)^\\s*hey[,]?\\s+jarvis[,!?.\\s]*", "").trim();
            if (text.isEmpty()) {
                userSpeaking = false;
                if (!responding) {
                    showStatus("Listening for follow-up");
                    if (sttMode.usesDirectAndroidRecognizer())
                        scheduleDefaultSpeechRestart("Listening for follow-up");
                    else if (streamingReady) resetIdle();
                }
                return;
            }
        }
        if (responding) speechStarted();
        userSpeaking = false;
        main.removeCallbacks(idleStop);
        pauseDefaultSpeechForOutput();
        responding = true;
        startAndroidBargeInBridge();
        activeTurn = UUID.randomUUID().toString();
        utteranceAt = SystemClock.elapsedRealtime();
        firstTokenAt = 0;
        voice.startReply();
        showStatus(sttMode.usesDirectAndroidRecognizer() && !androidBargeInListening
            ? "Thinking" : "Thinking · listening for interruption");
        try {
            final String utterance = text, turn = activeTurn;
            if (quickCommands == null) sendToAgent(utterance, turn);
            else quickCommands.submit(utterance, result -> {
                if (!started || !responding || !turn.equals(activeTurn)) return;
                if (result.fallback()) { sendToAgent(utterance, turn); return; }
                if (!result.speech().isEmpty()) try {
                    command(Json.obj("action", "record_local_voice", "text", utterance, "response", result.speech()));
                } catch (RuntimeException unavailable) { Log.w("PocketQuickCommand", "Could not record local exchange in chat"); }
                firstTokenAt = SystemClock.elapsedRealtime();
                voice.appendReply(result.speech());
                voice.finishReply();
                showStatus(result.awaiting() ? "Waiting for your answer" : "Speaking · listening for interruption");
            });
        } catch (Exception failure) {
            responding = false;
            activeTurn = null;
            voice.interruptReply();
            showStatus("Unable to submit voice request");
            afterPlayback();
        }
    }

    private void sendToAgent(String text, String turn) {
        try { command(new JSONObject().put("action", "send").put("voiceSessionId", turn).put("text", text)); }
        catch (Exception failure) {
            responding = false; activeTurn = null;
            if (voice != null) voice.interruptReply();
            showStatus("Unable to submit voice request"); afterPlayback();
        }
    }

    private void command(JSONObject command) {
        startService(new Intent(this, AgentService.class)
            .setAction(AgentService.ACTION_COMMAND).putExtra("command", command.toString()));
    }

    @Override public void onAgentEvent(JSONObject event) {
        if (!started || activeTurn == null || !activeTurn.equals(event.optString("sessionId"))) return;
        switch (event.optString("type")) {
            case "voice_delta":
                if (!responding || voice == null) return;
                if (firstTokenAt == 0) firstTokenAt = SystemClock.elapsedRealtime();
                voice.appendReply(event.optString("text"));
                break;
            case "voice_end":
                if (voice != null && responding) voice.finishReply();
                break;
            case "voice_error":
                if (voice != null && responding) voice.finishReply();
                showStatus("Speech request failed");
                break;
        }
    }

    @Override public void onDestroy() {
        if (runningService == this) runningService = null;
        started = false;
        active = false;
        streamingReady = false;
        androidBargeInListening = false;
        androidBargeInTurn = false;
        wakeStarted = false;
        systemWakeReadyCuePending = false;
        main.removeCallbacks(idleStop);
        main.removeCallbacks(restartDefaultSpeech);
        main.removeCallbacks(defaultSpeechFinalTimeout);
        cancelSystemResultFallback();
        cancelDeepgramDuplicateFallback();
        AgentService.unobserve(this);
        if (quickCommands != null) { quickCommands.close(); quickCommands = null; }
        pendingAnnouncements.clear();
        acceptedIds.clear();
        if (voice != null) voice.setPcmObserver(null);
        if (shortSpeech != null) { shortSpeech.close(); shortSpeech = null; }
        if (defaultSpeech != null) { defaultSpeech.close(); defaultSpeech = null; }
        transcriptArbiter = null;
        if (voice != null) { voice.close(); voice = null; }
        WakeWordService.resumeAfterConversation(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
