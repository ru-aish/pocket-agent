package dev.pocket.agent;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.ContactsContract;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Feeds Pocket Agent's existing PCM capture into Android's system recognizer.
 * This class never opens a microphone, so Deepgram remains the single AudioRecord owner.
 */
final class AndroidShortSpeechRecognizer implements AutoCloseable, DeepgramVoice.PcmObserver {
    interface Callback {
        void onSpeechStarted();
        void onSpeechEnded();
        void onResult(String text);
        void onError();
    }

    private static final int MAX_PREROLL_BYTES = 32000;
    private static final int MAX_BIASING_STRINGS = 128;
    private final Context context;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor writer;
    private final ExecutorService biasLoader;
    private final Object biasLock = new Object();
    private final ArrayDeque<byte[]> preroll = new ArrayDeque<>();
    private ArrayList<String> cachedBiases = new ArrayList<>();
    private boolean biasPermissionGranted;
    private boolean biasCacheReady;
    private boolean biasRefreshInFlight;
    private int prerollBytes;
    private long generation;
    private volatile boolean active;
    private volatile OutputStream pcmOutput;
    private ParcelFileDescriptor readPipe;
    private ParcelFileDescriptor writePipe;
    private SpeechRecognizer recognizer;

    AndroidShortSpeechRecognizer(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(48), runnable -> {
                Thread thread = new Thread(runnable, "PocketSystemSpeechPcm");
                thread.setDaemon(true);
                return thread;
            });
        biasLoader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PocketSystemSpeechBias");
            thread.setDaemon(true);
            return thread;
        });
        recognitionBiases();
    }

    static boolean supported(Context context) {
        return Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(context);
    }

    void primePcm(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || active) return;
        if (pcm.length > MAX_PREROLL_BYTES - prerollBytes) {
            preroll.clear();
            prerollBytes = 0;
            return;
        }
        byte[] copy = Arrays.copyOf(pcm, pcm.length);
        preroll.addLast(copy);
        prerollBytes += copy.length;
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    boolean startCycle() {
        if (!supported(context)) return false;
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("SpeechRecognizer must start on the main thread");
        endCycle(true);
        final long cycle = ++generation;
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            readPipe = pipe[0];
            writePipe = pipe[1];
            pcmOutput = new ParcelFileDescriptor.AutoCloseOutputStream(writePipe);
            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(new Listener(cycle));
            Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                .putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readPipe)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            ArrayList<String> biases = recognitionBiases();
            if (!biases.isEmpty())
                request.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, biases);
            active = true;
            recognizer.checkRecognitionSupport(request, context.getMainExecutor(),
                new RecognitionSupportCallback() {
                    @Override public void onSupportResult(RecognitionSupport support) {
                        if (!active || cycle != generation || recognizer == null) return;
                        try { recognizer.startListening(request); }
                        catch (RuntimeException unsupported) { failCycle(cycle); }
                    }
                    @Override public void onError(int error) { failCycle(cycle); }
                });
            while (!preroll.isEmpty()) {
                byte[] frame = preroll.removeFirst();
                prerollBytes -= frame.length;
                enqueue(frame, frame.length, cycle);
            }
            return true;
        } catch (Exception unavailable) {
            endCycle(false);
            return false;
        }
    }

    private ArrayList<String> recognitionBiases() {
        boolean granted = context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED;
        boolean startRefresh = false;
        ArrayList<String> snapshot;
        synchronized (biasLock) {
            if (!biasCacheReady || biasPermissionGranted != granted) {
                biasPermissionGranted = granted;
                cachedBiases = new ArrayList<>();
                biasCacheReady = !granted;
                if (granted && !biasRefreshInFlight) {
                    biasRefreshInFlight = true;
                    startRefresh = true;
                }
            }
            snapshot = new ArrayList<>(cachedBiases);
        }
        if (startRefresh) refreshRecognitionBiasesAsync();
        return snapshot;
    }

    private void refreshRecognitionBiasesAsync() {
        try {
            biasLoader.execute(() -> {
                LinkedHashSet<String> names = new LinkedHashSet<>();
                boolean granted = context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED;
                if (granted) {
                    appendContactNames(ContactsContract.Contacts.CONTENT_STREQUENT_URI, names, null);
                    if (names.size() < MAX_BIASING_STRINGS) {
                        appendContactNames(ContactsContract.Contacts.CONTENT_URI, names,
                            ContactsContract.Contacts.STARRED + " DESC, "
                                + ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                                + " COLLATE NOCASE ASC");
                    }
                }
                boolean stillGranted = context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED;
                synchronized (biasLock) {
                    biasPermissionGranted = stillGranted;
                    cachedBiases = stillGranted ? new ArrayList<>(names) : new ArrayList<>();
                    biasCacheReady = true;
                    biasRefreshInFlight = false;
                }
            });
        } catch (RejectedExecutionException stopped) {
            synchronized (biasLock) {
                biasRefreshInFlight = false;
            }
        }
    }

    private void appendContactNames(Uri uri, LinkedHashSet<String> names, String sortOrder) {
        if (names.size() >= MAX_BIASING_STRINGS) return;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{ContactsContract.Contacts.DISPLAY_NAME_PRIMARY},
                null, null, sortOrder)) {
            if (cursor == null) return;
            int column = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY);
            if (column < 0) return;
            while (cursor.moveToNext() && names.size() < MAX_BIASING_STRINGS) {
                String name = cursor.getString(column);
                if (name == null) continue;
                name = name.trim();
                if (!name.isEmpty() && name.length() <= 80) names.add(name);
            }
        } catch (RuntimeException ignored) { }
    }

    static String chooseRecognitionResult(ArrayList<String> options, ArrayList<String> biases) {
        if (options == null || options.isEmpty()) return "";
        String first = options.get(0) == null ? "" : options.get(0).trim();
        if (first.isEmpty() || !looksLikeContactCommand(first) || biases == null || biases.isEmpty())
            return first;
        for (String option : options) {
            if (option == null || option.trim().isEmpty()) continue;
            String normalizedOption = " " + normalizeWords(option) + " ";
            for (String name : biases) {
                String normalizedName = normalizeWords(name);
                if (!normalizedName.isEmpty()
                        && normalizedOption.contains(" " + normalizedName + " "))
                    return option.trim();
            }
        }
        return first;
    }

    private static boolean looksLikeContactCommand(String text) {
        String normalized = normalizeWords(text);
        return normalized.startsWith("call ") || normalized.startsWith("dial ")
            || normalized.startsWith("phone ") || normalized.startsWith("text ")
            || normalized.startsWith("message ") || normalized.startsWith("whatsapp ")
            || normalized.startsWith("send ") || normalized.startsWith("please call ")
            || normalized.startsWith("please text ") || normalized.startsWith("please message ");
    }

    private static String normalizeWords(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    @Override public void onPcm(byte[] samples, int length) {
        if (!active || samples == null || length <= 0) return;
        enqueue(samples, length, generation);
    }

    private void enqueue(byte[] samples, int length, long cycle) {
        if (!active || cycle != generation) return;
        final byte[] copy = Arrays.copyOf(samples, length);
        final OutputStream target = pcmOutput;
        if (target == null) return;
        try {
            writer.execute(() -> {
                if (!active || cycle != generation || pcmOutput != target) return;
                try {
                    target.write(copy);
                } catch (IOException failed) {
                    main.post(() -> failCycle(cycle));
                }
            });
        } catch (RejectedExecutionException full) {
            main.post(() -> failCycle(cycle));
        }
    }

    void cancelCycle() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::cancelCycle);
            return;
        }
        endCycle(true);
    }

    private void failCycle(long cycle) {
        if (!active || cycle != generation) return;
        endCycle(true);
        callback.onError();
    }

    private void finishInput(long cycle) {
        if (!active || cycle != generation) return;
        closeWriter();
        callback.onSpeechEnded();
    }

    private void endCycle(boolean cancel) {
        active = false;
        closeWriter();
        closeQuietly(readPipe);
        readPipe = null;
        writePipe = null;
        SpeechRecognizer old = recognizer;
        recognizer = null;
        if (old != null) {
            if (cancel) {
                try { old.cancel(); } catch (RuntimeException ignored) { }
            }
            try { old.destroy(); } catch (RuntimeException ignored) { }
        }
    }

    private void closeWriter() {
        OutputStream output = pcmOutput;
        pcmOutput = null;
        if (output != null) {
            try { output.close(); } catch (IOException ignored) { }
        } else {
            closeQuietly(writePipe);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try { descriptor.close(); } catch (IOException ignored) { }
    }

    @Override public void close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::close);
            return;
        }
        ++generation;
        endCycle(true);
        preroll.clear();
        prerollBytes = 0;
        writer.getQueue().clear();
        writer.shutdownNow();
        biasLoader.shutdownNow();
    }

    private final class Listener implements RecognitionListener {
        private final long cycle;

        Listener(long cycle) { this.cycle = cycle; }

        private boolean current() { return active && cycle == generation; }

        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() {
            if (current()) callback.onSpeechStarted();
        }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() {
            if (current()) finishInput(cycle);
        }
        @Override public void onError(int error) {
            if (!current()) return;
            endCycle(false);
            callback.onError();
        }
        @Override public void onResults(Bundle results) {
            if (!current()) return;
            ArrayList<String> options = results == null ? null
                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = chooseRecognitionResult(options, recognitionBiases());
            endCycle(false);
            if (text.isEmpty()) callback.onError();
            else callback.onResult(text);
        }
        @Override public void onPartialResults(Bundle partialResults) { }
        @Override public void onEvent(int eventType, Bundle params) { }
    }
}
