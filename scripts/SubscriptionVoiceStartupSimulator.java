package dev.pocket.agent;

import java.util.ArrayList;
import java.util.List;

/** Reproduces a failed power-button activation without an account or microphone. */
public final class SubscriptionVoiceStartupSimulator {
    private static final class Port implements SubscriptionVoiceLifecycle.Port {
        final List<String> statuses = new ArrayList<>();
        @Override public void connect(long epoch, boolean microphone) { }
        @Override public void close(long epoch) { }
        @Override public void sendToPi(String turnId, String utterance) { }
        @Override public void sendSpeech(String delegationId, String text) { }
        @Override public void interruptSpeech() { }
        @Override public void status(String text) { statuses.add(text); }
        @Override public void failure(String safeMessage) { statuses.add("FAILURE: " + safeMessage); }
        @Override public void announcementFailed(String id, String reason) { }
    }

    public static void main(String[] args) {
        Port port = new Port();
        SubscriptionVoiceLifecycle voice = new SubscriptionVoiceLifecycle(port);
        voice.wake(1_000);
        voice.failed(voice.epoch(), "ChatGPT voice sign-in expired; sign in again");
        if (!"FAILURE: ChatGPT voice sign-in expired; sign in again"
                .equals(port.statuses.get(port.statuses.size() - 1)))
            throw new AssertionError("A failed wake lost its actionable final state: " + port.statuses);
        voice.disconnected(voice.epoch());
        if (port.statuses.size() != 3)
            throw new AssertionError("A late disconnect reported failure twice");
        System.out.println("PASS: failed wake retains the actionable sign-in failure after cleanup");
    }
}
