package dev.pocket.agent;

/**
 * Chooses exactly one transcript for a spoken turn.
 *
 * Android and Deepgram receive the same PCM. Flux word timestamps provide the preferred
 * speech-duration measurement because they exclude recognizer endpointing silence. Android's
 * begin/end callbacks are retained as a fallback when Flux omits word timing.
 */
final class HybridTranscriptArbiter {
    static final long DEFAULT_SHORT_UTTERANCE_MS = 3000L;

    enum Source { SYSTEM, DEEPGRAM }

    static final class Choice {
        final String text;
        final Source source;

        Choice(String text, Source source) {
            this.text = text;
            this.source = source;
        }
    }

    private long generation;
    private long shortUtteranceMs;
    private boolean systemEnabled;
    private long speechStartedAt = -1L;
    private long systemSpeechEndedAt = -1L;
    private long deepgramSpokenDurationMs = -1L;
    private String pendingSystem;
    private String pendingDeepgram;
    private boolean deepgramTurnOpen;
    private boolean deepgramEnded;
    private boolean resolved;
    private boolean waitingForMatchingDeepgramFinal;

    HybridTranscriptArbiter(boolean systemEnabled) {
        this(systemEnabled, DEFAULT_SHORT_UTTERANCE_MS);
    }

    HybridTranscriptArbiter(boolean systemEnabled, long shortUtteranceMs) {
        reset(1L, systemEnabled, shortUtteranceMs);
    }

    synchronized void reset(long generation, boolean enabled) {
        reset(generation, enabled, shortUtteranceMs);
    }

    synchronized void reset(long generation, boolean enabled, long shortUtteranceMs) {
        if (shortUtteranceMs <= 0L)
            throw new IllegalArgumentException("Short utterance threshold must be positive");
        this.generation = generation;
        this.shortUtteranceMs = shortUtteranceMs;
        systemEnabled = enabled;
        speechStartedAt = -1L;
        systemSpeechEndedAt = -1L;
        deepgramSpokenDurationMs = -1L;
        pendingSystem = null;
        pendingDeepgram = null;
        deepgramTurnOpen = false;
        deepgramEnded = false;
        resolved = false;
        waitingForMatchingDeepgramFinal = false;
    }

    synchronized long generation() {
        return generation;
    }

    /** Records Android's own beginning-of-speech callback for fallback duration measurement. */
    synchronized long speechStarted(long gen, long nowMs) {
        if (gen != generation || resolved) return -1L;
        if (speechStartedAt < 0L || nowMs < speechStartedAt) speechStartedAt = nowMs;
        return Math.max(0L, shortUtteranceMs - (nowMs - speechStartedAt));
    }

    /**
     * Records Android's endpoint. The boolean is only a fallback-duration hint; a late endpoint
     * does not disable Android while Flux word timing may still prove the spoken words were short.
     */
    synchronized boolean systemSpeechEnded(long gen, long nowMs) {
        if (gen != generation || !systemEnabled || resolved) return false;
        systemSpeechEndedAt = nowMs;
        if (speechStartedAt < 0L) {
            systemEnabled = false;
            return false;
        }
        return systemSpeechEndedAt - speechStartedAt < shortUtteranceMs;
    }

    synchronized Choice systemFinal(long gen, String text, long nowMs) {
        if (gen != generation || resolved || !systemEnabled || text == null
                || text.trim().isEmpty()) return null;
        pendingSystem = text.trim();

        if (!deepgramEnded) return null;

        if (pendingDeepgram == null) {
            // Deepgram ended empty. Preserve the only usable transcript instead of dropping
            // the command merely because timing metadata is unavailable.
            return choosePendingSystem();
        }

        long duration = effectiveSpokenDurationMs(nowMs);
        if (duration >= 0L && duration >= shortUtteranceMs) {
            systemEnabled = false;
            pendingSystem = null;
            return choosePendingDeepgram();
        }
        return choosePendingSystem();
    }

    synchronized void deepgramSpeechStarted(long gen) {
        if (gen != generation || deepgramEnded) return;
        deepgramTurnOpen = true;
    }

    synchronized Choice deepgramFinal(long gen, String text, long nowMs) {
        return deepgramFinal(gen, text, nowMs, -1L);
    }

