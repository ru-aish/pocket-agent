package dev.pocket.agent;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

/** App-private durable snapshots; credentials have a separate Keystore-backed store. */
public final class StateStore extends SQLiteOpenHelper {
    public StateStore(Context context) { super(context, "agent-state.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE snapshot (id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE runtime_state (id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, json TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) { throw new IllegalStateException("Unsupported state migration"); }
    public synchronized JSONObject load() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT json FROM snapshot WHERE id=1", null)) {
            if (cursor.moveToFirst()) return new JSONObject(cursor.getString(0));
        } catch (org.json.JSONException e) { throw new IllegalStateException("Invalid saved state", e); }
        return new JSONObject();
    }
    public synchronized void save(JSONObject state) {
        ContentValues values = new ContentValues(); values.put("id", 1); values.put("json", state.toString());
        getWritableDatabase().insertWithOnConflict("snapshot", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized JSONObject loadDurable() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT json FROM runtime_state WHERE id=1", null)) {
            if (cursor.moveToFirst()) return new JSONObject(cursor.getString(0));
        } catch (org.json.JSONException e) { throw new IllegalStateException("Invalid saved runtime state", e); }
        return new JSONObject();
    }
    public synchronized void saveDurable(JSONObject state) {
        ContentValues values = new ContentValues(); values.put("id", 1); values.put("json", state.toString());
        getWritableDatabase().insertWithOnConflict("runtime_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized void record(JSONObject event) {
        ContentValues values = new ContentValues(); values.put("at", System.currentTimeMillis()); values.put("json", event.toString());
        SQLiteDatabase db = getWritableDatabase(); db.insertOrThrow("events", null, values);
        db.execSQL("DELETE FROM events WHERE id <= (SELECT COALESCE(MAX(id),0)-" + (BuildConfig.DEVELOPMENT_TOOLS ? 2000 : 100) + " FROM events)");
    }
    public synchronized JSONArray recentEvents() {
        JSONArray events = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT json FROM events ORDER BY id DESC LIMIT 20", null)) {
            while (cursor.moveToNext()) events.put(new JSONObject(cursor.getString(0)));
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        return events;
    }
    public synchronized JSONArray queryEvents(String filter, int offset, int limit) {
        if (!BuildConfig.DEVELOPMENT_TOOLS) throw new SecurityException("Development only");
        JSONArray result = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT json FROM events WHERE json LIKE ? ESCAPE '!' ORDER BY id DESC LIMIT ? OFFSET ?",
                new String[]{"%" + filter.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%", String.valueOf(limit), String.valueOf(offset)})) {
            while (cursor.moveToNext()) result.put(new JSONObject(cursor.getString(0)));
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        return result;
    }
    public synchronized void saveMainSystemPrompt(String prompt) {
        JSONObject durable = loadDurable(), snapshot = load();
        try {
            if (prompt == null || prompt.trim().isEmpty()) durable.remove("mainSystemPrompt");
            else durable.put("mainSystemPrompt", prompt.trim());
            saveDurable(durable);
            snapshot.put("mainSystemPrompt", prompt == null ? "" : prompt.trim());
            save(snapshot);
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }
    public synchronized JSONObject requestFreshMain() {
        JSONObject durable = loadDurable(), snapshot = load();
        try {
            durable.put("freshMainRequested", true); saveDurable(durable);
            snapshot.put("messages", new JSONArray()).put("mainTurns", 0).put("mainPaused", false)
                    .put("mainInterrupted", false).put("busy", false).put("active", false).put("lastError", JSONObject.NULL);
            save(snapshot); return snapshot;
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }
    /** In-memory Pi sessions cannot survive process death. Never replay unresolved side effects. */
    public synchronized JSONObject markInterrupted() {
        JSONObject state = load();
        try {
            JSONArray tasks = state.optJSONArray("tasks");
            if (tasks != null) for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
                String status = task.optString("status");
                if (status.equals("running") || status.equals("queued") || status.equals("waiting") || status.equals("steering")) {
                    task.put("status", "interrupted");
                    task.put("interruptionReason", "Android process stopped. Verify previous actions before restarting.");
                }
            }
            state.put("busy", false); state.put("active", false); save(state); return state;
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }
}
