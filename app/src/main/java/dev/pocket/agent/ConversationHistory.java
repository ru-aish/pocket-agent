package dev.pocket.agent;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * App-private, append-only transcripts, independent of the bounded live Pi checkpoint.
 * All writes occur inside StateStore's checkpoint transaction. No server or model is needed.
 */
final class ConversationHistory {
    private static final Pattern TOKEN=Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final String COMMON=" a an and are as at be been but by can could did do does for from had has have how i in is it its me my of on or our please said say she so tell than that the their them there these this to was were what when where which who why will with would you your ";
    private ConversationHistory() {}

    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversation_history (id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id TEXT NOT NULL, owner TEXT NOT NULL, role TEXT NOT NULL, timestamp INTEGER NOT NULL, event_key TEXT NOT NULL UNIQUE, text TEXT NOT NULL)");
        db.execSQL("CREATE INDEX conversation_history_thread ON conversation_history(thread_id,timestamp)");
        db.execSQL("CREATE INDEX conversation_history_recent ON conversation_history(timestamp DESC)");
        db.execSQL("CREATE VIRTUAL TABLE conversation_history_fts USING fts4(text, tokenize=unicode61)");
        createProgress(db);
    }
    static void createProgress(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversation_history_progress (thread_id TEXT PRIMARY KEY, position INTEGER NOT NULL, fingerprint TEXT NOT NULL)");
    }
    /** Extract only archive fields without decoding a second copy of the whole checkpoint tree. */
    static JSONObject archivePayload(byte[] bytes) {
        return archivePayload(new InputStreamReader(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8));
    }
    static JSONObject archivePayload(java.io.Reader input) {
        try(JsonReader reader=new JsonReader(input)) {
            JSONObject result=new JSONObject();reader.beginObject();
            while(reader.hasNext()) {
                String name=reader.nextName();
                switch(name) {
                    case "mainThreadId":result.put(name,string(reader));break;
                    case "mainMessages":result.put(name,messages(reader));break;
                    case "workerMessages": {
                        JSONObject workers=new JSONObject();reader.beginObject();
                        while(reader.hasNext()){String worker=reader.nextName();workers.put(worker,messages(reader));}
                        reader.endObject();result.put(name,workers);break;
                    }
                    case "workerHistoryOwners": {
                        if(reader.peek()!=JsonToken.BEGIN_OBJECT){reader.skipValue();result.put(name,JSONObject.NULL);break;}
                        JSONObject owners=new JSONObject();reader.beginObject();
                        while(reader.hasNext()){String worker=reader.nextName();owners.put(worker,string(reader));}
                        reader.endObject();result.put(name,owners);break;
                    }
                    default:reader.skipValue();
                }
            }
            reader.endObject();
            if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IllegalArgumentException("Trailing checkpoint data");
            return result;
        } catch(java.io.IOException|org.json.JSONException invalid) {throw new IllegalArgumentException("Invalid archive payload",invalid);}
    }
    private static String string(JsonReader reader) throws java.io.IOException {
        if(reader.peek()==JsonToken.STRING||reader.peek()==JsonToken.NUMBER)return reader.nextString();
        reader.skipValue();return "";
    }
    private static JSONArray messages(JsonReader reader) throws java.io.IOException,org.json.JSONException {
        JSONArray result=new JSONArray();reader.beginArray();
        while(reader.hasNext()) {
            if(reader.peek()!=JsonToken.BEGIN_OBJECT){reader.skipValue();result.put(JSONObject.NULL);continue;}
            JSONObject message=new JSONObject();reader.beginObject();
            while(reader.hasNext()) {
                String name=reader.nextName();
                switch(name) {
                    case "role":message.put(name,string(reader));break;
                    case "timestamp": {
                        String raw=string(reader);long timestamp=0;
                        try{timestamp=Long.parseLong(raw);}catch(NumberFormatException ignored){}
                        message.put(name,timestamp);break;
                    }
                    case "content": {
                        if(reader.peek()==JsonToken.STRING)message.put(name,reader.nextString());
                        else if(reader.peek()==JsonToken.BEGIN_ARRAY) {
                            JSONArray blocks=new JSONArray();reader.beginArray();
                            while(reader.hasNext()) {
                                if(reader.peek()!=JsonToken.BEGIN_OBJECT){reader.skipValue();continue;}
                                JSONObject block=new JSONObject();reader.beginObject();
                                while(reader.hasNext()) {
                                    String key=reader.nextName();
                                    if(key.equals("type")||key.equals("text"))block.put(key,string(reader));else reader.skipValue();
                                }
                                reader.endObject();blocks.put(block);
                            }
                            reader.endArray();message.put(name,blocks);
                        } else reader.skipValue();
                        break;
                    }
                    default:reader.skipValue();
                }
            }
            reader.endObject();result.put(message);
        }
        reader.endArray();return result;
    }

    private static String messageText(JSONObject message) {
        Object raw=message.opt("content");
        if(raw instanceof String)return (String)raw;
        if(!(raw instanceof JSONArray))return "";
        JSONArray blocks=(JSONArray)raw;StringBuilder text=new StringBuilder();
        for(int i=0;i<blocks.length();i++) {
            JSONObject block=blocks.optJSONObject(i);
            if(block==null||!"text".equals(block.optString("type")))continue;
            if(text.length()>0)text.append('\n');
            text.append(block.optString("text"));
        }
        return text.toString();
    }

    private static String fingerprint(String thread,String role,long timestamp,String content) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] hash=digest.digest((thread+(char)0+role+(char)0+timestamp+(char)0+content).getBytes(StandardCharsets.UTF_8));
            char[] hex=new char[hash.length*2],digits="0123456789abcdef".toCharArray();
            for(int i=0;i<hash.length;i++){int b=hash[i]&255;hex[2*i]=digits[b>>>4];hex[2*i+1]=digits[b&15];}
            return new String(hex);
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }

    private static void append(SQLiteDatabase db,String thread,String owner,JSONArray messages) {
        if(messages==null||thread.isEmpty()||thread.length()>180)return;
        int start=0;
        try(Cursor progress=db.rawQuery("SELECT position,fingerprint FROM conversation_history_progress WHERE thread_id=?",new String[]{thread})) {
            if(progress.moveToFirst()) {
                int position=progress.getInt(0);
                // Live transcripts append immutable messages; trimming or a changed tail invalidates this cursor.
                if(position>0&&position<=messages.length()&&progress.getString(1).equals(messageFingerprint(thread,messages.optJSONObject(position-1))))start=position;
            }
        }
        for(int i=start;i<messages.length();i++) {
            JSONObject message=messages.optJSONObject(i);if(message==null)continue;
            String role=message.optString("role");
            if(!role.equals("user")&&!role.equals("assistant"))continue;
            String content=archiveText(message);if(content.isEmpty())continue;
            // No tool calls, screenshots, or raw provider request bodies in long-lived history.
            long timestamp=message.optLong("timestamp",0);
            ContentValues values=new ContentValues();
            values.put("thread_id",thread);values.put("owner",owner);values.put("role",role);
            values.put("timestamp",timestamp);values.put("text",content);
            values.put("event_key",fingerprint(thread,role,timestamp,content));
            long id=db.insertWithOnConflict("conversation_history",null,values,SQLiteDatabase.CONFLICT_IGNORE);
            if(id<0) {
                // CONFLICT_IGNORE also hides some SQLite failures. Only an existing key is a duplicate.
                try(Cursor existing=db.rawQuery("SELECT id FROM conversation_history WHERE event_key=?",new String[]{values.getAsString("event_key")})) {
                    if(existing.moveToFirst())continue;
                }
                throw new IllegalStateException("Conversation archive persistence failed");
            }
            ContentValues indexed=new ContentValues();indexed.put("docid",id);indexed.put("text",content);
            db.insertOrThrow("conversation_history_fts",null,indexed);
        }
        if(messages.length()>0) {
            ContentValues progress=new ContentValues();progress.put("thread_id",thread);progress.put("position",messages.length());
            progress.put("fingerprint",messageFingerprint(thread,messages.optJSONObject(messages.length()-1)));
            if(db.insertWithOnConflict("conversation_history_progress",null,progress,SQLiteDatabase.CONFLICT_REPLACE)<0)
                throw new IllegalStateException("Archive progress persistence failed");
        }
    }
    private static String archiveText(JSONObject message) {
        String text=messageText(message).trim();
        if(text.length()<=100000)return text;
        int end=Character.isHighSurrogate(text.charAt(99999))?99999:100000;
        return text.substring(0,end)+" [archived excerpt]";
    }
    private static String messageFingerprint(String thread,JSONObject message) {
        if(message==null)return "null";
        return fingerprint(thread,message.optString("role"),message.optLong("timestamp",0),archiveText(message));
    }

    static void archive(SQLiteDatabase db,JSONObject checkpoint) {
        String main=checkpoint.optString("mainThreadId","00000000-0000-4000-8000-000000000000");
        if(main.isEmpty())main="00000000-0000-4000-8000-000000000000";
        append(db,main,"main",checkpoint.optJSONArray("mainMessages"));
        JSONObject workerMessages=checkpoint.optJSONObject("workerMessages");
        if(workerMessages==null)return;
        java.util.Iterator<String> workers=workerMessages.keys();
        while(workers.hasNext()) {
            String worker=workers.next();
            JSONObject identities=checkpoint.optJSONObject("workerHistoryOwners");
            String identity=!checkpoint.has("workerHistoryOwners")?worker:identities==null?"":identities.optString(worker,"");
            // Preserve malformed legacy text for main without assigning it to a possibly recycled worker ID.
            if(identity.isEmpty()||identity.equals("main")||identity.length()>160)
                identity="quarantine:"+fingerprint(worker,"invalid-owner",0,identity);
            append(db,"worker:"+identity,identity,workerMessages.optJSONArray(worker));
        }
    }

    private static String match(String query) {
        return match(query,false);
    }
    private static String match(String query,boolean prefix) {
        Matcher tokens=TOKEN.matcher(query.toLowerCase(Locale.ROOT));
        ArrayList<String> terms=new ArrayList<>();
        while(tokens.find()&&terms.size()<8) {
            String word=tokens.group();
            if(word.length()>(prefix?0:1)&&word.length()<=40&&(prefix||!COMMON.contains(" "+word+" "))&&!terms.contains(word))terms.add(word);
        }
        ArrayList<String> quoted=new ArrayList<>();
        for(String term:terms)quoted.add("\""+term+(prefix?"*":"")+"\"");
        return String.join(prefix?" AND ":" OR ",quoted);
    }

    static JSONArray conversations(SQLiteDatabase db, String query) {
        return conversations(db,query,java.util.Map.of());
    }
    static JSONArray conversations(SQLiteDatabase db, String query,java.util.Map<String,String> titles) {
        if (query == null || query.length() > 300) throw new IllegalArgumentException("Invalid conversation search");
        String expression = match(query,true),normalized=query.trim().toLowerCase(Locale.ROOT);ArrayList<String> titleMatches=new ArrayList<>();
        if(!normalized.isEmpty())for(java.util.Map.Entry<String,String> entry:titles.entrySet())if(entry.getKey().matches("[a-f0-9-]{36}")&&entry.getValue().toLowerCase(Locale.ROOT).contains(normalized))titleMatches.add(entry.getKey());
        if(expression.isEmpty()&&!normalized.isEmpty()&&titleMatches.isEmpty())return new JSONArray();
        ArrayList<String> args=new ArrayList<>();String filter=" WHERE h.owner='main'";
        if(!normalized.isEmpty()){ArrayList<String> predicates=new ArrayList<>();if(!expression.isEmpty()){predicates.add("h.thread_id IN (SELECT m.thread_id FROM conversation_history m JOIN conversation_history_fts f ON f.docid=m.id WHERE m.owner='main' AND f.text MATCH ?)");args.add(expression);}if(!titleMatches.isEmpty()){predicates.add("instr(?,h.thread_id)>0");args.add(String.join(",",titleMatches));}filter+=" AND ("+String.join(" OR ",predicates)+")";}
        String sql = "SELECT h.thread_id,h.owner,MAX(h.timestamp),COUNT(*),(SELECT substr(x.text,1,100) FROM conversation_history x WHERE x.thread_id=h.thread_id AND x.role='user' ORDER BY x.timestamp,x.id LIMIT 1) FROM conversation_history h" + filter + " GROUP BY h.thread_id ORDER BY MAX(h.timestamp) DESC LIMIT 50";
        JSONArray result = new JSONArray();
        try (Cursor cursor = db.rawQuery(sql, args.toArray(new String[0]))) {
            while (cursor.moveToNext()) try {
                result.put(new JSONObject().put("threadId", cursor.getString(0)).put("owner", cursor.getString(1)).put("updatedAt", cursor.getLong(2)).put("count", cursor.getInt(3)).put("title", cursor.isNull(4) ? "Saved conversation" : cursor.getString(4)));
            } catch (org.json.JSONException invalid) { throw new IllegalStateException("Could not read conversation history", invalid); }
        }
        return result;
    }
    static JSONObject conversation(SQLiteDatabase db, String threadId, int offset) {
        if (threadId == null || threadId.isEmpty() || threadId.length() > 300 || offset < 0) throw new IllegalArgumentException("Invalid conversation page");
        int total;
        try (Cursor count = db.rawQuery("SELECT COUNT(*) FROM conversation_history WHERE thread_id=?", new String[]{threadId})) { count.moveToFirst(); total = count.getInt(0); }
        JSONArray messages = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT role,text,timestamp FROM conversation_history WHERE thread_id=? ORDER BY timestamp,id LIMIT 60 OFFSET ?", new String[]{threadId, String.valueOf(offset)})) {
            while (cursor.moveToNext()) try { messages.put(new JSONObject().put("role", cursor.getString(0)).put("text", cursor.getString(1)).put("timestamp", cursor.getLong(2))); }
            catch (org.json.JSONException invalid) { throw new IllegalStateException("Could not read conversation page", invalid); }
        }
        try { return new JSONObject().put("messages", messages).put("total", total).put("offset", offset); }
        catch (org.json.JSONException invalid) { throw new IllegalStateException("Could not read conversation page", invalid); }
    }
    static JSONArray resumeMain(SQLiteDatabase db,String threadId) {
        if(threadId==null||threadId.isEmpty()||threadId.length()>180)throw new IllegalArgumentException("Invalid conversation");
        ArrayList<JSONObject> tail=new ArrayList<>();int bytes=0;
        try(Cursor cursor=db.rawQuery("SELECT role,text,timestamp FROM conversation_history WHERE thread_id=? AND owner='main' ORDER BY timestamp DESC,id DESC LIMIT 2048",new String[]{threadId})){
            while(cursor.moveToNext()){
                String text=cursor.getString(1);int size=text.getBytes(StandardCharsets.UTF_8).length;
                if(bytes+size>6000000)break;bytes+=size;
                try{tail.add(new JSONObject().put("role",cursor.getString(0)).put("text",text).put("timestamp",cursor.getLong(2)));}catch(org.json.JSONException invalid){throw new IllegalStateException("Could not load conversation",invalid);}
            }
        }
        if(tail.isEmpty())throw new IllegalArgumentException("This main conversation could not be found");
        java.util.Collections.reverse(tail);return new JSONArray(tail);
    }
    static JSONArray search(SQLiteDatabase db,String query,int limit,String requester) {
        if(query==null||query.length()>300||limit<1||limit>50||requester==null||requester.isEmpty()||requester.length()>160)
            throw new IllegalArgumentException("Invalid history search");
        String expression=match(query);
        if(expression.isEmpty()&&!query.trim().isEmpty())return new JSONArray();
        boolean main="main".equals(requester);
        String scope=main?"":"h.owner IN ('main',?)";
        String base="SELECT h.id,h.thread_id,h.owner,h.role,h.timestamp,substr(h.text,1,1800) FROM conversation_history h";
        if(!expression.isEmpty())base+=" JOIN conversation_history_fts f ON f.docid=h.id";
        String where=expression.isEmpty()?scope:"f.text MATCH ?"+(main?"":" AND "+scope);
        String sql=base+(where.isEmpty()?"":" WHERE "+where)+" ORDER BY h.timestamp DESC,h.id DESC LIMIT ?";
        String[] args=expression.isEmpty()?(main?new String[]{String.valueOf(limit)}:new String[]{requester,String.valueOf(limit)})
            :(main?new String[]{expression,String.valueOf(limit)}:new String[]{expression,requester,String.valueOf(limit)});
        JSONArray results=new JSONArray();
        try(Cursor cursor=db.rawQuery(sql,args)) {
            while(cursor.moveToNext()) {
                try {results.put(new JSONObject().put("id",cursor.getLong(0)).put("threadId",cursor.getString(1))
                    .put("owner",cursor.getString(2)).put("role",cursor.getString(3))
                    .put("timestamp",cursor.getLong(4)).put("text",cursor.getString(5)));}
                catch(org.json.JSONException impossible) {throw new IllegalStateException(impossible);}
            }
        }
        return results;
    }

    static JSONObject read(SQLiteDatabase db,long id,String requester) {
        return read(db,id,requester,0,100100);
    }
    static JSONObject read(SQLiteDatabase db,long id,String requester,int offset,int limit) {
        if(id<=0||requester==null||requester.isEmpty()||requester.length()>160)
            throw new IllegalArgumentException("Invalid history reference");
        if(offset<0||offset>100100||limit<1||limit>100100)throw new IllegalArgumentException("Invalid history page");
        boolean main="main".equals(requester);
        String sql="SELECT id,thread_id,owner,role,timestamp,text FROM conversation_history WHERE id=?"+
            (main?"":" AND owner IN ('main',?)");
        String[] args=main?new String[]{String.valueOf(id)}:new String[]{String.valueOf(id),requester};
        try(Cursor cursor=db.rawQuery(sql,args)) {
            if(!cursor.moveToFirst())return new JSONObject();
            String text=cursor.getString(5);int total=text.codePointCount(0,text.length());
            int start=text.offsetByCodePoints(0,Math.min(offset,total));
            int end=text.offsetByCodePoints(start,Math.min(limit,Math.max(0,total-offset)));
            try {return new JSONObject().put("id",cursor.getLong(0)).put("threadId",cursor.getString(1))
                .put("owner",cursor.getString(2)).put("role",cursor.getString(3))
                .put("timestamp",cursor.getLong(4)).put("text",text.substring(start,end)).put("offset",offset)
                .put("totalCharacters",total).put("nextOffset",offset+limit<total?offset+limit:JSONObject.NULL);}
            catch(org.json.JSONException impossible) {throw new IllegalStateException(impossible);}
        }
    }
}
