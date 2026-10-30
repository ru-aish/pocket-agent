package dev.pocket.agent;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.json.JSONObject;

final class GroqSpeechInput implements AutoCloseable {
    interface Listener { void status(String value); void result(String text); void failure(String message); }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean closed = new AtomicBoolean(), stopped = new AtomicBoolean();
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(12, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
    private volatile Call call;
    GroqSpeechInput(Context context, Listener listener) { this.context = context.getApplicationContext(); this.listener = listener; }
    void start() { executor.submit(this::capture); }
    void finish() { stopped.set(true); }
    private void status(String value) { main.post(() -> { if (!closed.get()) listener.status(value); }); }
    private void capture() {
        AudioRecord recorder = null; byte[] pcm = null;
        try {
            String key = new VoiceCredentialStore(context, "groq").read();
            if (key.isEmpty()) throw new IllegalStateException("Add your Groq speech key in Voice settings.");
            int size = Math.max(6400, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
            if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new IllegalStateException("Allow microphone access in Android settings.");
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone could not start.");
            ByteArrayOutputStream audio = new ByteArrayOutputStream(); byte[] frame = new byte[3200]; int voiced = 0, quiet = 0; boolean heard = false;
            recorder.startRecording(); status("Listening… Speak, then tap Finish or pause.");
            long deadline = android.os.SystemClock.elapsedRealtime() + 30000;
            while (!closed.get() && !stopped.get() && android.os.SystemClock.elapsedRealtime() < deadline) {
                int count = recorder.read(frame, 0, frame.length); if (count < 0) throw new IllegalStateException("Microphone capture failed.");
                count -= count % 2; if (count == 0) continue; audio.write(frame, 0, count);
                double energy = 0; for (int i = 0; i < count; i += 2) { short sample = (short) ((frame[i + 1] << 8) | (frame[i] & 255)); energy += (double) sample * sample; }
                if (Math.sqrt(energy / (count / 2)) > 740) { voiced++; quiet = 0; if (voiced >= 2) heard = true; } else if (heard) quiet++;
                if (heard && audio.size() >= 32000 && quiet >= 12) break;
            }
            recorder.stop(); recorder.release(); recorder = null; Arrays.fill(frame, (byte) 0);
            if (closed.get()) return;
            if (!heard) throw new IllegalStateException("No clear speech detected. Try again or choose system speech.");
            pcm = audio.toByteArray(); status("Transcribing with Groq Whisper…");
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put(new byte[]{82,73,70,70}).putInt(36 + pcm.length).put(new byte[]{87,65,86,69,102,109,116,32}).putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16).put(new byte[]{100,97,116,97}).putInt(pcm.length);
            byte[] wav = new byte[44 + pcm.length]; System.arraycopy(header.array(), 0, wav, 0, 44); System.arraycopy(pcm, 0, wav, 44, pcm.length);
            RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("model", "whisper-large-v3-turbo").addFormDataPart("language", "en").addFormDataPart("response_format", "json").addFormDataPart("file", "speech.wav", RequestBody.create(wav, MediaType.get("audio/wav"))).build();
            call = client.newCall(new Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions").header("Authorization", "Bearer " + key).post(body).build());
            if (closed.get()) { call.cancel(); Arrays.fill(wav,(byte)0); return; }
            try (okhttp3.Response response = call.execute()) {
                if (closed.get()) return;
                if (!response.isSuccessful()) throw new IllegalStateException(response.code() == 429 ? "Groq speech quota reached. Try later or use system speech." : "Groq speech request failed. Check your key and connection.");
                String payload = response.peekBody(262145).string(); if (payload.length() > 262144) throw new IllegalStateException("Groq returned an oversized response.");
                String text = new JSONObject(payload).optString("text").trim(); if (text.isEmpty() || text.length() > 2000) throw new IllegalStateException("Groq returned an empty or oversized transcript.");
                main.post(() -> { if (!closed.get()) listener.result(text); });
            } finally { Arrays.fill(wav, (byte) 0); }
        } catch (Exception error) {
            String message = error instanceof IllegalStateException ? error.getMessage() : "Groq voice could not complete. Check microphone access and your connection.";
            main.post(() -> { if (!closed.get()) listener.failure(message); });
        } finally { if (recorder != null) { try { recorder.stop(); } catch (IllegalStateException alreadyStopped) { } recorder.release(); } if (pcm != null) Arrays.fill(pcm, (byte) 0); }
    }
    @Override public void close() { if (closed.compareAndSet(false, true)) { stopped.set(true); Call active = call; if (active != null) active.cancel(); executor.shutdownNow(); client.dispatcher().executorService().shutdown(); client.connectionPool().evictAll(); } }
}
