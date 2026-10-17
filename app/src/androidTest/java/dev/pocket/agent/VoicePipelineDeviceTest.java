package dev.pocket.agent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Intent;
import android.content.ComponentName;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VoicePipelineDeviceTest {
    @Test public void agentSpeechIntentIsPrivateValidatedAndKeepsOptionalListening() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject event = new JSONObject().put("id", "f8fcb5b1-7f09-4d38-b28c-d07e7741ec89")
            .put("message", "Hello from a background task").put("listenAfter", false);
        VoiceAnnouncement item = VoiceAnnouncement.fromEvent(event);
        Intent intent = VoiceAnnouncement.intent(context, item);
        assertEquals(context.getPackageName(), intent.getComponent().getPackageName());
        assertEquals(VoiceListeningService.ACTION_ANNOUNCE, intent.getAction());
        VoiceAnnouncement restored = VoiceAnnouncement.fromIntent(intent);
        assertEquals("Hello from a background task", restored.message);
        assertFalse(restored.listenAfter);
        assertTrue(VoiceAnnouncement.fromEvent(new JSONObject().put("id", event.getString("id"))
            .put("message", "Follow up")).listenAfter);
        try {
            VoiceAnnouncement.fromEvent(new JSONObject().put("id", event.getString("id"))
                .put("message", " "));
            fail("Blank speech must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            VoiceAnnouncement.fromEvent(new JSONObject().put("id", "invalid")
                .put("message", "bad request"));
            fail("Invalid ids must be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void internalAnnouncementServiceIsDispatchableOnDevice() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var credentials = context.getSharedPreferences("voice-credentials", Context.MODE_PRIVATE);
        String previous = credentials.getString("secret", null);
        boolean runtimeAlreadyRunning = AgentService.isRunning();
        Assume.assumeFalse("Do not interrupt an existing voice session", VoiceListeningService.isRunning());
        try {
            new VoiceCredentialStore(context).save("invalid-emulator-test-key");
            var request = VoiceAnnouncement.fromEvent(new JSONObject()
                .put("id", "1f05d6a1-cddb-43ac-ac2e-54842f1066f1")
                .put("message", "Agent-initiated speech test")
                .put("listenAfter", false));
            Intent announcement = VoiceAnnouncement.intent(context, request);
            ComponentName started = context.startForegroundService(announcement);
            assertNotNull("Android must accept internal agent speech dispatch", started);
            assertEquals(context.getPackageName(), started.getPackageName());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        } finally {
            context.stopService(new Intent(context, VoiceListeningService.class));
            if (!runtimeAlreadyRunning) context.stopService(new Intent(context, AgentService.class));
            var editor = credentials.edit();
            if (previous == null) editor.remove("secret"); else editor.putString("secret", previous);
            assertTrue("Restore voice credential state after the test", editor.commit());
        }
    }

    @Test public void fluxOnlyDispatchesConfirmedNonemptyTurns() throws Exception {
        assertNull(VoiceTranscript.completedTurn(new JSONObject().put("type", "Connected")));
        assertNull(VoiceTranscript.completedTurn(new JSONObject().put("type", "TurnInfo")
            .put("event", "Update").put("transcript", "I want")));
        assertNull(VoiceTranscript.completedTurn(new JSONObject().put("type", "TurnInfo")
            .put("event", "EagerEndOfTurn").put("transcript", "I want")));
        assertNull(VoiceTranscript.completedTurn(new JSONObject().put("type", "TurnInfo")
            .put("event", "EndOfTurn").put("transcript", "  ")));
        assertEquals("I want a reminder", VoiceTranscript.completedTurn(new JSONObject()
            .put("type", "TurnInfo").put("event", "EndOfTurn").put("transcript", " I want a reminder ")));
    }

    @Test public void fluxWordTimestampsMeasureSpokenAudioNotEndpointWindow() throws Exception {
        JSONObject turn = new JSONObject().put("type", "TurnInfo").put("event", "EndOfTurn")
            .put("audio_window_start", 0.0).put("audio_window_end", 4.8)
            .put("words", new JSONArray()
                .put(new JSONObject().put("word", "call").put("start", 1.10).put("end", 1.32))
                .put(new JSONObject().put("word", "Tanmay").put("start", 1.40).put("end", 1.94)));
        assertEquals(840L, VoiceTranscript.spokenDurationMs(turn));

        assertEquals(-1L, VoiceTranscript.spokenDurationMs(new JSONObject()
            .put("type", "TurnInfo").put("event", "EndOfTurn").put("words", new JSONArray())));

        assertEquals(-1L, VoiceTranscript.spokenDurationMs(new JSONObject()
            .put("type", "TurnInfo").put("event", "EndOfTurn")
            .put("words", new JSONArray()
                .put(new JSONObject().put("word", "call").put("start", 1.10).put("end", 1.32))
                .put(new JSONObject().put("word", "Tanmay").put("start", 1.40)))));
    }

    @Test public void contactCommandPrefersExactContactHypothesis() {
        ArrayList<String> options = new ArrayList<>(List.of("call ten may", "call Tanmay"));
        ArrayList<String> contacts = new ArrayList<>(List.of("Tanmay", "Alice Example"));
        assertEquals("call Tanmay",
            AndroidShortSpeechRecognizer.chooseRecognitionResult(options, contacts));

        ArrayList<String> ordinary = new ArrayList<>(List.of(
            "tell me about ten may", "tell me about Tanmay"));
        assertEquals("tell me about ten may",
            AndroidShortSpeechRecognizer.chooseRecognitionResult(ordinary, contacts));
    }

    @Test public void emptyConfirmedTurnReleasesSpeechStateWithoutDispatch() throws Exception {
        JSONObject empty = new JSONObject().put("type", "TurnInfo")
            .put("event", "EndOfTurn").put("transcript", "   ");
        assertTrue(VoiceTranscript.emptyCompletedTurn(empty));
        assertNull(VoiceTranscript.completedTurn(empty));
        assertFalse(VoiceTranscript.emptyCompletedTurn(new JSONObject()
            .put("type", "TurnInfo").put("event", "Update").put("transcript", " ")));
        assertFalse(VoiceTranscript.emptyCompletedTurn(new JSONObject()
            .put("type", "TurnInfo").put("event", "EndOfTurn").put("transcript", "voice")));
    }

    @Test public void fluxBargeInOnlyOnConfirmedSpeechStart() throws Exception {
        assertFalse(VoiceTranscript.startedTurn(new JSONObject().put("type", "TurnInfo")
            .put("event", "Update").put("transcript", "hello")));
        assertFalse(VoiceTranscript.startedTurn(new JSONObject().put("type", "TurnInfo")
            .put("event", "EndOfTurn").put("transcript", "hello")));
        assertFalse(VoiceTranscript.startedTurn(new JSONObject().put("type", "Error")
            .put("event", "StartOfTurn")));
        assertTrue(VoiceTranscript.startedTurn(new JSONObject().put("type", "TurnInfo")
            .put("event", "StartOfTurn")));
    }

    @Test public void streamedReplyDoesNotSplitWordsAndDrainsTrailingText() {
        VoiceTextChunker chunker = new VoiceTextChunker();
        assertTrue(chunker.append("Here is your ans").isEmpty());
        assertEquals(List.of("Here is your answer."), chunker.append("wer. Next"));
        assertEquals("Next", chunker.drain());
        assertTrue(chunker.append("").isEmpty());
    }
}
