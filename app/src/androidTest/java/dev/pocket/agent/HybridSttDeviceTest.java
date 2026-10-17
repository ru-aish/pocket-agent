package dev.pocket.agent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class HybridSttDeviceTest {
    @Test public void shortSystemUtteranceWinsOverBufferedDeepgram() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertTrue(arbiter.systemSpeechEnded(gen, 2200L));
        assertNull(arbiter.deepgramFinal(gen, "Deepgram text", 2400L));

        HybridTranscriptArbiter.Choice choice =
            arbiter.systemFinal(gen, "System text", 2600L);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.SYSTEM, choice.source);
        assertEquals("System text", choice.text);
        assertTrue(arbiter.turnSettled());
    }

    @Test public void longUtteranceUsesDeepgramAfterThreeSeconds() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertNull(arbiter.thresholdReached(gen, 4000L));
        assertFalse(arbiter.systemEligible());

        HybridTranscriptArbiter.Choice choice =
            arbiter.deepgramFinal(gen, "Long Deepgram speech", 4500L);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.DEEPGRAM, choice.source);
        assertEquals("Long Deepgram speech", choice.text);
        assertNull(arbiter.systemFinal(gen, "Late system text", 4600L));
    }

    @Test public void exactThreeSecondSpeechDurationUsesDeepgram() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertFalse(arbiter.systemSpeechEnded(gen, 4000L));
        assertTrue(arbiter.systemEligible());

        HybridTranscriptArbiter.Choice choice =
            arbiter.deepgramFinal(gen, "Boundary transcript", 4200L);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.DEEPGRAM, choice.source);
    }

    @Test public void systemErrorReleasesBufferedDeepgramFinal() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertTrue(arbiter.systemSpeechEnded(gen, 2000L));
        assertNull(arbiter.deepgramFinal(gen, "Fallback Deepgram transcript", 2200L));

        HybridTranscriptArbiter.Choice choice = arbiter.systemFailed(gen);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.DEEPGRAM, choice.source);
        assertEquals("Fallback Deepgram transcript", choice.text);
        assertTrue(arbiter.turnSettled());
    }

    @Test public void systemResultWaitsForFluxTimingAndWinsShortTurn() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        arbiter.deepgramSpeechStarted(gen);
        assertTrue(arbiter.systemSpeechEnded(gen, 2000L));

        assertNull(arbiter.systemFinal(gen, "System winner", 2300L));
        HybridTranscriptArbiter.Choice winner =
            arbiter.deepgramFinal(gen, "Deepgram candidate", 2500L, 900L);
        assertNotNull(winner);
        assertEquals(HybridTranscriptArbiter.Source.SYSTEM, winner.source);
        assertEquals("System winner", winner.text);
        assertTrue(arbiter.turnSettled());
        assertNull(arbiter.deepgramFinal(gen, "Duplicate Deepgram", 2600L, 900L));
    }

    @Test public void shortEndStillAllowsLateSystemResultPastThreshold() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertTrue(arbiter.systemSpeechEnded(gen, 2800L));

        assertNull(arbiter.thresholdReached(gen, 4200L));
        assertTrue(arbiter.systemEligible());
        assertNull(arbiter.deepgramFinal(gen, "Buffered Deepgram", 4300L));

        HybridTranscriptArbiter.Choice choice =
            arbiter.systemFinal(gen, "Late system result", 4500L);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.SYSTEM, choice.source);
    }

    @Test public void systemWaitTimeoutFallsBackWithoutHanging() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertTrue(arbiter.systemSpeechEnded(gen, 2000L));
        assertNull(arbiter.deepgramFinal(gen, "Deepgram fallback", 2200L));

        HybridTranscriptArbiter.Choice choice = arbiter.systemWaitExpired(gen);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.DEEPGRAM, choice.source);
        assertTrue(arbiter.turnSettled());
        assertNull(arbiter.systemFinal(gen, "Late system", 2400L));
    }

    @Test public void emptyDeepgramTurnStillAllowsPendingShortSystemResult() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertTrue(arbiter.systemSpeechEnded(gen, 1800L));
        arbiter.deepgramEmptyTurn(gen);
        assertTrue(arbiter.systemEligible());
        assertFalse(arbiter.turnSettled());

        HybridTranscriptArbiter.Choice choice =
            arbiter.systemFinal(gen, "System understood it", 2200L);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.SYSTEM, choice.source);
        assertTrue(arbiter.turnSettled());
    }

    @Test public void emptyDeepgramAndMissingSystemResultSettlesOnGraceTimeout() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertTrue(arbiter.systemSpeechEnded(gen, 1800L));
        arbiter.deepgramEmptyTurn(gen);

        assertNull(arbiter.systemWaitExpired(gen));
        assertFalse(arbiter.systemEligible());
        assertTrue(arbiter.turnSettled());
    }

    @Test public void staleGenerationCannotAffectNewTurn() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long oldGen = arbiter.generation();
        arbiter.speechStarted(oldGen, 1000L);
        arbiter.reset(2L, true);

        assertNull(arbiter.systemFinal(oldGen, "Stale system", 1500L));
        assertNull(arbiter.deepgramFinal(oldGen, "Stale Deepgram", 1500L));
        assertNull(arbiter.systemFailed(oldGen));

        long currentGen = arbiter.generation();
        arbiter.speechStarted(currentGen, 2000L);
        assertTrue(arbiter.systemSpeechEnded(currentGen, 2500L));
        assertNull(arbiter.systemFinal(currentGen, "Current system", 2600L));
        HybridTranscriptArbiter.Choice current =
            arbiter.deepgramFinal(currentGen, "Current Deepgram", 2700L, 450L);
        assertNotNull(current);
        assertEquals(HybridTranscriptArbiter.Source.SYSTEM, current.source);
        assertEquals("Current system", current.text);
    }

    @Test public void systemEndWithoutObservedSpeechStartFallsBackToDeepgram() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        assertFalse(arbiter.systemSpeechEnded(gen, 2000L));
        assertFalse(arbiter.systemEligible());

        HybridTranscriptArbiter.Choice choice =
            arbiter.deepgramFinal(gen, "Deepgram safe fallback", 2200L);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.DEEPGRAM, choice.source);
    }

    @Test public void timedOutSystemFallbackSuppressesMatchingDeepgramFinal() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        arbiter.deepgramSpeechStarted(gen);
        assertTrue(arbiter.systemSpeechEnded(gen, 1800L));
        assertNull(arbiter.systemFinal(gen, "System winner", 2000L));

        HybridTranscriptArbiter.Choice choice = arbiter.systemWaitExpired(gen);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.SYSTEM, choice.source);
        assertTrue(arbiter.waitingForMatchingDeepgramFinal());

        assertNull(arbiter.deepgramFinal(gen, "Matching Deepgram final", 2200L, 800L));
        assertFalse(arbiter.waitingForMatchingDeepgramFinal());
        assertTrue(arbiter.turnSettled());
    }

    @Test public void systemTimeoutDoesNotWaitWhenDeepgramNeverOpenedTurn() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertTrue(arbiter.systemSpeechEnded(gen, 1800L));
        assertNull(arbiter.systemFinal(gen, "System only", 2000L));

        HybridTranscriptArbiter.Choice choice = arbiter.systemWaitExpired(gen);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.SYSTEM, choice.source);
        assertFalse(arbiter.waitingForMatchingDeepgramFinal());
        assertTrue(arbiter.turnSettled());
    }

    @Test public void longAndroidResultDoesNotPreemptOpenDeepgramTurnAtGraceTimeout() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        arbiter.deepgramSpeechStarted(gen);
        assertFalse(arbiter.systemSpeechEnded(gen, 4500L));
        assertNull(arbiter.systemFinal(gen, "Android long candidate", 4600L));

        assertNull(arbiter.systemWaitExpired(gen));
        assertTrue(arbiter.systemEligible());
        assertFalse(arbiter.turnSettled());

        HybridTranscriptArbiter.Choice choice =
            arbiter.deepgramFinal(gen, "Deepgram long winner", 5000L, 3400L);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.DEEPGRAM, choice.source);
        assertEquals("Deepgram long winner", choice.text);
        assertTrue(arbiter.turnSettled());
    }

    @Test public void matchingDeepgramWaitHasBoundedEscape() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        arbiter.deepgramSpeechStarted(gen);
        assertTrue(arbiter.systemSpeechEnded(gen, 1800L));
        assertNull(arbiter.systemFinal(gen, "System winner", 2000L));
        assertNotNull(arbiter.systemWaitExpired(gen));
        assertTrue(arbiter.waitingForMatchingDeepgramFinal());

        assertTrue(arbiter.matchingDeepgramWaitExpired(gen));
        assertFalse(arbiter.waitingForMatchingDeepgramFinal());
        assertTrue(arbiter.turnSettled());
        assertFalse(arbiter.matchingDeepgramWaitExpired(gen));
    }

    @Test public void deepgramFinalAfterSystemFailureIsImmediateEvenForShortSpeech() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertTrue(arbiter.systemSpeechEnded(gen, 1800L));
        assertNull(arbiter.systemFailed(gen));

        HybridTranscriptArbiter.Choice choice =
            arbiter.deepgramFinal(gen, "Deepgram after system failure", 2200L);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.DEEPGRAM, choice.source);
        assertEquals("Deepgram after system failure", choice.text);
        assertTrue(arbiter.turnSettled());
    }

    @Test public void deepgramFinalPastThresholdWaitsForAndroidEndpoint() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertFalse(arbiter.systemEndpointObserved());

        assertNull(arbiter.deepgramFinal(gen, "Buffered Deepgram", 4500L));
        assertTrue(arbiter.systemEligible());
        assertFalse(arbiter.systemEndpointObserved());
        assertFalse(arbiter.turnSettled());

        assertFalse(arbiter.systemSpeechEnded(gen, 4600L));
        assertTrue(arbiter.systemEndpointObserved());
        HybridTranscriptArbiter.Choice choice = arbiter.systemFailed(gen);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.DEEPGRAM, choice.source);
        assertEquals("Buffered Deepgram", choice.text);
        assertTrue(arbiter.turnSettled());
    }

    @Test public void configurableThresholdControlsShortTurnOwnership() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true, 5000L);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertNull(arbiter.deepgramFinal(gen, "Deepgram candidate", 4600L));
        assertTrue(arbiter.systemSpeechEnded(gen, 4800L));

        HybridTranscriptArbiter.Choice choice =
            arbiter.systemFinal(gen, "System candidate", 5200L);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.SYSTEM, choice.source);
        assertEquals("System candidate", choice.text);
    }

    @Test public void delayedAndroidEndpointStillUsesSystemWhenFluxWordsAreShort() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);

        assertFalse(arbiter.systemSpeechEnded(gen, 5200L));
        assertTrue(arbiter.systemEligible());
        assertNull(arbiter.systemFinal(gen, "Call Tanmay", 5300L));

        HybridTranscriptArbiter.Choice choice =
            arbiter.deepgramFinal(gen, "Call ten may", 5400L, 850L);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.SYSTEM, choice.source);
        assertEquals("Call Tanmay", choice.text);
    }

    @Test public void fluxWordDurationCanMakeDeepgramAuthoritative() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(true);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertTrue(arbiter.systemSpeechEnded(gen, 2000L));
        assertNull(arbiter.systemFinal(gen, "System candidate", 2100L));

        HybridTranscriptArbiter.Choice choice =
            arbiter.deepgramFinal(gen, "Long Deepgram turn", 2200L, 3200L);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.DEEPGRAM, choice.source);
        assertEquals("Long Deepgram turn", choice.text);
    }

    @Test public void unavailableSystemRecognizerMakesDeepgramAuthoritativeImmediately() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(false);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);

        HybridTranscriptArbiter.Choice choice =
            arbiter.deepgramFinal(gen, "Deepgram only", 1500L);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.DEEPGRAM, choice.source);
    }

    @Test public void exactMinimumThresholdMakesDeepgramAuthoritative() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(
            true, VoiceSttSettings.MIN_SHORT_UTTERANCE_MS);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertTrue(arbiter.systemSpeechEnded(gen, 1300L));
        assertNull(arbiter.systemFinal(gen, "Android candidate", 1350L));

        HybridTranscriptArbiter.Choice choice =
            arbiter.deepgramFinal(gen, "Deepgram boundary", 1400L,
                VoiceSttSettings.MIN_SHORT_UTTERANCE_MS);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.DEEPGRAM, choice.source);
        assertEquals("Deepgram boundary", choice.text);
    }

    @Test public void justBelowMinimumThresholdLetsAndroidWin() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(
            true, VoiceSttSettings.MIN_SHORT_UTTERANCE_MS);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertTrue(arbiter.systemSpeechEnded(gen, 1300L));
        assertNull(arbiter.systemFinal(gen, "Android short", 1350L));

        HybridTranscriptArbiter.Choice choice =
            arbiter.deepgramFinal(gen, "Deepgram short", 1400L,
                VoiceSttSettings.MIN_SHORT_UTTERANCE_MS - 1L);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.SYSTEM, choice.source);
        assertEquals("Android short", choice.text);
    }

    @Test public void exactMaximumThresholdMakesDeepgramAuthoritative() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(
            true, VoiceSttSettings.MAX_SHORT_UTTERANCE_MS);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertTrue(arbiter.systemSpeechEnded(gen, 1800L));
        assertNull(arbiter.systemFinal(gen, "Android candidate", 1900L));

        HybridTranscriptArbiter.Choice choice =
            arbiter.deepgramFinal(gen, "Deepgram max boundary", 2000L,
                VoiceSttSettings.MAX_SHORT_UTTERANCE_MS);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.DEEPGRAM, choice.source);
    }

    @Test public void justBelowMaximumThresholdLetsAndroidWin() {
        HybridTranscriptArbiter arbiter = new HybridTranscriptArbiter(
            true, VoiceSttSettings.MAX_SHORT_UTTERANCE_MS);
        long gen = arbiter.generation();
        arbiter.speechStarted(gen, 1000L);
        assertTrue(arbiter.systemSpeechEnded(gen, 1800L));
        assertNull(arbiter.systemFinal(gen, "Android max-short", 1900L));

        HybridTranscriptArbiter.Choice choice =
            arbiter.deepgramFinal(gen, "Deepgram candidate", 2000L,
                VoiceSttSettings.MAX_SHORT_UTTERANCE_MS - 1L);
        assertNotNull(choice);
        assertEquals(HybridTranscriptArbiter.Source.SYSTEM, choice.source);
        assertEquals("Android max-short", choice.text);
    }
}
