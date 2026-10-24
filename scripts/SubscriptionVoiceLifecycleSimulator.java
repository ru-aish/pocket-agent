package dev.pocket.agent;

import java.util.ArrayList;
import java.util.List;

/** Deterministic regressions for startup capture, pauses, and task ownership. */
public final class SubscriptionVoiceLifecycleSimulator {
    private static final class FakePort implements SubscriptionVoiceLifecycle.Port {
        final List<String> calls = new ArrayList<>();
        boolean capture;
        @Override public boolean startInitialCapture(long epoch) {
            calls.add("capture:" + epoch); return capture;
        }
        @Override public void connect(long epoch, boolean microphone) { calls.add("connect:" + epoch + ":" + microphone); }
        @Override public void close(long epoch) { calls.add("close:" + epoch); }
        @Override public void setMicrophoneEnabled(boolean enabled) { calls.add("mic:" + enabled); }
        @Override public void sendUserContext(String text) { calls.add("context:" + text); }
        @Override public void sendToPi(String turnId, String text) { calls.add("pi:" + turnId + ":" + text); }
        @Override public void sendSpeech(String id, String text) { calls.add("speech:" + id + ":" + text); }
        @Override public void interruptSpeech() { calls.add("interrupt-speech"); }
        @Override public void status(String text) { }
        @Override public void failure(String reason) { calls.add("failure:" + reason); }
        @Override public void announcementFailed(String id, String reason) { calls.add("failed:" + id); }
        boolean has(String fragment) { return calls.stream().anyMatch(c -> c.contains(fragment)); }
        long count(String prefix) { return calls.stream().filter(c -> c.startsWith(prefix)).count(); }
        String turn(int index) {
            return calls.stream().filter(c -> c.startsWith("pi:")).toList().get(index).split(":")[1];
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void recordingReady(SubscriptionVoiceLifecycle s, FakePort p, long epoch) {
        p.calls.add("recording-ready:" + epoch);
        s.microphoneReady(epoch);
    }

    private static void connectedAndRecording(SubscriptionVoiceLifecycle s, long epoch, long now) {
        s.connected(epoch, now);
        s.microphoneReady(epoch);
    }

    private static void normalWakeAndPiHandoff() {
        FakePort p = new FakePort();
        SubscriptionVoiceLifecycle s = new SubscriptionVoiceLifecycle(p);
        s.wake(100); s.wake(200);
        check(p.count("connect:") == 1, "duplicate wake opened twice");
        connectedAndRecording(s, 999, 210);
        check(s.state() == SubscriptionVoiceLifecycle.State.CONNECTING, "stale connect accepted");
        connectedAndRecording(s, 1, 300);
        s.delegated(1, "delegate-1", "Open the browser");
        check(s.state() == SubscriptionVoiceLifecycle.State.WAITING_PI, "Pi dispatch missing");
        s.tick(200000);
        check(s.state() == SubscriptionVoiceLifecycle.State.WAITING_PI, "silence stopped active Pi");
        String turn = p.turn(0);
        check(java.util.UUID.fromString(turn).toString().equals(turn), "Pi voice turn must be a UUID");
        s.delegated(1, "delegate-1", "duplicate");
        check(p.count("pi:") == 1, "duplicate request dispatched");
        s.piDelta("wrong-id", "not spoken");
        s.piDelta(turn, "Done opening browser.");
        s.piFinished("wrong-id");
        check(p.count("speech:") == 0, "stale Pi end spoke");
        s.piFinished(turn);
        check(p.has("speech:delegate-1:Done opening browser."), "Pi output missing");
        s.playbackFinished(1, 200500);
        s.tick(3_600_000);
        check(s.state() == SubscriptionVoiceLifecycle.State.LISTENING, "follow-up silence dropped microphone");
        s.close();
        check(p.count("close:") == 1, "explicit stop did not release transport");
    }

    private static void firstTranscriptBeforeConnection() {
        FakePort p = new FakePort(); p.capture = true;
        SubscriptionVoiceLifecycle s = new SubscriptionVoiceLifecycle(p);
        s.wake(0);
        check(p.calls.get(0).equals("capture:1"), "OAuth began before capture");
        s.initialCaptured(99, "stale");
        check(p.count("pi:") == 0, "stale recognizer submitted text");
        s.initialCaptured(1, "Find the blue button");
        check(s.connecting(1) && p.has("Find the blue button"), "first transcript waited for WebRTC");
        s.initialCaptured(1, "duplicate");
        check(p.count("pi:") == 1, "initial result submitted twice");
        s.piDelta(p.turn(0), "The button is on the left.");
        s.piFinished(p.turn(0));
        check(p.count("speech:") == 0, "Pi result was lost to an unready transport");
        s.connected(1, 9000);
        check(p.count("speech:") == 0, "first result preceded native recording start");
        s.microphoneReady(999);
        check(p.count("speech:") == 0, "stale recording callback released first result");
        recordingReady(s, p, 1);
        check(p.has("context:Find the blue button"), "initial request absent from live context");
        check(p.has("speech:null:The button is on the left."), "buffered result not spoken");
        int enabled = p.calls.indexOf("mic:true");
        int recording = p.calls.indexOf("recording-ready:1");
        int spoken = p.calls.indexOf("speech:null:The button is on the left.");
        check(enabled >= 0 && recording > enabled && spoken > recording,
            "speakable context sent before a mic enable and recording-start callback");
    }

    private static void connectionBeforeFirstTranscript() {
        FakePort p = new FakePort(); p.capture = true;
        SubscriptionVoiceLifecycle s = new SubscriptionVoiceLifecycle(p);
        s.wake(0); connectedAndRecording(s, 1, 3000);
        check(p.has("mic:false") && !p.has("mic:true"), "native mic competed with Android recognizer");
        s.tick(120_000);
        check(s.initialCaptureActive(), "listening timeout interrupted initial transcription");
        s.initialCaptured(1, "Read my next event");
        check(p.has("mic:true") && p.count("pi:") == 1, "recognizer did not hand microphone to WebRTC");
        s.piDelta(p.turn(0), "Your next event is tomorrow.");
        s.piFinished(p.turn(0));
        check(p.count("speech:") == 0, "reply bypassed delayed recording readiness");
        recordingReady(s, p, 1);
        check(p.has("speech:null:Your next event is tomorrow."), "first response not spoken");
    }

    private static void captureFailureAndConnectionTimeout() {
        FakePort p = new FakePort(); p.capture = true;
        SubscriptionVoiceLifecycle s = new SubscriptionVoiceLifecycle(p);
        s.wake(0); connectedAndRecording(s, 1, 100);
        s.initialCaptureUnavailable(1);
        check(p.has("mic:true") && p.count("pi:") == 0, "failed STT invented a request or blocked live capture");
        s.initialCaptured(1, "late partial");
        check(p.count("pi:") == 0, "late recognizer result executed after fallback");
        s.close(); s.wake(1000);
        s.initialCaptured(2, "Work while voice connects");
        s.tick(46_000);
        check(s.state() == SubscriptionVoiceLifecycle.State.CLOSED && p.count("pi:") == 1,
            "connection timeout was disabled by an active Pi turn");
        check(p.has("failure:Voice connection timed out"), "connection timeout not reported");
        s.piFinished(p.turn(0));
        connectedAndRecording(s, 2, 50_000);
        check(s.state() == SubscriptionVoiceLifecycle.State.CLOSED && p.count("speech:") == 0,
            "late callbacks resurrected disconnected voice");
    }

    private static void speechCannotCancelPiAndRequestsQueue() {
        FakePort p = new FakePort();
        SubscriptionVoiceLifecycle s = new SubscriptionVoiceLifecycle(p);
        s.wake(0); connectedAndRecording(s, 1, 100);
        s.delegated(1, "a", "Perform a long task");
        s.speechStarted(1, 200);
        s.inputInterrupted(1, 201);
        s.transcriptActivity(1, "And check the calendar", 300);
        check(s.state() == SubscriptionVoiceLifecycle.State.WAITING_PI, "raw speech detached Pi result delivery");
        s.delegated(1, "b", "Also check the calendar");
        check(p.count("pi:") == 2, "new complete request was lost");
        s.piDelta(p.turn(1), "Second result."); s.piFinished(p.turn(1));
        s.piDelta(p.turn(0), "First result."); s.piFinished(p.turn(0));
        check(p.count("speech:") == 0, "speech output started while VAD said user was speaking");
        s.speechStopped(1, 1000);
        check(p.has("speech:a:First result."), "earlier task's result was discarded");
        s.playbackFinished(1, 2000);
        check(p.has("speech:b:Second result."), "queued result was lost or used wrong delegation ID");
        s.playbackFinished(1, 3000);
        s.tick(3_600_000);
        check(s.state() == SubscriptionVoiceLifecycle.State.LISTENING, "task completion closed voice");
    }

    private static void disconnectOnlyDetachesVoice() {
        FakePort p = new FakePort();
        SubscriptionVoiceLifecycle s = new SubscriptionVoiceLifecycle(p);
        s.wake(0); connectedAndRecording(s, 1, 100);
        s.delegated(1, "a", "Keep working");
        String turn = p.turn(0);
        s.disconnected(1);
        check(s.state() == SubscriptionVoiceLifecycle.State.CLOSED && p.count("close:") == 1,
            "network failure didn't release voice resources");
        // Port deliberately has no cancel-Pi operation. Teardown cannot abort the task.
        s.wake(200); connectedAndRecording(s, 2, 300);
        s.microphoneReady(1);
        s.piDelta(turn, "Old result"); s.piFinished(turn);
        check(p.count("speech:") == 0, "old task result leaked into reopened voice session");
    }

    private static void announcementsAndBargeIn() {
        FakePort p = new FakePort();
        SubscriptionVoiceLifecycle s = new SubscriptionVoiceLifecycle(p);
        check(!s.announce("empty", " ", true, 0), "empty speech accepted");
        s.announce("hello", "Hello user", true, 0); connectedAndRecording(s, 1, 100);
        check(p.count("capture:") == 0, "announcement started initial speech recognizer");
        s.announce("bye", "Goodbye", false, 101);
        s.playbackFinished(1, 200); s.playbackFinished(1, 300);
        check(s.state() == SubscriptionVoiceLifecycle.State.CLOSED, "no-listen announcement stayed open");
        s.announce("interrupted", "Reminder", true, 400); connectedAndRecording(s, 2, 500);
        s.transcriptActivity(2, "Stop", 600);
        check(p.has("failed:interrupted") && p.count("interrupt-speech") == 1,
            "barge-in failed to interrupt playback");
        s.tick(3_600_000);
        check(s.state() == SubscriptionVoiceLifecycle.State.LISTENING, "pause after barge-in closed microphone");
    }

    private static void outputOnlyAnnouncement() {
        FakePort p = new FakePort();
        SubscriptionVoiceLifecycle s = new SubscriptionVoiceLifecycle(p);
        s.announce("speak", "Only speak, do not listen", false, 0);
        check(p.has("connect:1:false"), "output-only session requested microphone");
        check(!s.announce("listen", "Ask a question", true, 50), "output-only session silently authorized mic");
        connectedAndRecording(s, 1, 100);
        check(!p.has("mic:true"), "output-only announcement enabled microphone");
        s.playbackFinished(1, 130);
        check(s.state() == SubscriptionVoiceLifecycle.State.CLOSED && !p.has("failed:speak"),
            "output-only lifecycle did not finish");
    }

    public static void main(String[] args) {
        normalWakeAndPiHandoff();
        firstTranscriptBeforeConnection();
        connectionBeforeFirstTranscript();
        captureFailureAndConnectionTimeout();
        speechCannotCancelPiAndRequestsQueue();
        disconnectOnlyDetachesVoice();
        announcementsAndBargeIn();
        outputOnlyAnnouncement();
        System.out.println("Subscription voice lifecycle simulator: 8 scenarios PASSED");
    }
}
