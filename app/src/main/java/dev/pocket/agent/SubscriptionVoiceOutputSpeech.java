package dev.pocket.agent;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;
import java.util.UUID;

/** Output-only announcements use the system speech engine without a microphone or live call. */
final class SubscriptionVoiceOutputSpeech implements AutoCloseable {
    interface Listener {
        void ready(long epoch);
        void finished(long epoch);
        void failed(long epoch);
    }

    interface Engine {
        int setLanguage(Locale locale);
        int setAudioAttributes(AudioAttributes attributes);
        int setOnUtteranceProgressListener(UtteranceProgressListener progress);
        int speak(String text, String utterance);
        void stop();
        void shutdown();
    }

    interface EngineFactory { Engine create(Context context, TextToSpeech.OnInitListener init); }

    private static final class AndroidEngine implements Engine {
        private final TextToSpeech engine;
        AndroidEngine(Context context, TextToSpeech.OnInitListener init) { engine = new TextToSpeech(context, init); }
        @Override public int setLanguage(Locale locale) { return engine.setLanguage(locale); }
        @Override public int setAudioAttributes(AudioAttributes attributes) { return engine.setAudioAttributes(attributes); }
        @Override public int setOnUtteranceProgressListener(UtteranceProgressListener progress) {
            return engine.setOnUtteranceProgressListener(progress);
        }
        @Override public int speak(String text, String utterance) {
            return engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance);
        }
        @Override public void stop() { engine.stop(); }
        @Override public void shutdown() { engine.shutdown(); }
    }

    private final Context context;
    private final Listener listener;
    private final EngineFactory factory;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Engine speech;
    private long epoch = -1, generation;
    private boolean ready;
    private String utteranceId;
    private Runnable watchdog;

    SubscriptionVoiceOutputSpeech(Context context, Listener listener) {
        this(context, listener, AndroidEngine::new);
    }

    SubscriptionVoiceOutputSpeech(Context context, Listener listener, EngineFactory factory) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.factory = factory;
    }

    void connect(long id) {
        close();
        epoch = id;
        final long cycle = generation;
        try {
            speech = factory.create(context, status -> main.post(() -> {
                if (!current(cycle)) return;
                if (status != TextToSpeech.SUCCESS) { fail(cycle); return; }
                try {
                    int language = speech.setLanguage(Locale.getDefault());
                    if (language < TextToSpeech.LANG_AVAILABLE
                            || speech.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                != TextToSpeech.SUCCESS) { fail(cycle); return; }
                    if (speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utterance) { }
                        @Override public void onDone(String utterance) { complete(utterance, false); }
                        @Override public void onError(String utterance) { complete(utterance, true); }
                        @Override public void onError(String utterance, int error) { complete(utterance, true); }
                        @Override public void onStop(String utterance, boolean interrupted) { complete(utterance, true); }
                        private void complete(String utterance, boolean failed) {
                            main.post(() -> {
                                if (!current(cycle) || utteranceId == null || !utteranceId.equals(utterance)) return;
                                utteranceId = null;
                                cancelWatchdog();
                                if (failed) fail(cycle);
                                else listener.finished(epoch);
                            });
                        }
                    }) != TextToSpeech.SUCCESS) { fail(cycle); return; }
                    ready = true;
                    listener.ready(epoch);
                } catch (RuntimeException unavailable) { fail(cycle); }
            }));
        } catch (RuntimeException unavailable) { fail(cycle); }
    }

    void speak(long id, String text) {
        final long cycle = generation;
        if (id != epoch || !ready || speech == null) { fail(cycle); return; }
        if (text == null || text.trim().isEmpty() || text.length() > 800) { fail(cycle); return; }
        cancelWatchdog();
        utteranceId = UUID.randomUUID().toString();
        watchdog = () -> fail(cycle);
        main.postDelayed(watchdog, Math.max(30_000L, 10_000L + text.length() * 100L));
        try {
            if (speech.speak(text, utteranceId) != TextToSpeech.SUCCESS)
                fail(cycle);
        } catch (RuntimeException unavailable) { fail(cycle); }
    }

    private boolean current(long cycle) { return epoch >= 0 && cycle == generation && speech != null; }

    private void fail(long cycle) {
        if (epoch < 0 || cycle != generation) return;
        long failedEpoch = epoch;
        close();
        listener.failed(failedEpoch);
    }

    void close(long id) { if (id == epoch) close(); }

    private void cancelWatchdog() {
        if (watchdog != null) main.removeCallbacks(watchdog);
        watchdog = null;
    }

    @Override public void close() {
        ++generation;
        epoch = -1;
        ready = false;
        utteranceId = null;
        cancelWatchdog();
        Engine old = speech;
        speech = null;
        if (old != null) {
            try { old.stop(); } catch (RuntimeException ignored) { }
            try { old.shutdown(); } catch (RuntimeException ignored) { }
        }
    }
}
