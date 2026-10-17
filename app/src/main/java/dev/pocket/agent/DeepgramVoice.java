package dev.pocket.agent;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

/**
 * Streaming full-duplex transport. The microphone remains acquired during Pi/TTS output
 * so that Flux can trigger a genuine speech-driven interruption.
 */
final class DeepgramVoice implements AutoCloseable {
    interface PcmObserver { void onPcm(byte[] samples, int length); }

    interface Callback {
        void onUtterance(String text);
        default void onUtterance(String text, long spokenDurationMs) { onUtterance(text); }
        void onSpeechStarted();
        void onEmptyTurn();
        void onInterim(String text);
        void onPlaybackFinished();
        void onFirstAudio();
        void onStatus(String message);
        void onStreamReady();
    }

    private static final long FLUSH_TIMEOUT_MS = 30000L;
    private static final long CONNECT_RETRY_MS = 1500L;
    private static final int MAX_CAPTURE_BYTES = 2_560_000; // 80 seconds of mono PCM16.
    private static final long MAX_SOCKET_QUEUE_BYTES = 256_000L;
    private static final String STT_URL = "wss://api.deepgram.com/v2/listen?model=flux-general-en&encoding=linear16&sample_rate=16000";
    private static final String TTS_URL = "wss://api.deepgram.com/v1/speak?model=aura-2-thalia-en&encoding=linear16&sample_rate=24000";
    private final Context context;
    private final Callback callback;
    private final OkHttpClient client;
    private final String key;
    interface SocketConnector { WebSocket open(Request request, WebSocketListener listener); }
    private final SocketConnector connector;
    private final String sttUrl;
    private final ExecutorService playerQueue = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "PocketVoiceOutput");
        thread.setDaemon(true);
        return thread;
    });
    private final VoiceTextChunker chunker = new VoiceTextChunker();
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable flushTimeout;
    private final ArrayDeque<String> speechQueue = new ArrayDeque<>();
    private volatile boolean closed;
    private volatile boolean listening;
    private volatile boolean responseOpen;
    private volatile long playbackEpoch;
    private WebSocket sttSocket, ttsSocket;
    private final PcmCaptureQueue captured = new PcmCaptureQueue(MAX_CAPTURE_BYTES);
    private boolean sttReady, streamReadyNotified;
    private int initialCaptureBytesRemaining;
    private boolean ttsReady, hasSpeech, ttsFailed, firstAudio, speakerStarted;
    private volatile boolean waitingForFlush, waitingForClear;
    private AudioRecord microphone;
    private volatile PcmObserver pcmObserver;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private AudioTrack speaker;
    private long totalFrames;
    private int pendingAudioBytes;
    private final Runnable reconnectSpeech = () -> {
        synchronized (DeepgramVoice.this) {
            if (listening && !closed && sttSocket == null) connectSpeech();
        }
    };

    DeepgramVoice(Context context, String key, Callback callback) {
        this(context, key, callback, null, STT_URL);
    }

    DeepgramVoice(Context context, String key, Callback callback, SocketConnector connector, String sttUrl) {
        this.context = context.getApplicationContext();
        this.key = key;
        this.callback = callback;
        this.client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(12, TimeUnit.SECONDS).build();
        this.connector = connector == null ? client::newWebSocket : connector;
        this.sttUrl = sttUrl;
    }

    /** Queue the last locally captured wake-word frames before live microphone frames.
     * One-time session preroll is memory-only and never sent before Deepgram connects. */
    synchronized void primeWakePcm(byte[] preroll) {
        if (closed || listening || preroll == null || preroll.length == 0) return;
        if (preroll.length > 32000 || preroll.length % 2 != 0)
            throw new IllegalArgumentException("Invalid wake PCM preroll");
        for (int offset = 0; offset < preroll.length; offset += 2560) {
            int length = Math.min(2560, preroll.length - offset);
            byte[] frame = Arrays.copyOfRange(preroll, offset, offset + length);
            if (!captured.offer(frame, length)) throw new IllegalStateException("Wake preroll queue full");
        }
    }

    synchronized void startOutput() {
        if (closed || speakerStarted) return;
        speakerStarted = true;
        connectSpeaker();
    }

    void start() {
        if (closed) return;
        listen();
        if (listening) startOutput();
    }

    private Request request(String url) {
        return new Request.Builder().url(url).header("Authorization", "Token " + key).build();
    }

    private void connectSpeaker() {
        ttsSocket = client.newWebSocket(request(TTS_URL), new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                synchronized (DeepgramVoice.this) {
                    if (closed || ttsSocket != webSocket) {
                        webSocket.close(1000, "stale"); return;
                    }
                    if (waitingForClear) webSocket.send("{\"type\":\"Clear\"}");
                    else {
                        ttsReady = true;
                        while (!speechQueue.isEmpty()) webSocket.send(speechQueue.removeFirst());
                    }
                }
            }
            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                synchronized (DeepgramVoice.this) {
                    if (closed || ttsSocket != webSocket) return;
                    handleAudio(bytes.toByteArray());
                }
            }
            @Override public void onMessage(WebSocket webSocket, String text) {
                try {
                    String kind = new JSONObject(text).optString("type");
                    boolean flushComplete = false;
                    synchronized (DeepgramVoice.this) {
                        if (closed || ttsSocket != webSocket) return;
                        if ("Cleared".equals(kind) && waitingForClear) {
                            waitingForClear = false;
                            ttsReady = true;
                            while (!speechQueue.isEmpty()) {
                                if (!webSocket.send(speechQueue.removeFirst())) {
                                    speakerLost(webSocket); return;
                                }
                            }
                        } else if ("Flushed".equals(kind) && waitingForFlush && !waitingForClear) {
                            cancelFlushTimeout();
                            flushComplete = true;
                        }
                    }
                    if (flushComplete) finishPlayback();
                } catch (Exception ignored) { }
            }
            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
                speakerLost(webSocket);
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                speakerLost(webSocket);
            }
            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                speakerLost(webSocket);
            }
        });
    }

    private void cancelFlushTimeout() {
        if (flushTimeout != null) {
            timeoutHandler.removeCallbacks(flushTimeout);
            flushTimeout = null;
        }
    }

    /** Normal WebSocket closure must complete outstanding speech, not pin the mic forever. */
    private void speakerLost(WebSocket webSocket) {
        boolean notify;
        synchronized (this) {
            if (closed || ttsSocket != webSocket) return;
            ttsSocket = null;
            ttsReady = false;
            ttsFailed = true;
            waitingForClear = false;
            notify = waitingForFlush;
            waitingForFlush = false;
            cancelFlushTimeout();
            speechQueue.clear();
            ++playbackEpoch;
            stopSpeakerLocked();
        }
        callback.onStatus("Speech output unavailable; check Deepgram credentials or network");
        if (notify) callback.onPlaybackFinished();
    }

    private void stopSpeakerLocked() {
        if (speaker == null) return;
        try { speaker.pause(); speaker.flush(); } catch (Exception ignored) { }
        try { speaker.release(); } catch (Exception ignored) { }
        speaker = null;
        totalFrames = 0;
    }

    private void armFlushTimeout() {
        cancelFlushTimeout();
        final long generation = playbackEpoch;
        Runnable timeout = () -> {
            synchronized (DeepgramVoice.this) {
                if (closed || !waitingForFlush || generation != playbackEpoch) return;
                waitingForFlush = false;
                ttsReady = false;
                ttsFailed = true;
                speechQueue.clear();
                ++playbackEpoch;
                stopSpeakerLocked();
                WebSocket stalled = ttsSocket;
                ttsSocket = null;
                if (stalled != null) stalled.cancel();
            }
            callback.onStatus("Speech synthesis timed out; continuing to listen");
            callback.onPlaybackFinished();
        };
        flushTimeout = timeout;
        timeoutHandler.postDelayed(timeout, FLUSH_TIMEOUT_MS);
    }

    void setPcmObserver(PcmObserver observer) {
        pcmObserver = observer;
    }

    synchronized void listen() {
        if (closed || listening) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            callback.onStatus("Microphone permission required. Open Pocket Agent settings.");
            return;
        }
        listening = true;
        // Android microphone acquisition comes before DNS, TLS, or the WebSocket handshake.
        if (!startMicrophone()) return;
        callback.onStatus("Capturing audio · connecting to speech recognition");
        connectSpeech();
    }

    private void connectSpeech() {
        try {
            sttSocket = connector.open(request(sttUrl), new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) {
                    synchronized (DeepgramVoice.this) {
                        if (closed || !listening || sttSocket != socket) {
                            socket.close(1000, "not listening");
                            return;
                        }
                        initialCaptureBytesRemaining = captured.queuedBytes();
                        sttReady = true;
                        DeepgramVoice.this.notifyAll();
                    }
                }

                @Override public void onMessage(WebSocket socket, String text) {
                    if (!listening || closed || sttSocket != socket) return;
                    try {
                        JSONObject packet = new JSONObject(text);
                        String finalText = VoiceTranscript.completedTurn(packet);
                        if (finalText != null) {
                            callback.onUtterance(finalText, VoiceTranscript.spokenDurationMs(packet));
                        } else if (VoiceTranscript.startedTurn(packet)) {
                            callback.onSpeechStarted();
                        } else if (VoiceTranscript.emptyCompletedTurn(packet)) {
                            callback.onEmptyTurn();
                        } else if ("TurnInfo".equals(packet.optString("type"))) {
                            callback.onInterim(packet.optString("transcript", ""));
                        } else if ("Error".equals(packet.optString("type"))) {
                            stopListening();
                            callback.onStatus("Speech recognition stopped; check Deepgram or the network");
                        }
                    } catch (Exception ignored) { }
                }

                @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                    int status = response == null ? 0 : response.code();
                    if (status >= 400 && status < 500 && status != 408 && status != 429) {
                        synchronized (DeepgramVoice.this) {
                            if (sttSocket != socket || closed) return;
                            stopListening();
                        }
                        callback.onStatus("Speech recognition rejected request (HTTP " + status + ")");
                        return;
                    }
                    speechConnectionLost(socket);
                }
                @Override public void onClosing(WebSocket socket, int code, String reason) {
                    socket.close(1000, null);
                    speechConnectionLost(socket);
                }
                @Override public void onClosed(WebSocket socket, int code, String reason) {
                    speechConnectionLost(socket);
                }
            });
        } catch (RuntimeException error) {
            scheduleSpeechReconnect();
        }
    }

    private void scheduleSpeechReconnect() {
        if (closed || !listening) return;
        sttReady = false;
        sttSocket = null;
        timeoutHandler.removeCallbacks(reconnectSpeech);
        timeoutHandler.postDelayed(reconnectSpeech, CONNECT_RETRY_MS);
        callback.onStatus("Connecting to speech recognition · audio safely buffered");
    }

    private void speechConnectionLost(WebSocket socket) {
        synchronized (this) {
            if (closed || !listening || sttSocket != socket) return;
            if (!sttReady) {
                scheduleSpeechReconnect();
                return;
            }
            stopListening();
        }
        callback.onStatus("Speech recognition disconnected; start a new voice session");
    }

    private boolean startMicrophone() {
        try {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Microphone permission revoked");
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Microphone unsupported");
            AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 4096));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                throw new IllegalStateException("Microphone unavailable");
            }
            microphone = record;
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(record.getAudioSessionId());
                    if (echoCanceler != null) echoCanceler.setEnabled(true);
                }
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(record.getAudioSessionId());
                    if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
                }
            } catch (RuntimeException unavailable) {
                callback.onStatus("Device echo suppression unavailable");
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Microphone failed to start");
            Thread publisher = new Thread(this::publishMicrophone, "PocketVoiceUpload");
            publisher.setDaemon(true);
            publisher.start();
            Thread capture = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                byte[] samples = new byte[2560]; // 80ms of Flux PCM at 16 kHz.
                while (listening && !closed) {
                    int size = record.read(samples, 0, samples.length);
                    if (size <= 0) {
                        if (listening) callback.onStatus("Microphone capture interrupted");
                        break;
                    }
                    PcmObserver observer = pcmObserver;
                    if (observer != null) {
                        try { observer.onPcm(samples, size); }
                        catch (RuntimeException ignored) { pcmObserver = null; }
                    }
                    synchronized (DeepgramVoice.this) {
                        if (!listening || closed) break;
                        if (!captured.offer(samples, size)) {
                            callback.onStatus("Speech recognition backlog exceeded 80 seconds; start again");
                            break;
                        }
                        if (sttReady) DeepgramVoice.this.notifyAll();
                    }
                }
                stopListening();
            }, "PocketVoiceMicrophone");
            capture.setDaemon(true);
            capture.start();
            return true;
        } catch (Exception error) {
            stopListening();
            if (!closed) callback.onStatus("Microphone is unavailable");
            return false;
        }
    }

    private void publishMicrophone() {
        // Hold newly captured frames while the socket flushes the fixed backlog
        // present at onOpen. Live samples cannot keep the readiness barrier open.
        try {
            while (true) {
                boolean caughtUp = false;
                synchronized (this) {
                    while (listening && !closed) {
                        if (!sttReady) {
                            wait();
                            continue;
                        }
                        if (!streamReadyNotified && initialCaptureBytesRemaining == 0) {
                            if (sttSocket.queueSize() == 0L) {
                                streamReadyNotified = true;
                                caughtUp = true;
                                break;
                            }
                            wait(40L);
                            continue;
                        }
                        if (captured.isEmpty()) {
                            wait();
                            continue;
                        }
                        if (sttSocket.queueSize() >= MAX_SOCKET_QUEUE_BYTES) {
                            wait(40L);
                            continue;
                        }
                        byte[] frame = captured.peek();
                        if (frame == null) continue;
                        if (!sttSocket.send(ByteString.of(frame))) {
                            stopListening();
                            callback.onStatus("Speech recognition upload interrupted");
                            return;
                        }
                        captured.removeFirst();
                        if (!streamReadyNotified) initialCaptureBytesRemaining -= frame.length;
                        break;
                    }
                    if (!listening || closed) return;
                }
                if (caughtUp) callback.onStreamReady();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stopListening();
        } catch (RuntimeException failed) {
            stopListening();
            if (!closed) callback.onStatus("Speech recognition upload interrupted");
        }
    }

    synchronized int bufferedInputBytes() { return captured.queuedBytes(); }

    synchronized boolean isCapturing() {
        return microphone != null && microphone.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
    }

    synchronized void stopListening() {
        if (!listening && microphone == null && sttSocket == null && captured.isEmpty()) return;
        listening = false;
        sttReady = false;
        streamReadyNotified = false;
        initialCaptureBytesRemaining = 0;
        timeoutHandler.removeCallbacks(reconnectSpeech);
        captured.clear();
        notifyAll();
        AudioRecord record = microphone;
        microphone = null;
        if (echoCanceler != null) { echoCanceler.release(); echoCanceler = null; }
        if (noiseSuppressor != null) { noiseSuppressor.release(); noiseSuppressor = null; }
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) { }
            record.release();
        }
        WebSocket socket = sttSocket;
        sttSocket = null;
        if (socket != null) socket.close(1000, "utterance complete");
    }

    synchronized void startReply() {
        if (closed) return;
        cancelFlushTimeout();
        chunker.clear();
        responseOpen = true;
        waitingForFlush = false;
        totalFrames = 0;
        hasSpeech = false;
        firstAudio = false;
    }

    /** Abort locally and remotely, discarding both buffered PCM and unsent synthesis text. */
    synchronized void interruptReply() {
        if (closed) return;
        ++playbackEpoch;
        cancelFlushTimeout();
        responseOpen = false;
        waitingForFlush = false;
        chunker.clear();
        speechQueue.clear();
        if (ttsSocket != null && ttsReady) {
            ttsReady = false;
            waitingForClear = true;
            ttsSocket.send("{\"type\":\"Clear\"}");
        } else if (!waitingForClear) {
            // No synthesis was sent, or connection is still opening.
            // Do not enqueue a duplicate Clear that might erase the next reply.
            waitingForClear = false;
        }
        stopSpeakerLocked();
        firstAudio = false;
        hasSpeech = false;
    }

    synchronized void appendReply(String text) {
        if (!responseOpen || closed) return;
        for (String clause : chunker.append(text)) sendSpeech(clause);
    }

    private void sendSpeech(String text) {
        if (text.isEmpty()) return;
        hasSpeech = true;
        try { queueTts(new JSONObject().put("type", "Speak").put("text", text).toString()); }
        catch (Exception error) { callback.onStatus("Unable to synthesize this response"); }
    }

    private void queueTts(String json) {
        if (closed) return;
        if (ttsReady && !waitingForClear && ttsSocket != null) {
            WebSocket socket = ttsSocket;
            if (!socket.send(json)) speakerLost(socket);
        }
        else if (speechQueue.size() < 64) speechQueue.addLast(json);
        else callback.onStatus("Speech synthesis queue full");
    }

    synchronized void finishReply() {
        if (!responseOpen || closed) return;
        responseOpen = false;
        String tail = chunker.drain();
        if (!tail.isEmpty()) sendSpeech(tail);
        if (!hasSpeech || ttsFailed) {
            // Tool-only turns have no spoken text. Avoid waiting for a nonexistent flush.
            callback.onPlaybackFinished();
            return;
        }
        waitingForFlush = true;
        armFlushTimeout();
        queueTts("{\"type\":\"Flush\"}");
    }

    private void handleAudio(byte[] audio) {
        if (closed || waitingForClear || (!waitingForFlush && !responseOpen)) return;
        final long generation = playbackEpoch;
        synchronized (this) {
            if (!firstAudio) { firstAudio = true; callback.onFirstAudio(); }
        }
        synchronized (this) {
            if (pendingAudioBytes + audio.length > 1_000_000) {
                callback.onStatus("Speech output buffer overrun");
                return;
            }
            pendingAudioBytes += audio.length;
        }
        playerQueue.execute(() -> {
            try {
                if (closed || generation != playbackEpoch) return;
                synchronized (DeepgramVoice.this) {
                    if (closed || generation != playbackEpoch) return;
                    if (speaker == null) {
                        speaker = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(24000)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(24000)
                            .setTransferMode(AudioTrack.MODE_STREAM).build();
                        if (speaker.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("Speaker unavailable");
                        speaker.play();
                    }
                }
                int offset = 0;
                AudioTrack track = speaker;
                while (offset < audio.length && !closed && generation == playbackEpoch) {
                    int count = track.write(audio, offset, audio.length - offset, AudioTrack.WRITE_BLOCKING);
                    if (count <= 0) break;
                    offset += count;
                    totalFrames += count / 2;
                }
            } catch (Exception error) {
                if (!closed && generation == playbackEpoch) callback.onStatus("Speech playback failed");
            } finally {
                synchronized (DeepgramVoice.this) { pendingAudioBytes -= audio.length; }
            }
        });
    }

    private void finishPlayback() {
        final long generation = playbackEpoch;
        playerQueue.execute(() -> {
            AudioTrack track;
            long expected;
            synchronized (this) { if (closed || generation != playbackEpoch || !waitingForFlush) return; track = speaker; expected = totalFrames; }
            if (track != null) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!closed && generation == playbackEpoch && Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < expected
                    && System.nanoTime() < deadline) {
                    try { Thread.sleep(20); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
                synchronized (this) {
                    if (speaker == track && generation == playbackEpoch) {
                        try { track.stop(); } catch (Exception ignored) { }
                        track.release(); speaker = null;
                    }
                }
            }
            synchronized (this) { if (closed || generation != playbackEpoch) return; waitingForFlush = false; }
            callback.onPlaybackFinished();
        });
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        pcmObserver = null;
        ++playbackEpoch;
        cancelFlushTimeout();
        stopListening();
        if (ttsSocket != null) { ttsSocket.cancel(); ttsSocket = null; }
        ttsReady = false;
        speechQueue.clear();
        playerQueue.shutdownNow();
        if (speaker != null) { speaker.pause(); speaker.flush(); speaker.release(); speaker = null; }
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
