package dev.pocket.agent;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Release-device lifecycle evidence. Run prepare/verify in separate instrumentation invocations. */
@RunWith(AndroidJUnit4.class)
public class LifecycleDeviceTest {
    private static final String MARKER = "lifecycle-device-test";
    private StateStore store;
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private StateStore store() { if (store == null) store = new StateStore(context()); return store; }
    @After public void closeStore() { if (store != null) store.close(); }
    private interface Condition { boolean check() throws Exception; }
    private void until(String description, long timeoutMs, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do { if (condition.check()) return; Thread.sleep(80); } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Timed out: " + description);
    }
    private RuntimeHost host() throws Exception {
        Context c = context();
        c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        until("Activity foreground", 15000, () -> resumedActivity() != null);
        c.startForegroundService(new Intent(c, AgentService.class).setAction(AgentService.ACTION_START));
        AtomicReference<RuntimeHost> ready = new AtomicReference<>();
        until("Node runtime started", 30000, () -> {
            RuntimeHost h = AgentService.peekHost();
            if (h == null) return false;
            try { h.memoryStats().get(2, TimeUnit.SECONDS); ready.set(h); return true; }
            catch (Exception starting) { return false; }
        });
        return ready.get();
    }
    private Activity resumedActivity() {
        AtomicReference<Activity> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED);
            for (Activity activity : activities) if (activity instanceof MainActivity) { result.set(activity); break; }
        }); return result.get();
    }
    private View findText(View root, String value) {
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) { View found = findText(group.getChildAt(i), value); if (found != null) return found; }
        }
        return null;
    }
    private View findDescription(View root, String value) {
        CharSequence description = root.getContentDescription();
        if (description != null && value.contentEquals(description)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) { View found = findDescription(group.getChildAt(i), value); if (found != null) return found; }
        }
        return null;
    }
    private JSONObject command(RuntimeHost host, JSONObject command) throws Exception {
        JSONObject result = host.dispatch(command).get(10, TimeUnit.SECONDS);
        assertTrue("Agent command was rejected", result.optBoolean("ok")); return result;
    }
    private JSONObject action(String name) throws Exception { return new JSONObject().put("action", name); }
    private JSONObject view() { return store().load(); }
    private JSONObject task(String id) {
        JSONArray tasks = view().optJSONArray("tasks");
        if (tasks != null) for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task != null && id.equals(task.optString("conversationId"))) return task;
        }
        return null;
    }
    private Set<String> taskIds() {
        Set<String> ids = new HashSet<>(); JSONArray tasks = view().optJSONArray("tasks");
        if (tasks != null) for (int i = 0; i < tasks.length(); i++) ids.add(tasks.optJSONObject(i).optString("conversationId"));
        return ids;
    }
    private void stopAndIdle(RuntimeHost h) throws Exception {
        command(h, action("stop_all")); until("all work idle", 15000, () -> !view().optBoolean("active"));
    }
    private RuntimeHost scriptedHost() throws Exception {
        new CredentialStore(context()).save(new JSONObject().put("authMode", "scripted").put("endpoint", "https://api.openai.com/v1").put("model", "scripted"));
        RuntimeHost h = host(); stopAndIdle(h); command(h, action("configure"));
        until("scripted provider configured", 5000, () -> "scripted".equals(view().optJSONObject("provider").optString("mode")));
        return h;
    }
    private JSONObject spawn(RuntimeHost h, String prompt, boolean requireRunning) throws Exception {
        Set<String> before = taskIds();
        command(h, action("send").put("text", prompt));
        AtomicReference<JSONObject> found = new AtomicReference<>();
        until("new delegated worker checkpoint", 15000, () -> {
            JSONArray tasks = view().optJSONArray("tasks"); if (tasks == null) return false;
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.getJSONObject(i);
                if (!before.contains(task.getString("conversationId")) && (!requireRunning || "running".equals(task.optString("status")))) { found.set(task); return true; }
            }
            return false;
        });
        return found.get();
    }
    private void waitCompleted(String id) throws Exception {
        until("worker completed and main collected completion", 45000, () -> {
            JSONObject t = task(id); if (t == null) return false;
            return ("completed".equals(t.optString("status")) || "success".equals(t.optString("status"))) && !view().optBoolean("active");
        });
    }
    private SQLiteDatabase ledger() {
        return SQLiteDatabase.openDatabase(context().getDatabasePath("operations.db").getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
    }
    private JSONArray ledgerRows() throws Exception {
        JSONArray rows = new JSONArray();
        try (SQLiteDatabase db = ledger(); Cursor cursor = db.rawQuery("SELECT id,fingerprint,result,created,epoch FROM operations ORDER BY id", null)) {
            while (cursor.moveToNext()) rows.put(new JSONArray().put(cursor.getString(0)).put(cursor.getString(1)).put(cursor.isNull(2) ? JSONObject.NULL : cursor.getString(2)).put(cursor.getLong(3)).put(cursor.getLong(4)));
        }
        return rows;
    }
    /** Leave an actual running, durable worker for the runner's adb force-stop between tests. */
    @Test public void prepareProcessDeath() throws Exception {
        RuntimeHost h = scriptedHost();
        JSONObject capabilities = new NativeTools(context()).execute("phone_capabilities", new JSONObject(), "lifecycle-test");
        String sentinel = capabilities.getString("operation_prefix") + "_lifecycle" + Long.toHexString(System.nanoTime());
        // Inert checkpoint fixture: an unresolved receipt with no native action ever dispatched.
        try (SQLiteDatabase db = ledger()) {
            ContentValues row = new ContentValues(); row.put("id", sentinel); row.put("fingerprint", "lifecycle-inert-no-dispatch");
            row.putNull("result"); row.put("created", System.currentTimeMillis()); row.put("epoch", System.currentTimeMillis() / 86400000L);
            db.insertOrThrow("operations", null, row);
        }
        String prompt = "delegate long delay=30000 lifecycle-death-" + Long.toHexString(System.nanoTime());
        JSONObject worker = spawn(h, prompt, true); String id = worker.getString("conversationId"); int generation = worker.getInt("generation");
        until("main finished dispatch and worker remains running", 10000, () -> !view().optBoolean("busy") && "running".equals(task(id).optString("status")));
        JSONArray transcript = view().getJSONArray("messages"); assertTrue(transcript.toString().contains(prompt));
        JSONObject marker = new JSONObject().put("workerId", id).put("generation", generation).put("prompt", prompt)
                .put("transcript", transcript).put("ledger", ledgerRows()).put("sentinel", sentinel).put("preparedPid", Process.myPid());
        assertTrue(context().getSharedPreferences(MARKER, Context.MODE_PRIVATE).edit().putString("checkpoint", marker.toString()).commit());
        // This line is intentionally safe to collect in public device logs; no transcript/secret is printed.
        System.out.println("POCKET_PROCESS_DEATH_PREPARED=" + new JSONObject().put("workerId", id).put("generation", generation).put("pid", Process.myPid()));
    }
    /** Run only after a separate adb force-stop; no setup/reset is allowed before reading the marker. */
    @Test public void verifyProcessDeath() throws Exception {
        String saved = context().getSharedPreferences(MARKER, Context.MODE_PRIVATE).getString("checkpoint", null);
        assertNotNull("Run prepareProcessDeath, then adb force-stop, then verifyProcessDeath", saved);
        JSONObject marker = new JSONObject(saved); String id = marker.getString("workerId"); int generation = marker.getInt("generation");
        assertNotEquals("Verification must run in a new Android process", marker.getInt("preparedPid"), Process.myPid());
        RuntimeHost h = host();
        try {
            until("same interrupted worker restored", 10000, () -> { JSONObject t = task(id); return t != null && t.optInt("generation") == generation && "interrupted".equals(t.optString("status")); });
            until("recovery completion delivered", 10000, () -> !view().optBoolean("active"));
            JSONArray before = marker.getJSONArray("transcript"), after = view().getJSONArray("messages");
            assertTrue("Main transcript was truncated", after.length() >= before.length());
            for (int i = 0; i < before.length(); i++) assertEquals("Main transcript changed at message " + i, before.getJSONObject(i).toString(), after.getJSONObject(i).toString());
            assertTrue("Original user request missing", after.toString().contains(marker.getString("prompt")));
            assertEquals("Restart must not dispatch or rewrite mutation records", marker.getJSONArray("ledger").toString(), ledgerRows().toString());
            try (SQLiteDatabase db = ledger(); Cursor cursor = db.rawQuery("SELECT result FROM operations WHERE id=?", new String[]{marker.getString("sentinel")})) {
                assertTrue("Unresolved operation disappeared", cursor.moveToFirst()); assertTrue("Unknown operation was replayed", cursor.isNull(0));
            }
            System.out.println("POCKET_PROCESS_DEATH_VERIFIED=" + new JSONObject().put("workerId", id).put("generation", generation).put("status", "interrupted").put("ledgerUnchanged", true).put("transcriptPreserved", true));
            context().getSharedPreferences(MARKER, Context.MODE_PRIVATE).edit().remove("checkpoint").commit();
        } finally { stopAndIdle(h); }
    }
    @Test public void mainThreadPromptAndDebugControlsWorkOnDevice() throws Exception {
        RuntimeHost h = scriptedHost();
        try {
            String custom = "Device-test custom main prompt";
            command(h, action("set_personal_instructions").put("text", custom));
            until("personal instructions saved", 5000, () -> custom.equals(view().optString("personalInstructions")));
            command(h, action("send").put("text", "hello before fresh thread"));
            until("main turn completed", 10000, () -> !view().optBoolean("active") && view().optJSONArray("messages").length() > 0);
            command(h, action("new_main"));
            until("fresh main state saved", 5000, () -> view().optJSONArray("messages").length() == 0 && view().optInt("mainTurns") == 0);
            assertEquals("New main thread must retain the personal instructions", custom, view().optString("personalInstructions"));

            Activity activity = resumedActivity(); assertNotNull(activity);
            AtomicReference<View> fresh = new AtomicReference<>(), copy = new AtomicReference<>(), jump = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                View root = activity.findViewById(android.R.id.content);
                fresh.set(findText(root, "New main thread")); copy.set(findText(root, "Copy history")); jump.set(findDescription(root, "Jump to latest message"));
            });
            assertNotNull("New main thread control missing", fresh.get());
            assertNotNull("Copy history control missing", copy.get());
            assertNotNull("Jump-to-bottom control missing", jump.get());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> copy.get().performClick());
            ClipboardManager clipboard = context().getSystemService(ClipboardManager.class);
            assertNotNull("Clipboard service missing", clipboard); assertTrue("Copy history did not publish a clip", clipboard.hasPrimaryClip());
            ClipData clip = clipboard.getPrimaryClip(); assertNotNull(clip); assertTrue(clip.getItemAt(0).coerceToText(context()).toString().contains(custom));

            AtomicReference<ScrollView> transcriptScroll = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ViewGroup frame = (ViewGroup) jump.get().getParent();
                for (int i = 0; i < frame.getChildCount(); i++) {
                    if (frame.getChildAt(i) instanceof ScrollView) { transcriptScroll.set((ScrollView) frame.getChildAt(i)); break; }
                }
                assertNotNull("Transcript scroll view missing", transcriptScroll.get());
                TextView transcript = (TextView) transcriptScroll.get().getChildAt(0);
                StringBuilder longTranscript = new StringBuilder();
                for (int i = 0; i < 160; i++) longTranscript.append("message ").append(i).append("\n");
                transcript.setText(longTranscript.toString());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ScrollView scroll = transcriptScroll.get();
                int viewport = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
                int bottom = Math.max(0, scroll.getChildAt(0).getHeight() - Math.max(0, viewport));
                assertTrue("Test transcript did not become scrollable", bottom > 0);
                scroll.scrollTo(0, bottom);
                scroll.scrollTo(0, 0);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals("Jump-to-bottom control did not appear while away from latest", View.VISIBLE, jump.get().getVisibility());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> jump.get().performClick());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ScrollView scroll = transcriptScroll.get();
                int viewport = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
                int bottom = Math.max(0, scroll.getChildAt(0).getHeight() - Math.max(0, viewport));
                assertTrue("Jump-to-bottom tap did not move the transcript", scroll.getScrollY() > 0);
                assertTrue("Jump-to-bottom tap did not reach latest message", Math.abs(bottom - scroll.getScrollY()) <= 2);
                assertEquals("Jump-to-bottom control stayed visible at latest message", View.GONE, jump.get().getVisibility());
            });

            JSONObject synthetic = new JSONObject().put("provider", new JSONObject().put("mode", "openai").put("model", "safe-model").put("apiKey", "synthetic-secret"))
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("text", "main-debug-message")))
                    .put("tasks", new JSONArray().put(new JSONObject().put("conversationId", "worker-1")))
                    .put("workerMessages", new JSONObject().put("worker-1", new JSONArray().put(new JSONObject().put("role", "assistant").put("text", "worker-debug-message"))));
            String export = MainActivity.debugHistory(synthetic);
            assertTrue(export.contains("main-debug-message")); assertTrue(export.contains("worker-debug-message"));
            assertTrue(export.contains("safe-model")); assertFalse("Debug export copied provider credentials", export.contains("synthetic-secret"));
        } finally { stopAndIdle(h); }
    }

    @Test public void activityRecreationKeepsWorker() throws Exception {
        RuntimeHost h = scriptedHost();
        try {
            JSONObject started = spawn(h, "delegate worker delay=30000 lifecycle-recreation", true);
            String id = started.getString("conversationId"); int generation = started.getInt("generation"); Activity original = resumedActivity(); assertNotNull(original);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(original::recreate);
            until("replacement Activity resumed", 15000, () -> { Activity current = resumedActivity(); return current != null && current != original; });
            assertSame("Activity recreation replaced the runtime", h, AgentService.peekHost());
            assertEquals(generation, task(id).getInt("generation")); assertEquals("Worker stopped during Activity recreation", "running", task(id).getString("status"));
            waitCompleted(id);
            System.out.println("POCKET_ACTIVITY_RECREATION=" + new JSONObject().put("workerId", id).put("generation", generation).put("sameHost", true).put("completed", true));
        } finally { stopAndIdle(h); }
    }
    private JSONObject sample(RuntimeHost h) throws Exception {
        return new JSONObject().put("pssKiB", Debug.getPss()).put("node", h.memoryStats().get(5, TimeUnit.SECONDS)).put("retainedWorkers", taskIds().size());
    }
    private long directoryBytes(java.io.File path) {
        if(path.isFile()) return path.length();
        java.io.File[] children=path.listFiles();long size=0;
        if(children!=null)for(java.io.File child:children)size+=directoryBytes(child);
        return size;
    }
    @Test public void reportFootprint() throws Exception {
        RuntimeHost h = scriptedHost();
        try {
            int retainedBefore = taskIds().size(); assertTrue("Footprint test needs five free retained-worker slots; use a fresh test install", retainedBefore <= 7);
            JSONObject before = sample(h); JSONArray cycles = new JSONArray();
            for (int i = 0; i < 5; i++) {
                long start = SystemClock.elapsedRealtime();
                JSONObject worker = spawn(h, "delegate worker delay=80 footprint-cycle-" + i, false);
                waitCompleted(worker.getString("conversationId"));
                cycles.put(new JSONObject().put("cycle", i + 1).put("completionMs", SystemClock.elapsedRealtime() - start).put("sample", sample(h)));
            }
            assertEquals("Completed worker conversations should remain retained", retainedBefore + 5, taskIds().size());
            until("idle before CPU sample", 5000, () -> !view().optBoolean("active"));
            long wallStart = SystemClock.elapsedRealtime(), cpuStart = Process.getElapsedCpuTime(); Thread.sleep(1000);
            long cpuMs = Process.getElapsedCpuTime() - cpuStart, wallMs = SystemClock.elapsedRealtime() - wallStart;
            JSONObject report = new JSONObject().put("pid", Process.myPid()).put("sdk", Build.VERSION.SDK_INT).put("abis", new JSONArray(Build.SUPPORTED_ABIS))
                    .put("version", context().getPackageManager().getPackageInfo(context().getPackageName(), 0).versionName)
                    .put("before", before).put("cycles", cycles).put("after", sample(h)).put("idleCpuMs", cpuMs).put("idleWallMs", wallMs)
                    .put("apkBytes",new java.io.File(context().getApplicationInfo().sourceDir).length())
                    .put("nativeLibraryBytes",directoryBytes(new java.io.File(context().getApplicationInfo().nativeLibraryDir)))
                    .put("privateDataBytes",directoryBytes(context().getDataDir()))
                    .put("scope", "Single device sample; includes Android and instrumentation overhead. No stock-runtime comparison or physical battery claim.");
            System.out.println("POCKET_FOOTPRINT=" + report);
        } finally { stopAndIdle(h); }
    }
}
