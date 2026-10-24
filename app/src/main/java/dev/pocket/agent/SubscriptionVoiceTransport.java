package dev.pocket.agent;

import android.content.Context;

/**
 * Voice-service-facing adapter for SubscriptionWebRtcTransport. Each connection
 * owns one native peer, microphone and sideband, and releases its entire worker
 * when that epoch closes. The Pi lifecycle remains the exclusive task harness.
 */
final class SubscriptionVoiceTransport implements AutoCloseable {
    interface Callback {
        void onConnected(long epoch);
        void onDelegation(long epoch, String id, String text);
        void onPlaybackFinished(long epoch);
        void onActivity(long epoch);
        default void onSpeechStarted(long epoch) {}
        default void onSpeechStopped(long epoch) {}
        default void onMicrophoneReady(long epoch) {}
        void onError(long epoch, String safeMessage);
        void onDisconnected(long epoch);
        default void onInterrupted(long epoch) {}
        default void onTranscript(long epoch, String text) {}
    }

    private final Context context;
    private final Callback callback;
    private SubscriptionWebRtcTransport transport;
    private long activeEpoch = -1;
    private boolean disposed;

    SubscriptionVoiceTransport(Context context, Callback callback) {
        if (context == null || callback == null)
            throw new IllegalArgumentException("Missing subscription voice dependency");
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    synchronized void connect(long epoch, String accessToken, String accountId) {
        if (disposed || epoch < 0) return;
        if (transport != null && activeEpoch == epoch) return;
        releaseCurrent();
        // Validate before allocating JNI, network, or microphone resources.
        SubscriptionWebRtcTransport.Credentials fresh =
                new SubscriptionWebRtcTransport.Credentials(accessToken, accountId);
        activeEpoch = epoch;
        SubscriptionWebRtcTransport next =
                new SubscriptionWebRtcTransport(context, () -> fresh, new SubscriptionWebRtcTransport.Listener() {
                    @Override public void onConnected(long id) {
                        if (matches(id)) callback.onConnected(id);
                    }
                    @Override public void onDelegation(long id, String requestId, String transcript) {
                        if (matches(id)) callback.onDelegation(id, requestId, transcript);
                    }
                    @Override public void onPlaybackFinished(long id) {
                        if (matches(id)) callback.onPlaybackFinished(id);
                    }
                    @Override public void onActivity(long id) {
                        if (matches(id)) callback.onActivity(id);
                    }
                    @Override public void onSpeechStarted(long id) {
                        if (matches(id)) callback.onSpeechStarted(id);
                    }
                    @Override public void onSpeechStopped(long id) {
                        if (matches(id)) callback.onSpeechStopped(id);
                    }
                    @Override public void onMicrophoneReady(long id) {
                        if (matches(id)) callback.onMicrophoneReady(id);
                    }
                    @Override public void onInterrupted(long id) {
                        if (matches(id)) callback.onInterrupted(id);
                    }
                    @Override public void onTranscript(long id, String text) {
                        if (matches(id)) callback.onTranscript(id, text);
                    }
                    @Override public void onError(long id, String safeMessage) {
                        if (!matches(id)) return;
                        try { callback.onError(id, safeMessage); }
                        finally { close(id); }
                    }
                    @Override public void onDisconnected(long id) {
                        if (!matches(id)) return;
                        try { callback.onDisconnected(id); }
                        finally { close(id); }
                    }
                });
        transport = next;
        next.connect(epoch);
    }

    private synchronized boolean matches(long epoch) {
        return !disposed && epoch == activeEpoch && transport != null;
    }

    synchronized void sendSpeech(String delegationId, String text) {
        if (!disposed && transport != null)
            transport.sendSpeech(activeEpoch, delegationId, text);
    }

    synchronized void setMicrophoneEnabled(boolean enabled) {
        if (!disposed && transport != null)
            transport.setMicrophoneEnabled(activeEpoch, enabled);
    }

    synchronized void sendUserContext(String text) {
        if (!disposed && transport != null)
            transport.sendUserContext(activeEpoch, text);
    }

    synchronized void interruptSpeech() {
        if (!disposed && transport != null)
            transport.interruptSpeech(activeEpoch);
    }

    synchronized void close(long epoch) {
        if (epoch == activeEpoch) releaseCurrent();
    }

    private void releaseCurrent() {
        SubscriptionWebRtcTransport previous = transport;
        long epoch = activeEpoch;
        transport = null;
        activeEpoch = -1;
        if (previous != null) {
            previous.close(epoch);
            previous.close();
        }
    }

    @Override public synchronized void close() {
        if (disposed) return;
        disposed = true;
        releaseCurrent();
    }
}
