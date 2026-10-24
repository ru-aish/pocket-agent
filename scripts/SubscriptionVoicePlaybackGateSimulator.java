package dev.pocket.agent;

public final class SubscriptionVoicePlaybackGateSimulator {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }

    private static void gapRequiresCompletion() {
        SubscriptionVoicePlaybackGate gate = new SubscriptionVoicePlaybackGate();
        gate.bindAssistantTurn("turn-A");
        gate.audio(1000);
        check(!gate.ready(4600, 3500), "Long audio pause ended active response");
        gate.audio(6000);
        check(!gate.ready(12000, 3500), "Later audio fragment was cut off");
        check(gate.finishAssistantTurn("turn-A"), "Matching turn did not finish");
        check(!gate.ready(9000, 3500), "Response finished before playout margin");
        check(gate.ready(9500, 3500), "Completed response did not release playback");
    }

    private static void resetAndNoAudio() {
        SubscriptionVoicePlaybackGate gate = new SubscriptionVoicePlaybackGate();
        gate.bindResponse("resp-1");
        gate.audio(1000);
        gate.reset();
        check(!gate.ready(20000, 3500), "Stale completion survived reset");
        check(!gate.finishResponse("resp-1"), "Old response matched after reset");
        gate.bindResponse("resp-2");
        check(gate.finishResponse("resp-2"), "Correct response not recognized");
        check(!gate.ready(20000, 3500), "No-audio response completed playback");
        gate.audio(21000);
        check(!gate.ready(23000, 3500), "Audio after completion lacked playout margin");
        check(gate.ready(24500, 3500), "Audio after completion did not drain");
    }

    private static void interruptedResponseMustNotFinishNext() {
        SubscriptionVoicePlaybackGate gate = new SubscriptionVoicePlaybackGate();
        gate.bindResponse("resp-A");
        gate.bindAssistantTurn("turn-A");
        gate.audio(1000);
        gate.reset(); // A was interrupted; B begins using the same WebRTC session.
        gate.bindResponse("resp-B");
        gate.bindAssistantTurn("turn-B");
        gate.audio(6000);
        check(!gate.finishResponse("resp-A"), "Late response A completed response B");
        check(!gate.finishAssistantTurn("turn-A"), "Late assistant turn A completed B");
        check(!gate.finishResponse(""), "Unidentified done event completed B");
        check(!gate.finishAssistantTurn(""), "Unidentified turn completed B");
        check(!gate.ready(12000, 3500), "A's completion advanced response B");
        check(gate.finishAssistantTurn("turn-B"), "Response B's turn not matched");
        check(gate.ready(12000, 3500), "Response B cannot finish after its own completion");
        gate.reset();
        check(!gate.finishResponse("resp-B"), "Completion leaked into the following response");
    }

    private static void longSpeechWatchdog() {
        SubscriptionVoicePlaybackGate gate = new SubscriptionVoicePlaybackGate();
        long start = 1000, absoluteDeadline = 601000;
        check(!gate.playbackTimedOut(59000, start, absoluteDeadline, 60000, 30000),
                "Playback timed out before its first-audio grace");
        check(gate.playbackTimedOut(61000, start, absoluteDeadline, 60000, 30000),
                "Missing first audio did not time out");
        gate.audio(55000);
        check(!gate.playbackTimedOut(61000, start, absoluteDeadline, 60000, 30000),
                "Long answer was cut off at 60 seconds");
        gate.audio(121000);
        check(!gate.playbackTimedOut(140000, start, absoluteDeadline, 60000, 30000),
                "Advancing audio did not extend watchdog");
        check(gate.playbackTimedOut(151000, start, absoluteDeadline, 60000, 30000),
                "Stalled audio did not time out");
        gate.audio(600000);
        check(gate.playbackTimedOut(601000, start, absoluteDeadline, 60000, 30000),
                "Absolute resource deadline was not enforced");
    }

    public static void main(String[] args) {
        gapRequiresCompletion();
        resetAndNoAudio();
        interruptedResponseMustNotFinishNext();
        longSpeechWatchdog();
        System.out.println("Subscription voice playback gate: 4 scenarios PASSED");
    }
}
