package dev.pocket.agent;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** App-private durable snapshots; credentials have a separate Keystore-backed store. */
public final class StateStore extends SQLiteOpenHelper {
    private final Context context;
    private static java.util.Set<String> legacyDefaults;
    public StateStore(Context context) { this(context,"agent-state.db"); }
    StateStore(Context context,String name) { super(context,name,null,3);this.context=context.getApplicationContext(); }
    private boolean legacyDefault(String prompt) {
        synchronized(StateStore.class) {
            if(legacyDefaults==null)try(java.io.InputStream input=context.getAssets().open("legacy-main-prompts.json")) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
                JSONArray values=new JSONArray(bytes.toString(StandardCharsets.UTF_8.name()));
                java.util.Set<String> defaults=new java.util.HashSet<>();for(int i=0;i<values.length();i++)defaults.add(values.getString(i));legacyDefaults=defaults;
            } catch(Exception error){throw new IllegalStateException("Could not load instruction migration",error);}
            return legacyDefaults.contains(prompt);
        }
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE snapshot (id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE runtime_state (id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, json TEXT NOT NULL)");
        ConversationHistory.create(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if(from==1&&to==3) {
            ConversationHistory.create(db);
            archiveLegacy(db);
            return;
        }
        if(from==2&&to==3) {ConversationHistory.createProgress(db);archiveLegacy(db);return;}
        throw new IllegalStateException("Unsupported state migration");
    }
    private void archiveLegacy(SQLiteDatabase db) {
        final JSONObject archive;
        try {
            String raw=loadDurableRaw(db);if(raw==null)return;
            try {archive=ConversationHistory.archivePayload(new java.io.StringReader(raw));}
            catch(IllegalArgumentException|IllegalStateException malformed) {
                // A corrupt runtime checkpoint must not prevent opening the rendered UI and events.
                // Keep its original row untouched so it remains available for recovery.
                // Exception messages can contain private checkpoint data; log only its failure classification.
                Throwable cause=malformed.getCause()==null?malformed:malformed.getCause();
                android.util.Log.w("PocketHistory","Legacy checkpoint could not be indexed ("+cause.getClass().getSimpleName()+"); original checkpoint retained");
                return;
            }
            ConversationHistory.archive(db,archive);
        } catch(RuntimeException failure) {
            // SQLiteOpenHelper rolls back the complete migration, including its version, on write failure.
            throw new IllegalStateException("Legacy history migration failed; original database retained",failure);
        }
    }
    public synchronized JSONObject load() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT json FROM snapshot WHERE id=1", null)) {
            if (cursor.moveToFirst()) {
                JSONObject snapshot=new JSONObject(cursor.getString(0));
                if(!snapshot.has("personalInstructions")) {
                    String legacy=snapshot.optString("mainSystemPrompt");
                    snapshot.put("personalInstructions",legacyDefault(legacy)?"":legacy);
                }
                snapshot.remove("mainSystemPrompt");snapshot.remove("defaultMainSystemPrompt");return snapshot;
            }
        } catch (org.json.JSONException e) { throw new IllegalStateException("Invalid saved state", e); }
        return new JSONObject();
    }
    public synchronized void save(JSONObject state) {
        ContentValues values = new ContentValues(); values.put("id", 1); values.put("json", state.toString());
        if(getWritableDatabase().insertWithOnConflict("snapshot", null, values, SQLiteDatabase.CONFLICT_REPLACE)==-1)
            throw new IllegalStateException("Snapshot persistence failed");
    }
    private String loadDurableRaw() {
        return loadDurableRaw(getReadableDatabase());
    }
    private String loadDurableRaw(SQLiteDatabase db) {
        String type;int length;
        try(Cursor meta=db.rawQuery("SELECT typeof(json), length(json) FROM runtime_state WHERE id=1",null)) {
            if(!meta.moveToFirst())return null;
            type=meta.getString(0);length=meta.getInt(1);
        }
        if("blob".equals(type)) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(Math.max(0,length));
            final int chunk=256*1024;
            for(int offset=0;offset<length;offset+=chunk) {
                try(Cursor part=db.rawQuery("SELECT substr(json, ?, ?) FROM runtime_state WHERE id=1",
                    new String[]{String.valueOf(offset+1),String.valueOf(Math.min(chunk,length-offset))})) {
                    if(!part.moveToFirst())throw new IllegalStateException("Durable runtime state disappeared during read");
                    byte[] value=part.getBlob(0);if(value!=null)bytes.write(value,0,value.length);
                }
            }
            return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        }
        StringBuilder text=new StringBuilder(Math.max(0,length));final int chunk=128*1024;
        for(int offset=0;offset<length;offset+=chunk) {
            try(Cursor part=db.rawQuery("SELECT substr(json, ?, ?) FROM runtime_state WHERE id=1",
                new String[]{String.valueOf(offset+1),String.valueOf(Math.min(chunk,length-offset))})) {
                if(!part.moveToFirst())throw new IllegalStateException("Durable runtime state disappeared during read");
                String value=part.getString(0);if(value!=null)text.append(value);
            }
        }
        return text.toString();
    }
    public synchronized JSONObject loadDurable() {
        try {
            String raw=loadDurableRaw();return raw==null?new JSONObject():new JSONObject(raw);
        } catch (org.json.JSONException e) { throw new IllegalStateException("Invalid saved runtime state", e); }
    }
    public synchronized void saveDurable(JSONObject state) {
        persistDurable(state,state.toString());
    }
    public synchronized void saveDurableBytes(byte[] utf8Json) {
        if(utf8Json==null||utf8Json.length==0)throw new IllegalArgumentException("Durable state bytes are empty");
        persistDurable(ConversationHistory.archivePayload(utf8Json),utf8Json);
    }
    private void persistDurable(JSONObject state,Object encoded) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            // The archive and checkpoint commit together, even when the live conversation resets.
            ConversationHistory.archive(db,state);
            ContentValues values=new ContentValues();values.put("id",1);
            if(encoded instanceof byte[])values.put("json",(byte[])encoded);
            else values.put("json",(String)encoded);
            if(db.insertWithOnConflict("runtime_state",null,values,SQLiteDatabase.CONFLICT_REPLACE)<0)
                throw new IllegalStateException("Unable to persist durable state");
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    public synchronized JSONArray searchHistory(String query,int limit) {
        return searchHistory(query,limit,"main");
    }
    public synchronized JSONArray listConversations(String query) {
        return ConversationHistory.conversations(getReadableDatabase(), query);
    }
    public synchronized JSONArray listConversations(String query,java.util.Map<String,String> titles) {
        return ConversationHistory.conversations(getReadableDatabase(),query,titles);
    }
    public synchronized JSONObject readConversation(String threadId, int offset) {
        return ConversationHistory.conversation(getReadableDatabase(), threadId, offset);
    }
    public synchronized JSONArray resumeMainConversation(String threadId) { return ConversationHistory.resumeMain(getReadableDatabase(),threadId); }
    public synchronized JSONArray searchHistory(String query,int limit,String requester) {
        return ConversationHistory.search(getReadableDatabase(),query,limit,requester);
    }
    public synchronized JSONObject readHistory(long id,String requester) {
        return ConversationHistory.read(getReadableDatabase(),id,requester);
    }
    public synchronized JSONObject readHistory(long id,String requester,int offset,int limit) {
        if(limit>4000)throw new IllegalArgumentException("History page exceeds tool budget");
        return ConversationHistory.read(getReadableDatabase(),id,requester,offset,limit);
    }
    public synchronized void markDurableInterrupted() {
        try {
            JSONObject state=loadDurable();
            JSONArray inbox=state.optJSONArray("inbox");
            if(!state.optBoolean("busy")&&(inbox==null||inbox.length()==0))return;
            state.put("inbox",new JSONArray()).put("busy",false).put("mainPaused",true);
            // Shutdown neutralization must remain possible even when the archive is unavailable.
            ContentValues values=new ContentValues();values.put("id",1);values.put("json",state.toString());
            if(getWritableDatabase().insertWithOnConflict("runtime_state",null,values,SQLiteDatabase.CONFLICT_REPLACE)<0)
                throw new IllegalStateException("Unable to neutralize durable state");
        } catch(Exception failure) {
            if(getWritableDatabase().delete("runtime_state","id=1",null)<0)
                throw new IllegalStateException("Could not neutralize interrupted durable runtime state",failure);
        }
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
