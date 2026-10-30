package dev.pocket.agent;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ConversationHistoryDeviceTest {
    @Test public void chatSearchMatchesPrefixesAndRenamedTitlesWithoutWorkerLeakage() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();String name="chat-prefix-search.db";context.deleteDatabase(name);
        try(StateStore store=new StateStore(context,name)){
            JSONObject saved=checkpoint("Find the Ballon d'Or ranking and update my notes",100);
            saved.put("workerMessages",new JSONObject().put("worker-a",new JSONArray().put(new JSONObject().put("role","user").put("timestamp",200).put("content","Ballistic private worker text"))));store.saveDurable(saved);
            for(String query:new String[]{"bal","ball","ballon","BAL","ball note"})assertEquals(query,1,store.listConversations(query).length());
            assertEquals(0,store.listConversations("ballistic").length());
            assertEquals(0,store.listConversations("travel").length());
            assertEquals(1,store.listConversations("trav",java.util.Map.of("33333333-3333-4333-8333-333333333333","Travel plans")).length());
            assertEquals(0,store.listConversations("'; DROP TABLE conversation_history --").length());assertEquals(1,store.listConversations("bal").length());
        }finally{context.deleteDatabase(name);}
    }
    @Test public void drawerSearchAndPagesReadTheOriginalArchive() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="drawer-history-instrumentation.db"; context.deleteDatabase(name);
        try (StateStore store=new StateStore(context,name)) {
            JSONArray messages=new JSONArray();
            for (int i=0;i<65;i++) messages.put(new JSONObject().put("role","user").put("timestamp",1000+i).put("content","Sapphire archive message "+i));
            JSONObject saved=checkpoint("",1).put("mainMessages",messages);
            saved.put("workerMessages",new JSONObject().put("worker-a",new JSONArray().put(new JSONObject().put("role","user").put("timestamp",2000).put("content","Sapphire internal worker prompt"))));
            store.saveDurable(saved);
            JSONArray chats=store.listConversations("Sapphire");
            assertEquals(1,chats.length());
            String thread=chats.getJSONObject(0).getString("threadId");
            assertEquals(65,chats.getJSONObject(0).getInt("count"));
            JSONArray first=store.readConversation(thread,0).getJSONArray("messages"),second=store.readConversation(thread,60).getJSONArray("messages");
            assertEquals(60,first.length());assertEquals(5,second.length());
            assertEquals("Sapphire archive message 0",first.getJSONObject(0).getString("text"));
            assertEquals("Sapphire archive message 59",first.getJSONObject(59).getString("text"));
            assertEquals("Sapphire archive message 60",second.getJSONObject(0).getString("text"));
            assertEquals("Sapphire archive message 64",second.getJSONObject(4).getString("text"));
            assertEquals(0,store.listConversations("obsidian").length());
            assertEquals(1,store.listConversations("").length());
            assertEquals(65,store.resumeMainConversation(thread).length());
            assertThrows(IllegalArgumentException.class,()->store.resumeMainConversation("worker:worker-a"));
        } finally { context.deleteDatabase(name); }
    }
    private JSONObject checkpoint(String text,long timestamp) throws Exception {
        return new JSONObject().put("mainThreadId","33333333-3333-4333-8333-333333333333")
            .put("mainMessages",new JSONArray().put(new JSONObject().put("role","user")
                .put("timestamp",timestamp).put("content",text)));
    }
    @Test public void versionTwoArchiveGainsProgressWithoutDuplicatingHistory() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-v2-instrumentation.db";context.deleteDatabase(name);
        try {
            try(StateStore state=new StateStore(context,name)) {
                state.saveDurable(checkpoint("Legacy sapphire receipt.",199));
                state.getWritableDatabase().execSQL("DROP TABLE conversation_history_progress");
                state.getWritableDatabase().setVersion(2);
            }
            try(StateStore migrated=new StateStore(context,name)) {
                assertEquals(3,migrated.getReadableDatabase().getVersion());
                assertEquals(1,migrated.searchHistory("sapphire",10).length());
                migrated.saveDurable(migrated.loadDurable());
                assertEquals(1,migrated.searchHistory("sapphire",10).length());
            }
        } finally {context.deleteDatabase(name);}
    }
    @Test public void versionOneMigrationPreservesAndArchivesExistingCheckpoint() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-migration-instrumentation.db";context.deleteDatabase(name);
        JSONObject old=checkpoint("Legacy obsidian warranty receipt.",201);old.remove("mainThreadId");
        try {
            try(android.database.sqlite.SQLiteOpenHelper legacy=new android.database.sqlite.SQLiteOpenHelper(context,name,null,1) {
                @Override public void onCreate(android.database.sqlite.SQLiteDatabase db) {
                    db.execSQL("CREATE TABLE snapshot (id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL)");
                    db.execSQL("CREATE TABLE runtime_state (id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL)");
                    db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, json TEXT NOT NULL)");
                }
                @Override public void onUpgrade(android.database.sqlite.SQLiteDatabase db,int from,int to) {throw new AssertionError();}
            }) {
                legacy.getWritableDatabase().execSQL("INSERT INTO runtime_state VALUES(1,?)",new Object[]{old.toString()});
                legacy.getWritableDatabase().execSQL("INSERT INTO snapshot VALUES(1,?)",new Object[]{"{\"mainTurns\":7}"});
                legacy.getWritableDatabase().execSQL("INSERT INTO events(at,json) VALUES(1,?)",new Object[]{"{\"type\":\"ready\"}"});
            }
            try(StateStore migrated=new StateStore(context,name)) {
                assertEquals(3,migrated.getReadableDatabase().getVersion());
                assertEquals(old.toString(),migrated.loadDurable().toString());
                assertEquals(7,migrated.load().getInt("mainTurns"));
                assertEquals(1,migrated.recentEvents().length());
                JSONArray hits=migrated.searchHistory("obsidian",10);
                assertEquals(1,hits.length());
                assertEquals("00000000-0000-4000-8000-000000000000",hits.getJSONObject(0).getString("threadId"));
            }
        } finally {context.deleteDatabase(name);}
    }
    @Test public void malformedWorkerScopesAreQuarantinedWithoutBlockingCheckpoints() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-quarantine-instrumentation.db";context.deleteDatabase(name);
        JSONObject saved=checkpoint("Valid agate main transcript.",207)
            .put("workerMessages",new JSONObject().put("abc",new JSONArray().put(new JSONObject()
                .put("role","assistant").put("timestamp",208).put("content","Private beryl transcript."))))
            .put("workerHistoryOwners",new JSONObject().put("abc","main"));
        try(StateStore state=new StateStore(context,name)) {
            state.saveDurable(saved);
            assertEquals(saved.toString(),state.loadDurable().toString());
            assertEquals(1,state.searchHistory("agate",10).length());
            JSONArray quarantined=state.searchHistory("beryl",10);
            assertEquals(1,quarantined.length());assertTrue(quarantined.getJSONObject(0).getString("owner").startsWith("quarantine:"));
            assertEquals(0,state.searchHistory("beryl",10,"abc").length());
            assertEquals(0,state.readHistory(quarantined.getJSONObject(0).getLong("id"),"abc").length());
        } finally {context.deleteDatabase(name);}
    }
    @Test public void corruptLegacyCheckpointDoesNotBlockUiDatabaseMigration() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-corrupt-migration.db";context.deleteDatabase(name);
        try {
            try(StateStore seeded=new StateStore(context,name)) {
                seeded.save(new JSONObject().put("mainTurns",4));
                seeded.getWritableDatabase().execSQL("INSERT INTO runtime_state VALUES(1,'not-json')");
                seeded.getWritableDatabase().execSQL("DROP TABLE conversation_history_progress");
                seeded.getWritableDatabase().setVersion(2);
            }
            try(StateStore migrated=new StateStore(context,name)) {
                assertEquals(3,migrated.getReadableDatabase().getVersion());assertEquals(4,migrated.load().getInt("mainTurns"));
                assertEquals(0,migrated.searchHistory("",10).length());
                try(android.database.Cursor original=migrated.getReadableDatabase().rawQuery("SELECT json FROM runtime_state",null)) {
                    assertTrue(original.moveToFirst());assertEquals("not-json",original.getString(0));
                }
            }
        } finally {context.deleteDatabase(name);}
    }
    @Test public void migrationWriteFailurePreservesLegacyDatabaseAndVersion() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-failed-migration.db";context.deleteDatabase(name);
        JSONObject saved=checkpoint("Preserve jasper legacy checkpoint.",209);
        try {
            try(StateStore seeded=new StateStore(context,name)) {
                seeded.saveDurable(saved);
                seeded.getWritableDatabase().execSQL("DROP TABLE conversation_history_progress");
                seeded.getWritableDatabase().execSQL("CREATE TRIGGER reject_migration BEFORE INSERT ON conversation_history BEGIN SELECT RAISE(ABORT,'synthetic migration failure'); END");
                seeded.getWritableDatabase().setVersion(2);
            }
            try(StateStore migrated=new StateStore(context,name)) {
                try {migrated.getReadableDatabase();fail("Migration write failure was ignored");}
                catch(IllegalStateException expected) {
                    assertEquals("Legacy history migration failed; original database retained",expected.getMessage());
                    assertTrue(expected.getCause() instanceof android.database.sqlite.SQLiteConstraintException);
                }
            }
            try(android.database.sqlite.SQLiteDatabase original=android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).getAbsolutePath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(2,original.getVersion());
                try(android.database.Cursor row=original.rawQuery("SELECT json FROM runtime_state",null)) {
                    assertTrue(row.moveToFirst());assertEquals(saved.toString(),row.getString(0));
                }
                try(android.database.Cursor progress=original.rawQuery("SELECT name FROM sqlite_master WHERE name='conversation_history_progress'",null)) {assertEquals(0,progress.getCount());}
            }
        } finally {context.deleteDatabase(name);}
    }
    @Test public void failedCheckpointRollsBackArchiveAndSearchIndex() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-rollback-instrumentation.db";context.deleteDatabase(name);
        try(StateStore state=new StateStore(context,name)) {
            JSONObject original=checkpoint("Committed quartz receipt.",301);state.saveDurable(original);
            state.getWritableDatabase().execSQL("CREATE TRIGGER reject_checkpoint BEFORE INSERT ON runtime_state BEGIN SELECT RAISE(ABORT,'synthetic checkpoint failure'); END");
            try {state.saveDurable(checkpoint("Uncommitted zircon receipt.",302));fail("Failed checkpoint was accepted");}
            catch(android.database.sqlite.SQLiteConstraintException expected) {assertTrue(expected.getMessage().contains("synthetic checkpoint failure"));}
            assertEquals(original.toString(),state.loadDurable().toString());
            assertEquals(1,state.searchHistory("",10).length());
            assertEquals(0,state.searchHistory("zircon",10).length());
            state.getWritableDatabase().execSQL("DROP TRIGGER reject_checkpoint");
            state.saveDurable(checkpoint("Uncommitted zircon receipt.",302));
            assertEquals(1,state.searchHistory("zircon",10).length());
        } finally {context.deleteDatabase(name);}
    }
    @Test public void mainAndWorkerHistorySurviveResetByteCheckpointAndReopen() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-instrumentation.db";context.deleteDatabase(name);
        JSONObject user=new JSONObject().put("role","user").put("timestamp",101).put("content",
            new JSONArray().put(new JSONObject().put("type","text").put("text","Discuss the basalt motherboard return.")));
        JSONObject assistant=new JSONObject().put("role","assistant").put("timestamp",102).put("content",
            new JSONArray().put(new JSONObject().put("type","text").put("text","The basalt warranty receipt is ready.")));
        JSONObject worker=new JSONObject().put("role","assistant").put("timestamp",103).put("content",
            new JSONArray().put(new JSONObject().put("type","text").put("text","Verified the basalt receipt in downloads.")));
        JSONObject checkpoint=new JSONObject().put("mainThreadId","11111111-1111-4111-8111-111111111111")
            .put("mainMessages",new JSONArray().put(user).put(assistant))
            .put("workerMessages",new JSONObject().put("worker-1",new JSONArray().put(worker)));
        try(StateStore state=new StateStore(context,name)) {
            state.saveDurable(checkpoint);
            state.saveDurable(checkpoint);
            assertEquals(3,state.searchHistory("basalt",10).length());
            assertEquals(1,state.searchHistory("downloads",10).length());
            assertEquals(1,state.searchHistory("downloads",10,"worker-1").length());
            assertEquals(0,state.searchHistory("downloads",10,"worker-2").length());
            assertEquals(2,state.searchHistory("basalt",10,"worker-2").length());
            JSONObject next=new JSONObject().put("mainThreadId","22222222-2222-4222-8222-222222222222")
                .put("mainMessages",new JSONArray().put(new JSONObject().put("role","user")
                    .put("timestamp",104).put("content","Fresh basalt thread.")));
            state.saveDurableBytes(next.toString().getBytes(StandardCharsets.UTF_8));
            assertEquals(4,state.searchHistory("basalt",10).length());
            assertEquals(1,state.searchHistory("warranty",10).length());
            long id=state.searchHistory("downloads",10).getJSONObject(0).getLong("id");
            assertTrue(state.readHistory(id,"worker-1").getString("text").contains("downloads"));
            assertEquals(0,state.readHistory(id,"worker-2").length());
            assertEquals(4,state.searchHistory("",10).length());
            assertEquals(0,state.searchHistory("the",10).length());
            assertEquals(0,state.searchHistory("!!!",10).length());
            assertEquals("Fresh basalt thread.",state.loadDurable().getJSONArray("mainMessages").getJSONObject(0).getString("content"));
        }
        try(StateStore reopened=new StateStore(context,name)) {
            JSONArray found=reopened.searchHistory("motherboard",10);
            assertEquals(1,found.length());
            assertEquals("main",found.getJSONObject(0).getString("owner"));
            assertEquals(4,reopened.searchHistory("basalt",10).length());
        } finally {context.deleteDatabase(name);}
    }
    @Test public void longEscapedMessagesPageWithoutLossAndByteSavesSkipUnrelatedPayloads() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-page-instrumentation.db";context.deleteDatabase(name);
        String text="start\u0000"+"\u0001😀".repeat(18000);
        JSONObject saved=checkpoint(text,401).put("unrelated",new JSONObject().put("large","x".repeat(1600000)));
        try(StateStore state=new StateStore(context,name)) {
            state.saveDurableBytes(saved.toString().getBytes(StandardCharsets.UTF_8));
            long id=state.searchHistory("",10).getJSONObject(0).getLong("id");
            StringBuilder joined=new StringBuilder();int offset=0;
            while(true) {
                JSONObject page=state.readHistory(id,"main",offset,4000);
                assertTrue("Escaped page must leave room in the tool envelope",page.toString().length()<31000);
                joined.append(page.getString("text"));
                if(page.isNull("nextOffset"))break;
                assertTrue(page.getInt("nextOffset")>offset);offset=page.getInt("nextOffset");
            }
            assertEquals(text.trim(),joined.toString());
            assertEquals(1600000,state.loadDurable().getJSONObject("unrelated").getString("large").length());
        } finally {context.deleteDatabase(name);}
    }
    @Test public void shutdownNeutralizationDoesNotDependOnArchiveHealth() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-shutdown-instrumentation.db";context.deleteDatabase(name);
        try(StateStore state=new StateStore(context,name)) {
            JSONObject saved=checkpoint("Preserve the topaz transcript.",501).put("busy",true)
                .put("inbox",new JSONArray().put(new JSONObject().put("type","user").put("text","pending")));
            state.saveDurable(saved);
            state.getWritableDatabase().execSQL("CREATE TRIGGER reject_archive BEFORE INSERT ON conversation_history BEGIN SELECT RAISE(ABORT,'synthetic archive failure'); END");
            state.markDurableInterrupted();
            JSONObject neutralized=state.loadDurable();
            assertFalse(neutralized.getBoolean("busy"));assertTrue(neutralized.getBoolean("mainPaused"));
            assertEquals(0,neutralized.getJSONArray("inbox").length());
            assertEquals(saved.getJSONArray("mainMessages").toString(),neutralized.getJSONArray("mainMessages").toString());
            assertEquals(1,state.searchHistory("topaz",10).length());
        } finally {context.deleteDatabase(name);}
    }
    @Test public void archiveCursorSkipsOldWritesAndInvalidatesAfterTranscriptTrimming() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="history-progress-instrumentation.db";context.deleteDatabase(name);
        try(StateStore state=new StateStore(context,name)) {
            JSONObject saved=checkpoint("First emerald receipt.",601);state.saveDurable(saved);
            state.getWritableDatabase().execSQL("CREATE TABLE archive_attempts(n INTEGER)");
            state.getWritableDatabase().execSQL("INSERT INTO archive_attempts VALUES(0)");
            state.getWritableDatabase().execSQL("CREATE TRIGGER count_archive BEFORE INSERT ON conversation_history BEGIN UPDATE archive_attempts SET n=n+1; END");
            state.saveDurable(saved);
            try(android.database.Cursor count=state.getReadableDatabase().rawQuery("SELECT n FROM archive_attempts",null)){assertTrue(count.moveToFirst());assertEquals(0,count.getInt(0));}
            saved.getJSONArray("mainMessages").put(new JSONObject().put("role","assistant").put("timestamp",602).put("content","Second emerald receipt."));
            state.saveDurable(saved);
            try(android.database.Cursor count=state.getReadableDatabase().rawQuery("SELECT n FROM archive_attempts",null)){assertTrue(count.moveToFirst());assertEquals(1,count.getInt(0));}
            saved.getJSONArray("mainMessages").remove(0);state.saveDurable(saved);
            assertEquals(2,state.searchHistory("emerald",10).length());
            saved.put("workerMessages",new JSONObject().put("abc",new JSONArray().put(new JSONObject().put("role","assistant").put("timestamp",603).put("content","Private garnet receipt."))))
                .put("workerHistoryOwners",new JSONObject().put("abc","abc@first"));
            state.saveDurable(saved);
            assertEquals(1,state.searchHistory("garnet",10,"abc@first").length());
            assertEquals(0,state.searchHistory("garnet",10,"abc@second").length());
        } finally {context.deleteDatabase(name);}
    }
}
