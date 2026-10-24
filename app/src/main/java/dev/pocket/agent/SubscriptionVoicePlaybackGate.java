package dev.pocket.agent;

/**
 * Completion guard for GPT-Live reflected audio. Silence is not completion;
 * a terminal event must identify the response or assistant turn that produced
 * the current speech. Unknown or mismatched IDs fail closed.
 */
final class SubscriptionVoicePlaybackGate {
    private boolean hasAudio;
    private boolean assistantFinished;
    private long lastAudioMs;
    private String responseId;
    private String assistantTurnId;

    void reset() {
        hasAudio = false;
        assistantFinished = false;
        lastAudioMs = 0;
        responseId = null;
        assistantTurnId = null;
    }

    void bindResponse(String id) {
        if (valid(id) && responseId == null) responseId = id;
    }

    void bindAssistantTurn(String id) {
        if (valid(id) && assistantTurnId == null) assistantTurnId = id;
    }

    String responseId() { return responseId; }
    String assistantTurnId() { return assistantTurnId; }

    private static boolean valid(String id) { return id != null && !id.isEmpty(); }

    boolean finishResponse(String id) {
        if (!valid(id) || !id.equals(responseId)) return false;
        assistantFinished = true;
        return true;
    }

    boolean finishAssistantTurn(String id) {
        if (!valid(id) || !id.equals(assistantTurnId)) return false;
        assistantFinished = true;
        return true;
    }

    void audio(long nowMs) {
        hasAudio = true;
        lastAudioMs = nowMs;
    }

    boolean hasAudio() { return hasAudio; }
    boolean assistantFinished() { return assistantFinished; }

    boolean ready(long nowMs, long quietMs) {
        return hasAudio && assistantFinished && nowMs - lastAudioMs >= quietMs;
    }

    long remaining(long nowMs, long quietMs) {
        return Math.max(0L, quietMs - (nowMs - lastAudioMs));
    }

    boolean playbackTimedOut(long nowMs, long startedMs, long absoluteDeadlineMs,
                             long initialAudioWaitMs, long audioIdleMs) {
        if (nowMs >= absoluteDeadlineMs) return true;
        return hasAudio ? nowMs - lastAudioMs >= audioIdleMs
                : nowMs - startedMs >= initialAudioWaitMs;
    }
}