    synchronized Choice deepgramFinal(long gen, String text, long nowMs, long spokenDurationMs) {
        if (gen != generation || text == null || text.trim().isEmpty()) return null;
        deepgramTurnOpen = false;
        deepgramEnded = true;
        deepgramSpokenDurationMs = spokenDurationMs >= 0L ? spokenDurationMs : -1L;
        if (waitingForMatchingDeepgramFinal) {
            waitingForMatchingDeepgramFinal = false;
            return null;
        }
        if (resolved) return null;

        pendingDeepgram = text.trim();
        if (!systemEnabled) return choosePendingDeepgram();

        long duration = effectiveSpokenDurationMs(nowMs);
        if (duration >= 0L) {
            if (duration >= shortUtteranceMs) {
                systemEnabled = false;
                pendingSystem = null;
                return choosePendingDeepgram();
            }
            if (pendingSystem != null) return choosePendingSystem();
        }
        // A short/unknown-duration Deepgram final stays buffered until Android returns its
        // higher-value short-turn transcript or the bounded system timeout expires.
        return null;
    }

    synchronized Choice thresholdReached(long gen, long nowMs) {
        if (gen != generation || resolved || !systemEnabled || speechStartedAt < 0L
                || nowMs - speechStartedAt < shortUtteranceMs) return null;
        if (deepgramSpokenDurationMs >= 0L && deepgramSpokenDurationMs < shortUtteranceMs)
            return null;
        if (systemSpeechEndedAt >= 0L
                && systemSpeechEndedAt - speechStartedAt < shortUtteranceMs)
            return null;
        systemEnabled = false;
        pendingSystem = null;
        return choosePendingDeepgram();
    }

    synchronized Choice systemFailed(long gen) {
        if (gen != generation || resolved) return null;
        systemEnabled = false;
        pendingSystem = null;
        Choice fallback = choosePendingDeepgram();
        if (fallback == null && deepgramEnded) resolved = true;
        return fallback;
    }

    synchronized void deepgramEmptyTurn(long gen) {
        if (gen != generation) return;
        deepgramTurnOpen = false;
        deepgramEnded = true;
        deepgramSpokenDurationMs = -1L;
        if (waitingForMatchingDeepgramFinal) waitingForMatchingDeepgramFinal = false;
        else if (!resolved && !systemEnabled) resolved = true;
    }

    synchronized Choice systemWaitExpired(long gen) {
        if (gen != generation || resolved) return null;
        if (pendingDeepgram != null) {
            systemEnabled = false;
            pendingSystem = null;
            return choosePendingDeepgram();
        }
        long duration = effectiveSpokenDurationMs(0L);
        if (pendingSystem != null && deepgramTurnOpen && !deepgramEnded
                && duration >= shortUtteranceMs) {
            // The turn is known to be long, so Android cannot win while Deepgram is still
            // finalizing. Keep Android only as the fallback if Deepgram later ends empty.
            return null;
        }
        systemEnabled = false;
        if (pendingSystem != null) return choosePendingSystem();
        if (deepgramEnded) resolved = true;
        return null;
    }

    synchronized boolean systemEligible() {
        return systemEnabled && !resolved;
    }

    synchronized boolean turnSettled() {
        return resolved && !waitingForMatchingDeepgramFinal;
    }

    synchronized boolean waitingForMatchingDeepgramFinal() {
        return resolved && waitingForMatchingDeepgramFinal;
    }

    synchronized boolean deepgramTurnOpen() {
        return deepgramTurnOpen;
    }

    synchronized boolean hasPendingDeepgramFinal() {
        return pendingDeepgram != null;
    }

    synchronized boolean systemEndpointObserved() {
        return systemSpeechEndedAt >= 0L;
    }

    synchronized boolean matchingDeepgramWaitExpired(long gen) {
        if (gen != generation || !resolved || !waitingForMatchingDeepgramFinal) return false;
        waitingForMatchingDeepgramFinal = false;
        return true;
    }

    private long effectiveSpokenDurationMs(long nowMs) {
        if (deepgramSpokenDurationMs >= 0L) return deepgramSpokenDurationMs;
        // Never substitute result-arrival wall clock for speech duration. If Flux omitted
        // word timings, wait until Android has supplied both speech callbacks.
        if (speechStartedAt < 0L || systemSpeechEndedAt < speechStartedAt) return -1L;
        return systemSpeechEndedAt - speechStartedAt;
    }

    private Choice choosePendingDeepgram() {
        if (pendingDeepgram == null) return null;
        String text = pendingDeepgram;
        pendingDeepgram = null;
        resolved = true;
        waitingForMatchingDeepgramFinal = false;
        return new Choice(text, Source.DEEPGRAM);
    }

    private Choice choosePendingSystem() {
        if (pendingSystem == null) return null;
        String text = pendingSystem;
        pendingSystem = null;
        pendingDeepgram = null;
        resolved = true;
        waitingForMatchingDeepgramFinal = deepgramTurnOpen && !deepgramEnded;
        return new Choice(text, Source.SYSTEM);
    }
}
