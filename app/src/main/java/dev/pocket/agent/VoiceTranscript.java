package dev.pocket.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Flux emits speculative turn updates; only a confirmed EndOfTurn is a new user message. */
final class VoiceTranscript {
    static boolean startedTurn(JSONObject packet) {
        return "TurnInfo".equals(packet.optString("type"))
            && "StartOfTurn".equals(packet.optString("event"));
    }

    static boolean emptyCompletedTurn(JSONObject packet) {
        return "TurnInfo".equals(packet.optString("type"))
            && "EndOfTurn".equals(packet.optString("event"))
            && packet.optString("transcript", "").trim().isEmpty();
    }

    static String completedTurn(JSONObject packet) {
        if (!"TurnInfo".equals(packet.optString("type"))
                || !"EndOfTurn".equals(packet.optString("event"))) return null;
        String utterance = packet.optString("transcript", "").trim();
        return utterance.isEmpty() ? null : utterance;
    }

    /** Spoken-word duration, excluding endpointer silence. Returns -1 when Flux omitted timings. */
    static long spokenDurationMs(JSONObject packet) {
        if (packet == null || !"TurnInfo".equals(packet.optString("type"))
                || !"EndOfTurn".equals(packet.optString("event"))) return -1L;
        JSONArray words = packet.optJSONArray("words");
        if (words == null || words.length() == 0) return -1L;
        double first = Double.POSITIVE_INFINITY;
        double last = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < words.length(); index++) {
            JSONObject word = words.optJSONObject(index);
            if (word == null || !word.has("start") || !word.has("end")) return -1L;
            double start = word.optDouble("start", Double.NaN);
            double end = word.optDouble("end", Double.NaN);
            if (!Double.isFinite(start) || !Double.isFinite(end) || end < start) return -1L;
            first = Math.min(first, start);
            last = Math.max(last, end);
        }
        if (!Double.isFinite(first) || !Double.isFinite(last) || last < first) return -1L;
        return Math.max(0L, Math.round((last - first) * 1000.0));
    }
}
