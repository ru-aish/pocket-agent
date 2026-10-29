package dev.pocket.agent;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.view.WindowManager;
import android.widget.AdapterView;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.SeekBar;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small native text surface; service/session ownership never follows this Activity. */
public final class MainActivity extends Activity implements AgentService.Observer, SubscriptionVoiceService.Observer {
    private TextView status, transcript, workerTranscript;
    private ScrollView messages;
    private EditText input;
    private LinearLayout taskList;
    private Button send, jumpToBottom, voiceConversation;
    private TextView voiceConversationStatus;
    private static final int START_VOICE_PERMISSIONS = 109;
    private JSONObject state = new JSONObject();
    private String viewedWorker;
    private String deferredUserMessage;
    private Spinner modelPicker;
    private Spinner reasoningPicker;
    private String renderedReasoningEffort = CredentialStore.DEFAULT_REASONING_EFFORT;
    private String modelRequestId;
    private String connectionTestId;
    private java.util.function.Consumer<JSONObject> connectionTestResult;
    private String mcpRequestId;
    private String[] modelIds = {};
    private final java.util.Map<String, String[]> modelReasoningEfforts = new java.util.HashMap<>();
    private final java.util.Map<String, String> modelDefaultReasoningEfforts = new java.util.HashMap<>();
    private static final String[] REASONING_EFFORTS = {"minimal", "low", "medium", "high", "xhigh", "max"};
    private StateStore store;
    private static final int IMPORT_CODEX = 90;
    private final ExecutorService accountFiles = Executors.newSingleThreadExecutor();
    private final ExecutorService historyQueries = Executors.newSingleThreadExecutor();
    private CodexAccountAuth accountFlow;
    private AlertDialog accountDialog;
    private long accountGeneration;
    private long pendingPickerGeneration = -1;
    private int pendingPickerRequest = -1;
    private Future<?> pendingImport;
    private String pendingVoiceInvitation;
    private DisplayViewer displayViewer;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); store = new StateStore(this);
        LinearLayout root = column(); root.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()|android.view.WindowInsets.Type.ime());
            android.view.WindowInsetsController controller=view.getWindowInsetsController();
            if(controller!=null)controller.setSystemBarsAppearance(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            view.setPadding(dp(14)+bars.left,dp(12)+bars.top,dp(14)+bars.right,dp(12)+bars.bottom);return insets;
        });
        LinearLayout toolbar = row(); TextView title = text("Pocket Agent"); title.setTextSize(22); title.setTypeface(null, Typeface.BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(button("Activity", this::autonomyActivity));
        toolbar.addView(button("Settings", this::settings)); root.addView(toolbar);
        status = text("Stopped · Send starts the runtime"); root.addView(status);
        displayViewer = new DisplayViewer(this, () -> state);
        root.addView(button("Displays", displayViewer::show));
        LinearLayout historyActions = row();
        historyActions.addView(button("New main thread", this::newMainThread), new LinearLayout.LayoutParams(0, -2, 1));
        historyActions.addView(button("Copy history", this::copyHistory), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(historyActions);
        root.addView(button("Search saved conversations", this::searchHistory));
        FrameLayout transcriptFrame = new FrameLayout(this);
        messages = new ScrollView(this); transcript = text(""); transcript.setTextIsSelectable(true); transcript.setPadding(0, dp(12), 0, dp(12)); messages.addView(transcript);
        transcriptFrame.addView(messages, new FrameLayout.LayoutParams(-1, -1));
        jumpToBottom = button("↓", () -> { messages.fullScroll(View.FOCUS_DOWN); messages.post(this::updateJumpToBottom); });
        jumpToBottom.setContentDescription("Jump to latest message"); jumpToBottom.setVisibility(View.GONE); jumpToBottom.setMinWidth(0); jumpToBottom.setMinimumWidth(0);
        FrameLayout.LayoutParams jumpParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.BOTTOM); jumpParams.setMargins(0, 0, dp(6), dp(6));
        transcriptFrame.addView(jumpToBottom, jumpParams);
        messages.setOnScrollChangeListener((view, x, y, oldX, oldY) -> updateJumpToBottom());
        root.addView(transcriptFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView tasksTitle = text("Tasks"); tasksTitle.setTypeface(null, Typeface.BOLD); root.addView(tasksTitle);
        ScrollView taskScroll = new ScrollView(this); taskList = column(); taskScroll.addView(taskList); root.addView(taskScroll, new LinearLayout.LayoutParams(-1, dp(112)));
        input = new EditText(this); input.setHint("Message main agent"); input.setMinLines(1); input.setMaxLines(4); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); root.addView(input);
        LinearLayout controls = row(); send = button("Send", () -> {
            String value = input.getText().toString().trim(); if (value.isEmpty()) return;
            if (AgentService.isStopping()) {
                deferredUserMessage = value;
                status.setText("Agent stopping · message will send after shutdown");
                return;
            }
            try {
                sendMainMessage(value);
                deferredUserMessage = null;
                input.setText("");
            } catch (RuntimeException error) { status.setText("Unable to submit message · try again"); }
        }); controls.addView(send, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(button("Stop response", () -> command(json("action", "stop_main"))));
        controls.addView(button("Stop all", () -> {
            AutonomyStore.requestEnabled(this,false,error->runOnUiThread(()->toast("Could not persist automation pause")));
            RuntimeHost shared=RuntimeOwner.peekHost();if(shared!=null)shared.dispatch(json("action","stop_all"));
            if (AgentService.isRunning()) startService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_STOP));
        })); root.addView(controls);
        voiceConversation = button("Start Codex voice", this::toggleVoiceConversation);
        root.addView(voiceConversation);
        voiceConversationStatus = text("Codex voice is off");
        root.addView(voiceConversationStatus);
        root.addView(button("Stop assistant listening", () -> {
            stopService(new Intent(this, VoiceListeningService.class));
        }));
        setContentView(root);
        AutonomyScheduler.arm(this);
        if(getIntent().getBooleanExtra("openAutonomy",false))root.post(this::autonomyActivity);
        pendingVoiceInvitation = saved == null ? null : saved.getString("voiceInvitation");
        root.post(() -> handleVoiceInvitation(getIntent()));
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        getWindow().getDecorView().post(() -> handleVoiceInvitation(intent));
    }
    @Override protected void onSaveInstanceState(Bundle saved) {
        super.onSaveInstanceState(saved);
        saved.putString("voiceInvitation", pendingVoiceInvitation);
    }
    private void handleVoiceInvitation(Intent intent) {
        if (!VoiceConversationInvitation.ACCEPT.equals(intent.getAction())) return;
        String id = intent.getStringExtra("invitationId");
        intent.setAction(null); // Activity recreation must not replay an accepted invitation.
        if (!VoiceConversationInvitation.canAccept(this, id)) {
            toast("Voice invitation expired or no longer matches this chat");
            return;
        }
        pendingVoiceInvitation = id;
        if (SubscriptionVoiceService.isActive()) {
            VoiceConversationInvitation.accept(this, id);
            pendingVoiceInvitation = null;
        } else requestVoiceConversation();
    }
    @Override protected void onStart() {
        super.onStart(); AgentService.observe(this); SubscriptionVoiceService.observe(this); state = store.load(); render();
        JSONArray events = store.recentEvents();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            long ageMs = System.currentTimeMillis() - event.optLong("atMs");
            if ("voice_delivery_error".equals(event.optString("type"))
                    && ageMs >= 0 && ageMs < 120000) {
                status.setText("Voice error · " + event.optString("error")); break;
            }
        }
    }
    @Override protected void onStop() { displayViewer.dismiss(); AgentService.unobserve(this); SubscriptionVoiceService.unobserve(this); super.onStop(); }
    @Override public void onVoiceState(boolean active, String message) {
        voiceConversation.setText(active ? "Disconnect Codex voice" : "Start Codex voice");
        voiceConversationStatus.setText(message);
    }
    private void sendMainMessage(String text) {
        if (!SubscriptionVoiceService.sendTyped(text)) command(json("action", "send", "text", text));
    }
    private void toggleVoiceConversation() {
        if (SubscriptionVoiceService.isActive()) {
            stopService(new Intent(this, SubscriptionVoiceService.class));
            return;
        }
        if (AgentService.isStopping()) { toast("Wait for the agent to finish stopping, then start voice"); return; }
        // A direct button tap is fresh consent, independent of a retained invitation attempt.
        pendingVoiceInvitation = null;
        requestVoiceConversation();
    }
    private void requestVoiceConversation() {
        java.util.ArrayList<String> needed = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), START_VOICE_PERMISSIONS);
            return;
        }
        startVoiceConversation();
    }
    private void responsePreference() {
        String[] modes = {VoiceConversationInvitation.TEXT, VoiceConversationInvitation.OFFER};
        String[] labels = {"Messages only", "Voice invitations allowed"};
        int selected = java.util.Arrays.asList(modes).indexOf(VoiceConversationInvitation.mode(this));
        new AlertDialog.Builder(this).setTitle("Response preference")
            .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                try { VoiceConversationInvitation.setMode(this, modes[which]); toast("Response preference saved"); }
                catch (RuntimeException failure) { toast("Could not save response preference"); }
                dialog.dismiss();
            })
            .setNegativeButton("Close", null).show();
    }
    private void startVoiceConversation() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceConversationStatus.setText("Allow microphone access to start a voice conversation");
            if (pendingVoiceInvitation != null) VoiceConversationInvitation.decline(this, pendingVoiceInvitation);
            pendingVoiceInvitation = null;
            return;
        }
        if (AgentService.isStopping()) { toast("Wait for the agent to finish stopping, then start voice"); return; }
        try {
            if (pendingVoiceInvitation != null) {
                if (!VoiceConversationInvitation.startAccepted(this, pendingVoiceInvitation,
                        () -> startForegroundService(SubscriptionVoiceSettings.conversationIntent(this)))) {
                    pendingVoiceInvitation = null;
                    toast("Voice invitation is no longer available"); return;
                }
                pendingVoiceInvitation = null;
            } else {
                startForegroundService(SubscriptionVoiceSettings.conversationIntent(this));
                VoiceConversationInvitation.cancel(this, "manually_started");
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                toast("Voice notifications are off; use Disconnect voice in the app to finish");
        } catch (RuntimeException denied) {
            android.util.Log.w("PocketVoice", "Android could not start the voice service", denied);
            voiceConversationStatus.setText("Android could not start voice · check microphone access");
        }
    }
    @Override protected void onDestroy() {
        invalidateAccountOperations();
        displayViewer.close();
        accountFiles.shutdownNow(); historyQueries.shutdownNow(); store.close(); super.onDestroy();
    }
    @Override public void onAgentEvent(JSONObject event) {
        String type = event.optString("type");
        if (type.equals("state")) { state = event.optJSONObject("state"); if (state == null) state = new JSONObject(); render(); }
        else if (type.equals("connection_validation") && event.optString("requestId").equals(connectionTestId)) {
            java.util.function.Consumer<JSONObject> callback = connectionTestResult;
            connectionTestId = null; connectionTestResult = null;
            if (callback != null) callback.accept(event);
        }
        else if (type.equals("models") && modelPicker != null && event.optString("requestId").equals(modelRequestId)) {
            String failure = event.optString("error");
            if (!failure.isEmpty()) { toast(failure); return; }
            JSONArray models = event.optJSONArray("models");
            if (models == null) { toast("Provider returned no model list"); return; }
            String[] labels = new String[models.length() + 1];
            modelIds = new String[models.length() + 1];
            modelReasoningEfforts.clear();
            modelDefaultReasoningEfforts.clear();
            labels[0] = "Manual model ID (edit field above)";
            for (int i = 0; i < models.length(); i++) {
                JSONObject entry = models.optJSONObject(i);
                String id = entry == null ? "" : entry.optString("id");
                modelIds[i + 1] = id;
                String name = entry == null ? id : entry.optString("name", id);
                labels[i + 1] = name.equals(id) ? id : name + " (" + id + ")";
                JSONArray levels = entry == null ? null : entry.optJSONArray("supportedReasoningLevels");
                if (levels != null) {
                    java.util.ArrayList<String> supported = new java.util.ArrayList<>();
                    for (int j = 0; j < levels.length(); j++) {
                        String effort = levels.optString(j);
                        if (isReasoningEffort(effort) && !supported.contains(effort)) supported.add(effort);
                    }
                    modelReasoningEfforts.put(id, supported.toArray(new String[0]));
                } else modelReasoningEfforts.put(id, new String[0]);
                String defaultEffort = entry == null ? "" : entry.optString("defaultReasoningLevel");
                if (isReasoningEffort(defaultEffort)) modelDefaultReasoningEfforts.put(id, defaultEffort);
            }
            modelPicker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
            modelPicker.setSelection(0);
            modelPicker.setEnabled(true);
            toast("Discovered " + models.length() + " models");
        }
        else if (type.equals("mcp_tools") && event.optString("requestId").equals(mcpRequestId)) {
            String failure=event.optString("error");
            if(!failure.isEmpty()){toast("MCP discovery failed · " + failure);return;}
            JSONObject server=event.optJSONObject("server");JSONArray tools=server==null?null:server.optJSONArray("tools");
            StringBuilder names=new StringBuilder();
            if(tools!=null)for(int i=0;i<tools.length();i++){if(i>0)names.append(", ");names.append(tools.optString(i));}
            toast("Connected · " + (tools==null?0:tools.length()) + " tools" + (names.length()==0?"":" · "+names));
        }
        else if (type.equals("error")) { status.setText("Error · " + event.optString("error", "Runtime unavailable")); }
        else if (type.equals("voice_delivery_error")) { status.setText("Voice error · " + event.optString("error", "Unable to start speech")); }
        else if (type.equals("stopped")) {
            if (deferredUserMessage != null && deferredUserMessage.equals(input.getText().toString().trim())) {
                try {
                    sendMainMessage(deferredUserMessage);
                    input.setText("");
                    deferredUserMessage = null;
                } catch (RuntimeException error) { status.setText("Unable to resend after shutdown · press Send again"); }
            } else deferredUserMessage = null;
            render();
        }
        else if (type.equals("ready")) render();
    }
    private void render() {
        status.setText(!AgentService.isRunning() ? "Stopped · Send starts the runtime" : state.optBoolean("busy") ? "Main is responding" : state.optBoolean("active") ? "Workers active · Main is available" : "Ready");
        if(AgentService.isRunning() && !state.isNull("lastError") && !state.optString("lastError").isEmpty()) status.setText("Error · "+state.optString("lastError"));
        String rendered=formatMessages(state.optJSONArray("messages"));
        if(!rendered.contentEquals(transcript.getText())) {
            boolean follow=messages.getScrollY()+messages.getHeight()>=transcript.getHeight()-dp(48);
            transcript.setText(rendered);
            if(follow)messages.post(()->{ messages.fullScroll(View.FOCUS_DOWN); updateJumpToBottom(); });
            else messages.post(this::updateJumpToBottom);
        } else messages.post(this::updateJumpToBottom);
        taskList.removeAllViews(); JSONArray tasks = state.optJSONArray("tasks");
        if (tasks == null || tasks.length() == 0) taskList.addView(text("No tasks yet"));
        else for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
            String id = task.optString("conversationId", task.optString("id"));
            taskList.addView(button(task.optString("label", id) + " · " + task.optString("status") + " · #" + task.optInt("generation"), () -> worker(id)));
        }
        if (workerTranscript != null && viewedWorker != null) workerTranscript.setText(workerMessages(viewedWorker));
    }
    private String formatMessages(JSONArray messages) {
        if (messages == null || messages.length() == 0) return "Start a conversation. Multi-step phone work runs in tasks while you keep talking here.";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) { JSONObject message = messages.optJSONObject(i); if (message == null) continue; text.append(message.optString("role")).append(message.has("inputSource") ? " (" + message.optString("inputSource") + ")" : "").append(":\n").append(message.optString("text", message.optString("content"))).append("\n\n"); }
        return text.toString();
    }
    private String workerMessages(String id) {
        JSONObject all = state.optJSONObject("workerMessages"); return formatMessages(all == null ? null : all.optJSONArray(id));
    }
    private void updateJumpToBottom() {
        if (jumpToBottom == null || messages == null || messages.getChildCount() == 0) return;
        View content = messages.getChildAt(0);
        boolean away = content.getHeight() - messages.getScrollY() - messages.getHeight() > dp(48);
        jumpToBottom.setVisibility(away ? View.VISIBLE : View.GONE);
    }
    private void newMainThread() {
        if (activeWork()) { toast("Finish active work before starting a new main thread"); return; }
        new AlertDialog.Builder(this).setTitle("Start a new main thread?")
            .setMessage("The main chat will start fresh. Saved worker tasks and their histories stay available for debugging and follow-up.")
            .setNegativeButton("Cancel", null).setPositiveButton("Start new thread", (dialog, which) -> {
                VoiceConversationInvitation.cancel(this, "thread_changed");
                stopService(new Intent(this, SubscriptionVoiceService.class));
                if (AgentService.isRunning()) command(json("action", "new_main"));
                else { state = store.requestFreshMain(); render(); toast("New main thread ready"); }
            }).show();
    }
    private void searchHistory() {
        LinearLayout panel=column();panel.setPadding(dp(12),dp(8),dp(12),0);
        EditText query=new EditText(this);query.setSingleLine(true);query.setHint("Words from an older chat or worker");
        query.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(300)});panel.addView(query);
        TextView results=text("");results.setTextIsSelectable(true);
        ScrollView scroll=new ScrollView(this);scroll.addView(results);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Saved conversation history").setView(panel).setPositiveButton("Close",null).create();
        java.util.concurrent.atomic.AtomicInteger request=new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<java.util.concurrent.Callable<String>> execute=work->{
            int generation=request.incrementAndGet();results.setText("Reading local history…");
            historyQueries.submit(()->{
                String answer;
                try {answer=work.call();}catch(Exception error){answer="Could not read local history.";}
                String display=answer;
                runOnUiThread(()->{if(!isDestroyed()&&dialog.isShowing()&&request.get()==generation)results.setText(display);});
            });
        };
        panel.addView(button("Search (blank shows latest)",()->{
            String words=query.getText().toString().trim();
            execute.accept(()->{
                try(StateStore history=new StateStore(getApplicationContext())) {
                    JSONArray hits=history.searchHistory(words,40);
                    if(hits.length()==0)return "No matching saved conversations.";
                    StringBuilder display=new StringBuilder();
                    for(int i=0;i<hits.length();i++) {
                        JSONObject hit=hits.optJSONObject(i);if(hit==null)continue;
                        String time=hit.optLong("timestamp")>0?java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(hit.optLong("timestamp"))):"Time unavailable";
                        String body=hit.optString("text");if(body.length()>1500)body=body.substring(0,1500)+"…";
                        display.append("#").append(hit.optLong("id")).append(" · ").append(time).append(" · ").append(hit.optString("owner")).append(" · ").append(hit.optString("role"))
                            .append("\n").append(body).append("\n\n");
                    }
                    return display.toString();
                }
            });
        }));
        EditText selected=new EditText(this);selected.setSingleLine(true);selected.setHint("Message ID to read in full");selected.setInputType(InputType.TYPE_CLASS_NUMBER);panel.addView(selected);
        panel.addView(button("Read full message",()->{
            final long id;
            try {id=Long.parseLong(selected.getText().toString().trim());if(id<1)throw new NumberFormatException();}
            catch(NumberFormatException invalid) {request.incrementAndGet();results.setText("Enter a valid archived message ID.");return;}
            execute.accept(()->{
                try(StateStore history=new StateStore(getApplicationContext())) {
                    JSONObject message=history.readHistory(id,"main");
                    return message.length()==0?"Message not found.":message.optString("text");
                }
            });
        }));
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(320)));
        dialog.setOnDismissListener(ignored->request.incrementAndGet());dialog.show();
    }
    private void copyHistory() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) { toast("Clipboard unavailable"); return; }
        clipboard.setPrimaryClip(ClipData.newPlainText("Pocket Agent debug history", debugHistory(state)));
        toast("Conversation history copied");
    }
    static String debugHistory(JSONObject snapshot) {
        try {
            JSONObject provider = snapshot.optJSONObject("provider");
            JSONObject safeProvider = new JSONObject();
            if (provider != null) {
                safeProvider.put("mode", provider.optString("mode"));
                safeProvider.put("model", provider.optString("model"));
            }
            JSONObject debug = new JSONObject();
            debug.put("provider", safeProvider);
            debug.put("busy", snapshot.optBoolean("busy"));
            debug.put("active", snapshot.optBoolean("active"));
            debug.put("mainPaused", snapshot.optBoolean("mainPaused"));
            debug.put("mainInterrupted", snapshot.optBoolean("mainInterrupted"));
            debug.put("mainTurns", snapshot.optInt("mainTurns"));
            debug.put("lastError", snapshot.opt("lastError"));
            debug.put("personalInstructions", snapshot.optString("personalInstructions"));
            debug.put("mainMessages", snapshot.optJSONArray("messages") == null ? new JSONArray() : snapshot.optJSONArray("messages"));
            debug.put("tasks", snapshot.optJSONArray("tasks") == null ? new JSONArray() : snapshot.optJSONArray("tasks"));
            debug.put("workerMessages", snapshot.optJSONObject("workerMessages") == null ? new JSONObject() : snapshot.optJSONObject("workerMessages"));
            return debug.toString(2);
        } catch (Exception invalidState) { throw new IllegalStateException("Unable to format debug history", invalidState); }
    }
    private void worker(String id) {
        viewedWorker = id; LinearLayout panel = column(); panel.setPadding(dp(16), dp(8), dp(16), 0);
        ScrollView scroll = new ScrollView(this); workerTranscript = text(workerMessages(id)); workerTranscript.setTextIsSelectable(true); scroll.addView(workerTranscript); panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(280)));
        EditText message = new EditText(this); message.setHint("Follow up or steer this worker"); panel.addView(message);
        LinearLayout actions = row();
        actions.addView(button("Message", () -> { if (!message.getText().toString().trim().isEmpty()) { command(json("action", "send", "conversationId", id, "text", message.getText().toString())); message.setText(""); } }));
        actions.addView(button("Steer", () -> { if (!message.getText().toString().trim().isEmpty()) { command(json("action", "steer_worker", "conversationId", id, "text", message.getText().toString())); message.setText(""); } }));
        actions.addView(button("Cancel", () -> command(json("action", "cancel_worker", "conversationId", id)))); panel.addView(actions);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Task " + id).setView(panel).setPositiveButton("Close", null).create();
        dialog.setOnDismissListener(ignored -> { viewedWorker = null; workerTranscript = null; }); dialog.show();
        command(json("action", "inspect_worker", "conversationId", id));
    }
    private void command(JSONObject command) {
        Intent service = new Intent(this, AgentService.class).setAction(AgentService.ACTION_COMMAND).putExtra("command", command.toString());
        startForegroundService(service);
    }
    private boolean activeWork() { return RuntimeOwner.peekHost()!=null && (state.optBoolean("busy") || state.optBoolean("active")); }
    private static boolean isReasoningEffort(String value) {
        for (String candidate : REASONING_EFFORTS) if (candidate.equals(value)) return true;
        return false;
    }
    private static String reasoningLabel(String value) {
        return switch (value) {
            case "minimal" -> "Minimal";
            case "medium" -> "Medium";
            case "high" -> "High";
            case "xhigh" -> "Extra high (xhigh)";
            case "max" -> "Maximum (max)";
            default -> "Low";
        };
    }
    private static String selectedReasoningEffort(Spinner picker) {
        Object tagged = picker.getTag();
        if (tagged instanceof String[]) {
            String[] values = (String[]) tagged;
            int position = picker.getSelectedItemPosition();
            if (position >= 0 && position < values.length && isReasoningEffort(values[position])) return values[position];
        }
        return CredentialStore.DEFAULT_REASONING_EFFORT;
    }
    private static boolean containsReasoningEffort(String[] values, String effort) {
        if (values == null) return false;
        for (String value : values) if (value.equals(effort)) return true;
        return false;
    }
    private static String[] reasoningEfforts(JSONArray values) {
        if (values == null) return new String[0];
        java.util.ArrayList<String> allowed = new java.util.ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            String value = values.optString(i);
            if (isReasoningEffort(value) && !allowed.contains(value)) allowed.add(value);
        }
        return allowed.toArray(new String[0]);
    }
    private static JSONArray reasoningEffortsJson(String[] values) {
        JSONArray result = new JSONArray();
        if (values != null) for (String value : values) if (isReasoningEffort(value)) result.put(value);
        return result;
    }
    static boolean hasPersistedReasoningModelProof(String selectedModel,
            String verifiedModel, String[] verifiedLevels) {
        return selectedModel.equals(verifiedModel) && verifiedLevels != null && verifiedLevels.length > 0;
    }
    static boolean hasPersistedReasoningProof(String selectedModel, String selectedEffort,
            String verifiedModel, String[] verifiedLevels) {
        return hasPersistedReasoningModelProof(selectedModel, verifiedModel, verifiedLevels)
                && containsReasoningEffort(verifiedLevels, selectedEffort);
    }
    static String reasoningPreferenceAfterSelection(String mode, String selectedModel,
            String selectedEffort, String preferred, String[] advertised,
            String verifiedModel, String[] verifiedLevels, boolean explicitManualChoice) {
        if (mode.equals("openai") || mode.equals("codex")) return selectedEffort;
        if (mode.equals(CredentialStore.CODEX_ACCOUNT)
                && (explicitManualChoice || containsReasoningEffort(advertised, selectedEffort)
                || hasPersistedReasoningProof(selectedModel, selectedEffort, verifiedModel, verifiedLevels))) return selectedEffort;
        return preferred;
    }
    static String[] reasoningChoices(boolean manualModel, String[] advertised,
            String modelId, String verifiedModel, String[] verifiedLevels) {
        if (manualModel) return REASONING_EFFORTS.clone();
        if (advertised != null) return advertised.length > 0 ? advertised
                : new String[]{CredentialStore.DEFAULT_REASONING_EFFORT};
        if (hasPersistedReasoningModelProof(modelId, verifiedModel, verifiedLevels)) return verifiedLevels;
        return new String[]{CredentialStore.DEFAULT_REASONING_EFFORT};
    }
    /** Matches Save's blank-key retention and fixed-provider endpoint semantics. */
    static boolean matchesSavedConnection(JSONObject saved, String mode, String endpoint, String enteredKey) {
        String savedMode = saved.optString("authMode");
        boolean sameMode = mode.equals(savedMode)
                || (mode.equals("openai") && savedMode.equals("api-key"))
                || (mode.equals("codex") && (savedMode.equals("codexRelay") || savedMode.equals("codex-relay")));
        if (!sameMode) return false;
        if (mode.equals(CredentialStore.CODEX_ACCOUNT) || mode.equals("scripted")) return true;
        boolean fixed = mode.equals(CredentialStore.OPENCODE) || mode.equals(CredentialStore.NEBIUS);
        String key = enteredKey.trim();
        return (fixed || endpoint.trim().equals(saved.optString("endpoint")))
                && (key.isEmpty() || key.equals(saved.optString("apiKey")));
    }
    private void populateReasoningPicker(Spinner picker, String modelId, String preferred,
            boolean manualModel, String verifiedModel, String[] verifiedLevels) {
        String[] values = reasoningChoices(manualModel, modelReasoningEfforts.get(modelId),
                modelId, verifiedModel, verifiedLevels);
        String[] labels = new String[values.length];
        int selection = -1;
        for (int i = 0; i < values.length; i++) {
            labels[i] = reasoningLabel(values[i]);
            if (values[i].equals(preferred)) selection = i;
        }
        if (selection < 0) {
            for (int i = 0; i < values.length; i++) if (CredentialStore.DEFAULT_REASONING_EFFORT.equals(values[i])) { selection = i; break; }
        }
        if (selection < 0) {
            String advertisedDefault = modelDefaultReasoningEfforts.get(modelId);
            for (int i = 0; i < values.length; i++) if (values[i].equals(advertisedDefault)) { selection = i; break; }
        }
        picker.setTag(values);
        picker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        picker.setSelection(Math.max(0, selection));
        renderedReasoningEffort = values[Math.max(0, selection)];
    }
    private void autonomyActivity() {
        AutonomyScheduler.execute(()->{
            try(AutonomyStore automation=new AutonomyStore(this)) {
                JSONObject saved=automation.load(),status=automation.status();
                runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())showAutonomyActivity(saved,status);});
            } catch(Exception error){
                android.util.Log.w("PocketAutonomy","Could not load background activity: "+error.getClass().getSimpleName());
                runOnUiThread(()->toast("Could not load background activity"));
            }
        });
    }
    private void showAutonomyActivity(JSONObject saved,JSONObject status) {
        LinearLayout panel=column();panel.setPadding(dp(18),dp(8),dp(18),dp(12));
        CheckBox enabled=new CheckBox(this);enabled.setText("Allow scheduled and autonomous work");enabled.setChecked(AutonomyStore.enabled(this));
        enabled.setOnCheckedChangeListener((button,checked)->{
            AutonomyStore.requestEnabled(this,checked,error->runOnUiThread(()->toast("Could not update automation")));
            if(!checked){RuntimeHost shared=RuntimeOwner.peekHost();if(shared!=null)shared.dispatch(json("action","stop_autonomy"));}
        });panel.addView(enabled);
        panel.addView(text(AutonomyStore.enabled(this)?"Automation enabled":"Automation paused — saved schedules will not run"));
        if(!status.optBoolean("exactAlarmPermission")) {
            panel.addView(text("Precise wakeups need Alarms & reminders access. Without it, Android may delay scheduled work."));
            panel.addView(button("Allow precise schedule timing",()->startActivity(new Intent(
                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                android.net.Uri.parse("package:"+getPackageName())))));
        }
        if(status.optBoolean("batteryRestricted")) {
            panel.addView(text("Battery optimization is enabled. For reliable screen-off work, set Pocket Agent battery usage to unrestricted."));
            panel.addView(button("Battery settings",()->startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))));
        }
        panel.addView(text("Runs use the phone's network and provider connection. Stop all pauses future automation. Quiet hours: 10 PM–8 AM on this phone."));
        try {
            String startupIssue=status.optString("lastFailure");if(!startupIssue.isEmpty())panel.addView(text(startupIssue));
            JSONArray decisions=saved.getJSONArray("decisions");
            panel.addView(text("Needs your input"));boolean pending=false;
            for(int i=0;i<decisions.length();i++) {
                JSONObject decision=decisions.getJSONObject(i);if(!"pending".equals(decision.optString("status")))continue;pending=true;
                panel.addView(text(decision.optString("question")));
                if("approval".equals(decision.optString("kind"))) {
                    panel.addView(text("Allow these phone tools for the next run of this task (expires in one hour): "+decision.optJSONArray("tools")));
                    panel.addView(button("Approve next run",()->answerDecision(decision,true,"Approved the listed phone tools for the next run")));
                    panel.addView(button("Decline",()->answerDecision(decision,false,"Declined")));
                } else {
                    EditText answer=new EditText(this);answer.setHint("Your answer");panel.addView(answer);
                    panel.addView(button("Send answer",()->{String value=answer.getText().toString().trim();if(!value.isEmpty())answerDecision(decision,true,value);}));
                }
            }
            if(!pending)panel.addView(text("No pending questions"));
            panel.addView(text("Schedules"));JSONArray schedules=saved.getJSONArray("schedules");
            if(schedules.length()==0)panel.addView(text("Ask the assistant to schedule work or save a follow-up."));
            for(int i=0;i<schedules.length();i++) {
                JSONObject schedule=schedules.getJSONObject(i);String id=schedule.getString("id");boolean active=schedule.optBoolean("enabled");
                panel.addView(text(schedule.optString("title")+" · "+(active?(AutonomyStore.enabled(this)?"Active":"Blocked: automation paused"):"Paused or finished")+"\nNext: "+schedule.optString("nextRunAt","—")));
                panel.addView(button(active?"Pause schedule":"Resume schedule",()->command(Json.obj("action","automation_control","tool","schedule","args",json("action",active?"pause":"resume","id",id)))));
            }
            panel.addView(text("Run history"));JSONArray runs=saved.getJSONArray("runs");
            if(runs.length()==0)panel.addView(text("No background runs yet"));
            for(int i=runs.length()-1;i>=Math.max(0,runs.length()-20);i--) {
                JSONObject run=runs.getJSONObject(i);panel.addView(text(run.optString("status")+" · "+run.optString("source")+"\n"+run.optString("output","Waiting for a result")));
            }
            panel.addView(text("Results"));JSONArray results=saved.getJSONArray("notifications");
            for(int i=results.length()-1;i>=Math.max(0,results.length()-20);i--) {
                JSONObject result=results.getJSONObject(i);panel.addView(text(result.optString("title")+"\n"+result.optString("message")));
            }
        } catch(Exception error){android.util.Log.w("PocketAutonomy","Could not render background activity: "+error.getClass().getSimpleName());panel.addView(text("Could not load background activity"));}
        ScrollView scroll=new ScrollView(this);scroll.addView(panel);
        new AlertDialog.Builder(this).setTitle("Background activity").setView(scroll).setPositiveButton("Close",null).setNeutralButton("Refresh",(dialog,which)->autonomyActivity()).show();
    }
    private void answerDecision(JSONObject decision,boolean approved,String answer) {
        AutonomyScheduler.execute(()->{
            try(AutonomyStore automation=new AutonomyStore(this)) {
                automation.incoming(Json.obj("id","decision:"+decision.getString("id"),"type","decision","decisionId",decision.getString("id"),"approved",approved,"answer",answer));
                runOnUiThread(()->toast("Response saved. The task will continue when background work can run."));
            } catch(Exception error){android.util.Log.w("PocketAutonomy","Could not save decision: "+error.getClass().getSimpleName());runOnUiThread(()->toast("Could not save the response"));}
        });
    }
    private void settings() {
        if(activeWork()){toast("Stop active work before changing settings");return;}
        CredentialStore credentials = new CredentialStore(this); final JSONObject saved;
        try { saved = credentials.get(); } catch (Exception e) { toast("Unable to read protected credentials"); return; }
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(4), dp(20), dp(8));
        panel.addView(text("Personal instructions"));
        EditText mainPrompt = new EditText(this); mainPrompt.setText(state.optString("personalInstructions")); mainPrompt.setHint("Preferences such as tone, priorities, and how to address you");
        mainPrompt.setMinLines(5); mainPrompt.setMaxLines(12); mainPrompt.setGravity(Gravity.TOP | Gravity.START);
        mainPrompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); panel.addView(mainPrompt);
        panel.addView(text("Optional preferences for your assistant. App operating instructions are managed separately."));
        panel.addView(button("Clear personal instructions", () -> {
            mainPrompt.setText(""); mainPrompt.setHint("No personal instructions; tap Save");
        }));
        panel.addView(text("Provider")); Spinner mode = new Spinner(this);
        String[] names = {"Scripted model (local validation)", "API key (HTTPS)", "OpenCode Free (Console key)", "Nebius Token Factory", "Codex emulator relay (development)", "Codex account (ChatGPT)"};
        String[] modes = {"scripted", "openai", CredentialStore.OPENCODE, CredentialStore.NEBIUS, "codex", CredentialStore.CODEX_ACCOUNT};
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        String auth = saved.optString("authMode");
        int selected = auth.equals("scripted") ? 0 : auth.equals("openai") || auth.equals("api-key") ? 1
                : auth.equals(CredentialStore.OPENCODE) ? 2 : auth.equals(CredentialStore.NEBIUS) ? 3
                : auth.equals(CredentialStore.CODEX_ACCOUNT) ? 5 : 4;
        mode.setSelection(selected); panel.addView(mode);
        LinearLayout endpointPanel = column(); panel.addView(endpointPanel);
        String defaultEndpoint = selected == 2 ? CredentialStore.OPENCODE_ENDPOINT : selected == 3 ? CredentialStore.NEBIUS_ENDPOINT : "https://api.openai.com/v1";
        EditText endpoint = field(endpointPanel, "API endpoint", saved.optString("endpoint", defaultEndpoint)); endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        String defaultModel = selected == 2 ? CredentialStore.OPENCODE_MODEL : selected == 3 ? CredentialStore.NEBIUS_MODEL : selected == 5 ? CredentialStore.CODEX_MODEL : "gpt-4.1-mini";
        EditText model = field(panel, "Main chat model ID (editable fallback)", saved.optString("model", defaultModel));
        panel.addView(text("Reasoning effort"));
        Spinner reasoning = new Spinner(this);
        reasoningPicker = reasoning;
        String savedActiveReasoning = saved.optString("reasoningEffort", CredentialStore.DEFAULT_REASONING_EFFORT);
        final String[] preferredReasoning = {saved.optString("preferredReasoningEffort", savedActiveReasoning)};
        final String savedReasoningModel = saved.optString("reasoningEffortModel");
        final String[] savedReasoningLevels = reasoningEfforts(saved.optJSONArray("reasoningEfforts"));
        final String savedModelSource = saved.optString("modelSource",
                savedReasoningModel.isEmpty() ? "manual" : "catalog");
        final String[] modelSource = {savedModelSource};
        final boolean[] savedProofApplies = {true};
        final boolean[] changingModel = {false};
        final boolean[] manualModelEdited = {false};
        final String[] sourceBeforeConnectionEdit = {savedModelSource};
        final String[] modelBeforeConnectionEdit = {model.getText().toString().trim()};
        populateReasoningPicker(reasoning, model.getText().toString(), savedActiveReasoning,
                modelSource[0].equals("manual"), savedReasoningModel, savedReasoningLevels);
        panel.addView(reasoning);
        TextView reasoningHelp = text("");
        panel.addView(reasoningHelp);
        Spinner picker = new Spinner(this);
        modelPicker = picker;
        picker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Manual model ID · or Discover models"}));
        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (picker.isEnabled() && position > 0 && position < modelIds.length && modelIds[position] != null) {
                    String selectedMode = modes[mode.getSelectedItemPosition()];
                    String currentModel = model.getText().toString().trim();
                    preferredReasoning[0] = reasoningPreferenceAfterSelection(selectedMode, currentModel,
                            selectedReasoningEffort(reasoning), preferredReasoning[0],
                            modelReasoningEfforts.get(currentModel), savedReasoningModel, savedReasoningLevels,
                            modelSource[0].equals("manual") && (manualModelEdited[0]
                                    || !selectedReasoningEffort(reasoning).equals(renderedReasoningEffort)));
                    changingModel[0] = true;
                    model.setText(modelIds[position]);
                    changingModel[0] = false;
                    modelSource[0] = "catalog";
                    manualModelEdited[0] = false;
                    String pickerPreference = selectedMode.equals(CredentialStore.CODEX_ACCOUNT)
                            ? selectedReasoningEffort(reasoning) : preferredReasoning[0];
                    populateReasoningPicker(reasoning, modelIds[position], pickerPreference,
                            false, savedProofApplies[0] ? savedReasoningModel : "",
                            savedProofApplies[0] ? savedReasoningLevels : new String[0]);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        panel.addView(picker);
        final EditText[] newKey = new EditText[1];
        final boolean[] testOnly = {false};
        panel.addView(button("Discover models", () -> {
            if (activeWork()) { toast("Finish active work before discovering models"); return; }
            String selectedMode = modes[mode.getSelectedItemPosition()];
            String savedMode = saved.optString("authMode");
            boolean sameMode = selectedMode.equals(savedMode)
                    || (selectedMode.equals("openai") && savedMode.equals("api-key"))
                    || (selectedMode.equals("codex") && (savedMode.equals("codexRelay") || savedMode.equals("codex-relay")));
            if (selectedMode.equals(CredentialStore.CODEX_ACCOUNT) || selectedMode.equals("scripted")) {
                if (!sameMode) { toast("Connect this provider first"); return; }
                modelRequestId = Long.toHexString(System.nanoTime());
                command(json("action", "list_models", "requestId", modelRequestId));
                return;
            }
            String enteredKey = newKey[0] == null ? "" : newKey[0].getText().toString().trim();
            String apiKey = enteredKey.isEmpty() && sameMode ? saved.optString("apiKey") : enteredKey;
            if (apiKey.isEmpty()) { toast("Enter an API key before discovering models"); return; }
            JSONObject draft = json("authMode", selectedMode, "endpoint", endpoint.getText().toString().trim(),
                    "model", model.getText().toString().trim(), "apiKey", apiKey);
            modelRequestId = Long.toHexString(System.nanoTime());
            command(Json.obj("action", "list_models", "requestId", modelRequestId, "credentials", draft));
        }));
        LinearLayout secretPanel = column(); panel.addView(secretPanel);
        EditText secret = field(secretPanel, "API key / relay token (blank keeps saved value)", ""); secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        newKey[0] = secret;
        secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); secret.setSaveEnabled(false);
        LinearLayout accountPanel = column(); panel.addView(accountPanel);
        String accountStatus = "No account connected";
        if (auth.equals(CredentialStore.CODEX_ACCOUNT) && !saved.optString("accountId").isEmpty()) {
            accountStatus = saved.optLong("expiresAt") <= System.currentTimeMillis() ? "Account connected · access token needs refresh" : "Account connected · access token valid";
        }
        accountPanel.addView(text(accountStatus));
        accountPanel.addView(text("Sign in using a device code, or select your Codex auth.json. Account tokens stay encrypted on this phone. Imported refresh tokens may share rotation with the original Codex installation."));
        final AlertDialog[] settingsDialog = new AlertDialog[1];
        TextView connectionStatus = text("Save tests the connection only when the API key changes. Test connection checks discovery and a backend reply on demand and may incur provider usage.");
        panel.addView(connectionStatus);
        Button testConnection = button("Test connection", () -> {
            if (settingsDialog[0] == null) return;
            testOnly[0] = true;
            settingsDialog[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            testOnly[0] = false;
        });
        panel.addView(testConnection);
        accountPanel.addView(button("Sign in", () -> {
            if (activeWork()) { toast("Stop active work before changing accounts"); return; }
            settingsDialog[0].dismiss(); signInAccount(false);
        }));
        accountPanel.addView(button("Import Codex auth.json", () -> {
            if (activeWork()) { toast("Stop active work before changing accounts"); return; }
            settingsDialog[0].dismiss();
            long generation = invalidateAccountOperations();
            pendingPickerGeneration = generation;
            pendingPickerRequest = IMPORT_CODEX + (int) (generation % 60000);
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
            try { startActivityForResult(pick, pendingPickerRequest); } catch (Exception unavailable) { invalidateAccountOperations(); toast("No document picker is available"); }
        }));
        accountPanel.addView(button("Sign out", () -> {
            if (activeWork()) { toast("Stop active work before signing out"); return; }
            try {
                boolean sharedVoice = credentials.signOutCodex();
                if (sharedVoice) {
                    stopService(new Intent(this, SubscriptionVoiceService.class));
                    VoiceConversationInvitation.cancel(this, "account_signed_out");
                }
                invalidateAccountOperations();
                if (AgentService.isRunning()) command(json("action", "configure"));
                settingsDialog[0].dismiss();
                toast(sharedVoice ? "Chat and shared voice account signed out" : "Chat account signed out");
            }
            catch (Exception error) { toast("Unable to clear the protected account"); }
        }));
        TextView connectionHelp = text("OpenCode Free uses OpenCode's fixed external inference API and requires a Console service-account key. Pocket Agent sends it only as Authorization: Bearer to opencode.ai; no OpenCode client identity headers are spoofed. Nebius Token Factory uses its fixed API endpoint and requires a Nebius key; its default GLM-5.3-Flash supports vision and tool use. Codex relay uses http://10.0.2.2:port/v1 on an emulator. Codex account mode needs no API key or endpoint."); panel.addView(connectionHelp);
        java.util.function.IntConsumer renderProviderFields = position -> {
            String selectedMode = modes[position];
            boolean account = selectedMode.equals(CredentialStore.CODEX_ACCOUNT);
            boolean openCode = selectedMode.equals(CredentialStore.OPENCODE);
            boolean nebius = selectedMode.equals(CredentialStore.NEBIUS);
            boolean fixedProvider = openCode || nebius;
            boolean reasoningProvider = account || selectedMode.equals("openai") || selectedMode.equals("codex");
            reasoning.setEnabled(reasoningProvider);
            reasoning.setAlpha(reasoningProvider ? 1.0f : 0.45f);
            reasoningHelp.setText(reasoningProvider
                    ? account
                            ? "Defaults to low. Edit the model ID for manual effort selection, or choose a discovered model to use its catalog reasoning levels."
                            : "Defaults to low. Manual model IDs allow manual effort selection; discovered models use their catalog reasoning levels."
                    : "This provider does not expose the OpenAI/Codex reasoning-effort control. The saved value is kept for compatible providers.");
            boolean sameProvider = selectedMode.equals(auth)
                    || (selectedMode.equals("openai") && auth.equals("api-key"))
                    || (selectedMode.equals("codex") && (auth.equals("codexRelay") || auth.equals("codex-relay")));
            endpointPanel.setVisibility(account || fixedProvider ? View.GONE : View.VISIBLE);
            secretPanel.setVisibility(account ? View.GONE : View.VISIBLE);
            accountPanel.setVisibility(account ? View.VISIBLE : View.GONE);
            secret.setHint(openCode ? "OpenCode Console service-account key (required)" : nebius ? "Nebius API key (required)" : "API key / relay token (blank keeps saved value)");
            changingModel[0] = true;
            modelSource[0] = sameProvider ? savedModelSource : "manual";
            manualModelEdited[0] = false;
            if (sameProvider) {
                if (!account) endpoint.setText(saved.optString("endpoint", endpoint.getText().toString()));
                model.setText(saved.optString("model", model.getText().toString()));
            } else if (account) model.setText(CredentialStore.CODEX_MODEL);
            else if (openCode) { endpoint.setText(CredentialStore.OPENCODE_ENDPOINT); model.setText(CredentialStore.OPENCODE_MODEL); }
            else if (nebius) { endpoint.setText(CredentialStore.NEBIUS_ENDPOINT); model.setText(CredentialStore.NEBIUS_MODEL); }
            else if (selectedMode.equals("codex")) { endpoint.setText("http://10.0.2.2:8787/v1"); model.setText(CredentialStore.CODEX_MODEL); }
            else if (selectedMode.equals("openai")) { endpoint.setText("https://api.openai.com/v1"); model.setText("gpt-4.1-mini"); }
            else { endpoint.setText("https://api.openai.com/v1"); model.setText("scripted"); }
            changingModel[0] = false;
            boolean sameConnection = matchesSavedConnection(saved, selectedMode,
                    endpoint.getText().toString(), secret.getText().toString());
            savedProofApplies[0] = sameConnection;
            if (!sameConnection) modelSource[0] = "manual";
            sourceBeforeConnectionEdit[0] = modelSource[0];
            modelBeforeConnectionEdit[0] = model.getText().toString().trim();
            picker.setEnabled(sameConnection);
            String pickerPreference = sameProvider ? savedActiveReasoning : preferredReasoning[0];
            populateReasoningPicker(reasoning, model.getText().toString(), pickerPreference,
                    modelSource[0].equals("manual"), sameConnection ? savedReasoningModel : "",
                    sameConnection ? savedReasoningLevels : new String[0]);
        };
        final int[] lastProvider = {selected};
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != lastProvider[0]) {
                    String previousMode = modes[lastProvider[0]];
                    String currentModel = model.getText().toString().trim();
                    preferredReasoning[0] = reasoningPreferenceAfterSelection(previousMode, currentModel,
                            selectedReasoningEffort(reasoning), preferredReasoning[0],
                            modelReasoningEfforts.get(currentModel), savedReasoningModel, savedReasoningLevels,
                            modelSource[0].equals("manual") && (manualModelEdited[0]
                                    || !selectedReasoningEffort(reasoning).equals(renderedReasoningEffort)));
                    changingModel[0] = true;
                    secret.setText("");
                    changingModel[0] = false;
                    modelRequestId = null;
                    modelIds = new String[0];
                    modelReasoningEfforts.clear();
                    modelDefaultReasoningEfforts.clear();
                    picker.setAdapter(new ArrayAdapter<>(MainActivity.this, android.R.layout.simple_spinner_dropdown_item,
                            new String[]{"Manual model ID · or Discover models"}));
                    picker.setSelection(0);
                }
                lastProvider[0] = position;
                renderProviderFields.accept(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        renderProviderFields.accept(selected);
        model.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) {
                if (changingModel[0]) return;
                String effort = selectedReasoningEffort(reasoning);
                modelSource[0] = "manual";
                manualModelEdited[0] = true;
                picker.setSelection(0);
                populateReasoningPicker(reasoning, value.toString().trim(), effort,
                        true, "", new String[0]);
            }
        });
        TextWatcher connectionChanges = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) {
                if (changingModel[0]) return;
                modelRequestId = null;
                picker.setEnabled(false);
                String effort = selectedReasoningEffort(reasoning);
                String currentModel = model.getText().toString().trim();
                boolean sameConnection = matchesSavedConnection(saved, modes[mode.getSelectedItemPosition()],
                        endpoint.getText().toString(), secret.getText().toString());
                if (sameConnection == savedProofApplies[0]) {
                    if (sameConnection) picker.setEnabled(true);
                    return;
                }
                if (!sameConnection) {
                    sourceBeforeConnectionEdit[0] = modelSource[0];
                    modelBeforeConnectionEdit[0] = currentModel;
                    modelSource[0] = "manual";
                    modelRequestId = null;
                } else if (!manualModelEdited[0] && currentModel.equals(modelBeforeConnectionEdit[0])) {
                    modelSource[0] = sourceBeforeConnectionEdit[0];
                }
                // Keep the original connection's catalog cached, but unavailable while edits
                // change its identity. Reverting the endpoint/key restores that catalog.
                savedProofApplies[0] = sameConnection;
                picker.setEnabled(sameConnection);
                populateReasoningPicker(reasoning, currentModel, effort, modelSource[0].equals("manual"),
                        sameConnection ? savedReasoningModel : "", sameConnection ? savedReasoningLevels : new String[0]);
            }
        };
        endpoint.addTextChangedListener(connectionChanges);
        secret.addTextChangedListener(connectionChanges);
        if (BuildConfig.DEVELOPMENT_TOOLS) panel.addView(button("Development MCP and ngrok", () ->
            startActivity(new Intent().setClassName(this, "dev.pocket.agent.DevToolsActivity"))));
        panel.addView(button("Accessibility settings", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        panel.addView(button("Enable Shizuku virtual displays", this::enableVirtualDisplays));
        panel.addView(button("Notification access settings", () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        panel.addView(button("Choose phone permissions", this::permissions));
        panel.addView(button("Remote MCP servers", this::mcpSettings));
        panel.addView(text("Main-thread voice conversation"));
        panel.addView(button("Response preference", this::responsePreference));
        panel.addView(text("Choose messages or allow silent voice invitations. Accept connects this chat; Decline keeps text. Voice starts only after acceptance. You can always start it manually."));
        panel.addView(text("Use Start Codex voice in the main chat to talk to that thread through the phone speaker. Spoken and typed requests share its history. Listening stays open through silence; Disconnect voice in the app or notification ends the call while background tasks continue. Stop response cancels the current response, and Stop all ends the runtime."));
        try {
            JSONObject voiceAccount = credentials.getVoiceCodex();
            panel.addView(text(voiceAccount.optString("accountId").isEmpty()
                ? "ChatGPT voice account: not connected"
                : credentials.voiceSharesChatAccount()
                    ? "ChatGPT voice account: shared with chat"
                    : "ChatGPT voice account: connected separately from chat"));
        } catch (Exception unreadable) {
            panel.addView(text("ChatGPT voice account: protected account unavailable"));
        }
        panel.addView(button("Sign in to ChatGPT for voice only", () -> {
            if (activeWork()) { toast("Stop active work before changing voice login"); return; }
            settingsDialog[0].dismiss(); signInAccount(true);
        }));
        panel.addView(button("Disconnect ChatGPT voice account", () -> {
            if (activeWork()) { toast("Stop active work before changing voice login"); return; }
            try {
                if (credentials.voiceSharesChatAccount()) {
                    new AlertDialog.Builder(this).setTitle("Disconnect shared ChatGPT account?")
                        .setMessage("Voice and chat share this login. Disconnecting it signs out both; your saved conversations stay on this phone.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Disconnect both", (dialog, which) -> disconnectVoiceAccount(settingsDialog[0])).show();
                } else disconnectVoiceAccount(settingsDialog[0]);
            } catch (Exception error) { toast("Unable to remove the protected voice login"); }
        }));
        String lastVoiceFailure = SubscriptionVoiceFailureStore.lastStatus(this);
        if (!lastVoiceFailure.isEmpty()) panel.addView(text(lastVoiceFailure));
        panel.addView(text("Voice conversation uses a protected ChatGPT voice login. The assistant button and Hey Jarvis use the existing voice system and Deepgram key."));
        panel.addView(text("Speech-to-text mode"));
        Spinner sttMode = new Spinner(this);
        String[] sttModeNames = {
            "Hybrid · threshold switching",
            "Android default only",
            "Deepgram only"
        };
        sttMode.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, sttModeNames));
        VoiceSttSettings.Mode savedSttMode = VoiceSttSettings.mode(this);
        sttMode.setSelection(savedSttMode == VoiceSttSettings.Mode.ANDROID_DEFAULT_ONLY ? 1
            : savedSttMode == VoiceSttSettings.Mode.DEEPGRAM_ONLY ? 2 : 0);
        panel.addView(sttMode);

        TextView sttModeHelp = text("");
        panel.addView(sttModeHelp);
        TextView shortThresholdLabel = text("");
        shortThresholdLabel.setTextSize(16);
        panel.addView(shortThresholdLabel);
        SeekBar shortThreshold = new SeekBar(this);
        shortThreshold.setMax(VoiceSttSettings.maxProgress());
        shortThreshold.setProgress(VoiceSttSettings.progressForMs(
            VoiceSttSettings.shortUtteranceMs(this)));
        panel.addView(shortThreshold);
        Button thresholdReset = button("Reset switch threshold to 3.00 s", () -> {
            VoiceSttSettings.setShortUtteranceMs(this,
                VoiceSttSettings.DEFAULT_SHORT_UTTERANCE_MS);
            shortThreshold.setProgress(VoiceSttSettings.progressForMs(
                VoiceSttSettings.DEFAULT_SHORT_UTTERANCE_MS));
            toast("Switch threshold reset to 3.00 s");
        });
        panel.addView(thresholdReset);

        java.util.function.IntConsumer updateShortThreshold = progress -> {
            int ms = VoiceSttSettings.msForProgress(progress);
            if (VoiceSttSettings.mode(MainActivity.this)
                    == VoiceSttSettings.Mode.HYBRID_THRESHOLD) {
                shortThresholdLabel.setText("Switch to Deepgram at: " +
                    String.format(java.util.Locale.US, "%.2f", ms / 1000f) + " s");
            } else {
                shortThresholdLabel.setText("Switch threshold disabled in single-provider mode");
            }
        };
        Runnable updateSttModeControls = () -> {
            VoiceSttSettings.Mode selectedMode = VoiceSttSettings.mode(MainActivity.this);
            boolean hybrid = selectedMode == VoiceSttSettings.Mode.HYBRID_THRESHOLD;
            shortThreshold.setEnabled(hybrid);
            thresholdReset.setEnabled(hybrid);
            shortThreshold.setAlpha(hybrid ? 1.0f : 0.45f);
            thresholdReset.setAlpha(hybrid ? 1.0f : 0.45f);
            updateShortThreshold.accept(shortThreshold.getProgress());
            if (selectedMode == VoiceSttSettings.Mode.ANDROID_DEFAULT_ONLY) {
                sttModeHelp.setText("Android default only uses the phone's normal system SpeechRecognizer for normal turns, matching the standalone test path. Hybrid arbitration stays disabled. While Pocket Agent is thinking or speaking, Deepgram Flux temporarily provides full-duplex barge-in so you can interrupt without waiting for playback to finish; an interrupted utterance is completed on that same stream before Android recognition resumes.");
            } else if (selectedMode == VoiceSttSettings.Mode.DEEPGRAM_ONLY) {
                sttModeHelp.setText("Deepgram only uses the existing streaming Deepgram STT path. Android recognition, parallel arbitration, and the switch threshold are disabled.");
            } else {
                sttModeHelp.setText("Hybrid runs Android and Deepgram in parallel. Android wins short turns below the selected threshold when available; Deepgram wins long turns. This is the only mode that uses threshold switching.");
            }
        };
        updateSttModeControls.run();

        sttMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                    int position, long id) {
                VoiceSttSettings.Mode selected = position == 1
                    ? VoiceSttSettings.Mode.ANDROID_DEFAULT_ONLY
                    : position == 2 ? VoiceSttSettings.Mode.DEEPGRAM_ONLY
                    : VoiceSttSettings.Mode.HYBRID_THRESHOLD;
                VoiceSttSettings.setMode(MainActivity.this, selected);
                updateSttModeControls.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        shortThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                updateShortThreshold.accept(progress);
                if (fromUser && VoiceSttSettings.usesThreshold(MainActivity.this)) {
                    VoiceSttSettings.setShortUtteranceMs(MainActivity.this,
                        VoiceSttSettings.msForProgress(progress));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (!VoiceSttSettings.usesThreshold(MainActivity.this)) return;
                int ms = VoiceSttSettings.msForProgress(bar.getProgress());
                toast("Switch threshold saved: " +
                    String.format(java.util.Locale.US, "%.2f", ms / 1000f) + " s");
            }
        });
        panel.addView(text("For better proper-name recognition, Hybrid mode requests Android quality formatting and device-context/contact hints. Android default only deliberately uses the normal system recognizer microphone path instead of Pocket Agent PCM injection. STT mode changes apply to the next voice session."));
        panel.addView(button("Deepgram voice key", this::voiceKeySettings));
        panel.addView(text("Hey Jarvis runs locally at 16 kHz. The wake-word sensitivity below is saved across restarts and applies immediately to an active listener."));
        TextView wakeConfidenceLabel = text("");
        wakeConfidenceLabel.setTextSize(16);
        panel.addView(wakeConfidenceLabel);
        SeekBar wakeConfidence = new SeekBar(this);
        wakeConfidence.setMax(WakeWordSettings.MAX_PERCENT - WakeWordSettings.MIN_PERCENT);
        wakeConfidence.setProgress(WakeWordSettings.thresholdPercent(this) - WakeWordSettings.MIN_PERCENT);
        panel.addView(wakeConfidence);
        TextView thresholdExplanation = text("");
        panel.addView(thresholdExplanation);
        java.util.function.IntConsumer updateWakeLabel = percent -> {
            wakeConfidenceLabel.setText("Hey Jarvis confidence threshold: " + percent + "%");
            thresholdExplanation.setText(percent < 30
                ? "Very sensitive · experimental: unrelated speech may activate Pocket Agent"
                : percent < 65 ? "More sensitive · more wake-ups, but greater false-trigger risk"
                : percent > 85 ? "Strict · fewer false triggers, but may miss your voice"
                : "Balanced · higher requires stronger wake-word confidence");
        };
        updateWakeLabel.accept(WakeWordSettings.thresholdPercent(this));
        wakeConfidence.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                int percent = value + WakeWordSettings.MIN_PERCENT;
                updateWakeLabel.accept(percent);
                if (fromUser) WakeWordSettings.setThresholdPercent(MainActivity.this, percent);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                toast("Hey Jarvis threshold saved: " +
                    (bar.getProgress() + WakeWordSettings.MIN_PERCENT) + "%");
            }
        });
        panel.addView(button("Reset wake threshold to 75%", () -> {
            WakeWordSettings.setThresholdPercent(this, WakeWordSettings.DEFAULT_PERCENT);
            wakeConfidence.setProgress(WakeWordSettings.DEFAULT_PERCENT - WakeWordSettings.MIN_PERCENT);
            toast("Hey Jarvis threshold reset to 75%");
        }));
        CheckBox standardMic = new CheckBox(this);
        standardMic.setText("Use standard microphone for Hey Jarvis (troubleshooting)");
        standardMic.setChecked(WakeWordSettings.standardMicrophone(this));
        standardMic.setOnCheckedChangeListener((button, checked) -> {
            WakeWordSettings.setStandardMicrophone(this, checked);
            toast("Microphone source saved. Disable and re-enable Hey Jarvis to apply.");
        });
        panel.addView(standardMic);
        panel.addView(text("If enrollment repeatedly shows 1–3% confidence, try the standard microphone and compare the signal level shown during enrollment. This helps distinguish poor input audio from a wake model that does not match your speech. Lowering the threshold does not retrain the model."));
        panel.addView(text("Optional calibration tunes the phrase detector; it does NOT authenticate your identity. The selected speech-to-text engine and the agent start only after detection."));
        panel.addView(button("Enable Hey Jarvis always-listening", this::enableWakeWord));
        panel.addView(button("Disable Hey Jarvis", () -> {
            startService(new Intent(this, WakeWordService.class).setAction(WakeWordService.ACTION_DISABLE));
            toast("Hey Jarvis disabled");
        }));
        panel.addView(button("Calibrate my Hey Jarvis phrase", () ->
            startActivity(new Intent(this, JarvisEnrollmentActivity.class))));
        panel.addView(button("Allow microphone", () -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 103)));
        panel.addView(button("Allow voice notification", () -> {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 105);
            else toast("Notifications already permitted");
        }));
        panel.addView(button("Choose default assistant", this::chooseAssistant));
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Connection & permissions").setView(scroll).setNegativeButton("Close", null).setPositiveButton("Save", null).create(); settingsDialog[0] = dialog;
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    if (activeWork()) { toast("Stop active work before changing connections"); return; }
                    String selectedMode = modes[mode.getSelectedItemPosition()];
                    String selectedModel = model.getText().toString().trim();
                    String selectedEffort = selectedReasoningEffort(reasoning);
                    String[] advertised = modelReasoningEfforts.get(selectedModel);
                    boolean persistedModelProof = savedProofApplies[0] && hasPersistedReasoningModelProof(selectedModel,
                            savedReasoningModel, savedReasoningLevels);
                    String[] verifiedLevels = modelSource[0].equals("manual") ? null
                            : advertised != null ? advertised : persistedModelProof ? savedReasoningLevels : null;
                    preferredReasoning[0] = reasoningPreferenceAfterSelection(selectedMode, selectedModel,
                            selectedEffort, preferredReasoning[0], advertised,
                            savedReasoningModel, savedReasoningLevels,
                            modelSource[0].equals("manual") && (manualModelEdited[0]
                                    || !selectedEffort.equals(renderedReasoningEffort)));
                    JSONObject config = json("authMode", selectedMode, "model", selectedModel, "modelSource", modelSource[0], "reasoningEffort", selectedEffort,
                            "preferredReasoningEffort", preferredReasoning[0]);
                    config.put("reasoningEffortModel", modelSource[0].equals("catalog") ? selectedModel : "");
                    config.put("reasoningEfforts", reasoningEffortsJson(verifiedLevels));
                    if (!selectedMode.equals(CredentialStore.CODEX_ACCOUNT)) {
                        config.put("endpoint", endpoint.getText().toString().trim());
                        String savedMode = saved.optString("authMode");
                        boolean sameProvider = selectedMode.equals(savedMode)
                                || (selectedMode.equals("openai") && savedMode.equals("api-key"))
                                || (selectedMode.equals("codex") && (savedMode.equals("codexRelay") || savedMode.equals("codex-relay")));
                        // Never carry a credential from a different provider when switching modes.
                        String entered = secret.getText().toString().trim();
                        config.put("apiKey", entered.isEmpty() && sameProvider ? saved.optString("apiKey") : entered);
                    }
                    String prompt = mainPrompt.getText().toString().trim();
                    if (prompt.length() > 50000) { toast("Personal instructions must be 50000 characters or fewer"); return; }
                    Runnable saveConnection = () -> {
                      try {
                        if (activeWork()) { connectionStatus.setText("Stop active work and save again."); return; }
                        credentials.save(config); invalidateAccountOperations();
                        // Serialize through the owner so late checkpoints cannot overwrite preferences.
                        JSONObject configure=json("action","configure","personalInstructions",prompt);
                        command(configure);
                        dialog.dismiss(); toast("Settings saved");
                      } catch (Exception error) { connectionStatus.setText(error instanceof IllegalArgumentException ? error.getMessage() : "Could not save connection"); }
                    };
                    if (selectedMode.equals("scripted") || selectedMode.equals(CredentialStore.CODEX_ACCOUNT)) {
                        if (testOnly[0]) { connectionStatus.setText("Connection testing is available for API-key providers. Use account sign-in for Codex."); return; }
                        saveConnection.run(); return;
                    }
                    // Compare the effective key after blank-field retention, not model/preferences.
                    // Re-entering the saved key also must not trigger an automatic request.
                    if (!testOnly[0] && config.optString("apiKey").equals(saved.optString("apiKey"))) {
                        saveConnection.run(); return;
                    }
                    if (connectionTestId != null) { toast("Connection test is already running"); return; }
                    boolean saveAfterTest = !testOnly[0];
                    String testedEndpoint = endpoint.getText().toString();
                    String testedKey = secret.getText().toString();
                    connectionStatus.setText("Testing key, model catalog, and backend reply…");
                    testConnection.setEnabled(false);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    connectionTestId = Long.toHexString(System.nanoTime());
                    connectionTestResult = event -> {
                        testConnection.setEnabled(true);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        String failure = event.optString("error");
                        if (!failure.isEmpty()) { connectionStatus.setText(failure + " Settings were not saved."); return; }
                        if (!selectedMode.equals(modes[mode.getSelectedItemPosition()])
                                || !selectedModel.equals(model.getText().toString().trim())
                                || !testedEndpoint.equals(endpoint.getText().toString())
                                || !testedKey.equals(secret.getText().toString())
                                || !selectedEffort.equals(selectedReasoningEffort(reasoning))
                                || !prompt.equals(mainPrompt.getText().toString().trim())) {
                            connectionStatus.setText("Connection responded, but settings changed during the test. Test or save again."); return;
                        }
                        connectionStatus.setText("Connection verified: key accepted, models discovered, and backend replied.");
                        if (saveAfterTest) saveConnection.run();
                    };
                    String pendingTest = connectionTestId;
                    connectionStatus.postDelayed(() -> {
                        if (!pendingTest.equals(connectionTestId)) return;
                        connectionTestId = null; connectionTestResult = null;
                        testConnection.setEnabled(true);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        connectionStatus.setText("Connection test timed out. Settings were not saved. Check network access and retry.");
                    }, 85000);
                    command(Json.obj("action", "validate_connection", "requestId", connectionTestId, "credentials", config));
                } catch (Exception error) {
                    connectionTestId = null; connectionTestResult = null;
                    testConnection.setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    connectionStatus.setText(error instanceof IllegalArgumentException ? error.getMessage() : "Could not test or save connection");
                }
            });
        });
        dialog.setOnDismissListener(ignored -> {
            if (modelPicker == picker) {
                connectionTestId = null; connectionTestResult = null;
                modelPicker = null; reasoningPicker = null; modelRequestId = null; modelIds = new String[0];
                modelReasoningEfforts.clear();
                modelDefaultReasoningEfforts.clear();
            }
        });
        dialog.show();
    }
    private void mcpSettings() {
        if (activeWork()) { toast("Stop active work before changing MCP servers"); return; }
        CredentialStore credentials=new CredentialStore(this);
        try {
            JSONArray saved=credentials.mcpServers();
            String[] labels=new String[saved.length()+1];labels[0]="Add remote MCP server";
            for(int i=0;i<saved.length();i++){
                JSONObject server=saved.optJSONObject(i);
                labels[i+1]=(server==null?"MCP server":server.optString("name"))+" · "+(server==null?"":server.optString("url"));
            }
            new AlertDialog.Builder(this).setTitle("Remote MCP servers").setItems(labels,(dialog,which)->{
                if(which==0){editMcpServer(credentials,null);return;}
                JSONObject server=saved.optJSONObject(which-1);if(server==null)return;
                String[] actions={"Edit", "Test and discover tools", "Delete"};
                new AlertDialog.Builder(this).setTitle(server.optString("name")).setItems(actions,(actionDialog,action)->{
                    if(action==0)editMcpServer(credentials,server);
                    else if(action==1){mcpRequestId=Long.toHexString(System.nanoTime());command(json("action","mcp_discover","requestId",mcpRequestId,"serverId",server.optString("id")));toast("Connecting to MCP server…");}
                    else try {if(activeWork()){toast("Stop active work before changing MCP servers");return;}credentials.deleteMcpServer(server.optString("id"));if(AgentService.isRunning())command(json("action","mcp_reload"));toast("MCP server removed");}
                    catch(Exception error){toast("Could not remove MCP server");}
                }).show();
            }).setNegativeButton("Close",null).show();
        } catch(Exception error){toast("Could not read protected MCP settings");}
    }
    private void editMcpServer(CredentialStore credentials,JSONObject existing) {
        LinearLayout panel=column();panel.setPadding(dp(18),dp(8),dp(18),0);
        EditText name=field(panel,"Server name",existing==null?"":existing.optString("name"));
        EditText url=field(panel,"MCP URL (HTTPS)",existing==null?"":existing.optString("url"));url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        android.widget.CheckBox enabled=new android.widget.CheckBox(this);enabled.setText("Enabled");enabled.setChecked(existing==null||existing.optBoolean("enabled",true));panel.addView(enabled);
        Spinner auth=new Spinner(this);auth.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"None","Bearer token","Custom headers"}));
        String savedMode=existing==null?"none":existing.optString("authMode",existing.has("headerName")?"headers":"none");auth.setSelection(savedMode.equals("bearer")?1:savedMode.equals("headers")?2:0);panel.addView(text("Authentication"));panel.addView(auth);
        EditText bearer=new EditText(this);bearer.setSingleLine(true);bearer.setHint(existing!=null&&!existing.optString("bearerToken").isEmpty()?"Saved encrypted token · blank keeps it":"Server-issued token");bearer.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);bearer.setSaveEnabled(false);bearer.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);panel.addView(bearer);
        LinearLayout headers=column();panel.addView(headers);java.util.ArrayList<EditText[]> headerRows=new java.util.ArrayList<>();JSONArray oldHeaders=existing==null?new JSONArray():existing.optJSONArray("headers");
        if(oldHeaders==null){oldHeaders=new JSONArray();if(existing!=null&&existing.has("headerName"))oldHeaders.put(json("name",existing.optString("headerName"),"value",existing.optString("headerValue")));}
        final JSONArray priorHeaders=oldHeaders;
        Runnable addHeader=()->{if(headerRows.size()>=8){toast("Up to 8 custom headers");return;}LinearLayout row=column();EditText key=field(row,"Header name","");EditText secret=field(row,"Header value · blank keeps saved value","");secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);secret.setSaveEnabled(false);secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);headerRows.add(new EditText[]{key,secret});headers.addView(row);};
        if(savedMode.equals("headers"))for(int i=0;i<priorHeaders.length();i++){JSONObject h=priorHeaders.optJSONObject(i);addHeader.run();if(h!=null){EditText[] row=headerRows.get(headerRows.size()-1);row[0].setText(h.optString("name"));row[1].setHint("Saved encrypted value · blank keeps it");}}
        panel.addView(button("Add custom header",addHeader));
        Runnable updateAuth=()->{int mode=auth.getSelectedItemPosition();bearer.setVisibility(mode==1?View.VISIBLE:View.GONE);headers.setVisibility(mode==2?View.VISIBLE:View.GONE);};auth.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){updateAuth.run();}public void onNothingSelected(android.widget.AdapterView<?> p){}});updateAuth.run();
        panel.addView(text("Leave a saved secret blank to keep it encrypted. Custom headers support up to 8 pairs."));
        panel.addView(text("Remote tools can receive conversation details sent by the model. Connect only servers you trust."));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"Add MCP server":"Edit MCP server").setView(panel).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
            try {
                if(activeWork())throw new IllegalStateException("Stop active work before changing MCP servers");
                int authIndex=auth.getSelectedItemPosition();String authMode=authIndex==1?"bearer":authIndex==2?"headers":"none";
                JSONObject config=new JSONObject().put("name",name.getText().toString()).put("url",url.getText().toString()).put("enabled",enabled.isChecked()).put("authMode",authMode);
                if(existing!=null)config.put("id",existing.optString("id"));
                if(authMode.equals("bearer")){
                    String token=bearer.getText().toString();if(token.isEmpty()&&existing!=null&&savedMode.equals("bearer"))token=existing.optString("bearerToken");
                    if(!token.isEmpty())config.put("bearerToken",token);
                }else if(authMode.equals("headers")){
                    JSONArray values=new JSONArray();
                    for(EditText[] row:headerRows){String headerName=row[0].getText().toString().trim(),headerValue=row[1].getText().toString();if(headerName.isEmpty())continue;
                        if(headerValue.isEmpty())for(int i=0;i<priorHeaders.length();i++){JSONObject old=priorHeaders.optJSONObject(i);if(old!=null&&old.optString("name").equalsIgnoreCase(headerName)){headerValue=old.optString("value");break;}}
                        values.put(json("name",headerName,"value",headerValue));}
                    config.put("headers",values);
                }
                credentials.saveMcpServer(config);if(AgentService.isRunning())command(json("action","mcp_reload"));
                dialog.dismiss();toast("MCP server saved encrypted");
            } catch(Exception error){toast(error instanceof IllegalArgumentException||error instanceof IllegalStateException?error.getMessage():"Could not save MCP server");}
        }));dialog.show();
    }
    private void voiceKeySettings() {
        EditText key = new EditText(this);
        String hint;
        try {
            hint = new VoiceCredentialStore(this).configured()
                ? "Configured · enter new key to replace" : "Deepgram API key";
        } catch (RuntimeException unreadable) {
            hint = "Saved voice key unavailable · enter a new key";
        }
        key.setHint(hint);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        key.setSaveEnabled(false);
        new AlertDialog.Builder(this).setTitle("Deepgram voice connection").setView(key)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", (dialog, which) -> {
                try { new VoiceCredentialStore(this).save(key.getText().toString()); toast("Voice key saved securely"); }
                catch (Exception failure) { toast("Could not save Deepgram key"); }
                key.setText("");
            }).show();
    }
    private void chooseAssistant() {
        try {
            RoleManager roles = getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 104);
            } else {
                startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
            }
        } catch (Exception unavailable) { toast("Open Settings → Default apps → Digital assistant app"); }
    }
    private void saveAccount(JSONObject account) throws Exception {
        if (activeWork()) throw new IllegalStateException("Stop active work before changing accounts");
        JSONObject config = new JSONObject(account.toString()).put("authMode", CredentialStore.CODEX_ACCOUNT).put("model", CredentialStore.CODEX_MODEL);
        new CredentialStore(this).save(config);
        if (AgentService.isRunning()) command(json("action", "configure"));
    }
    private void saveVoiceAccount(JSONObject account) throws Exception {
        if (activeWork()) throw new IllegalStateException("Stop active work before changing accounts");
        new CredentialStore(this).saveVoiceCodex(account);
    }
    /** A newer account choice/sign-out always wins over an older file read or login callback. */
    private long invalidateAccountOperations() {
        long generation = ++accountGeneration;
        pendingPickerGeneration = -1; pendingPickerRequest = -1;
        Future<?> importing = pendingImport; pendingImport = null; if (importing != null) importing.cancel(true);
        CodexAccountAuth flow = accountFlow; accountFlow = null; if (flow != null) flow.close();
        AlertDialog dialog = accountDialog; accountDialog = null; if (dialog != null) dialog.dismiss();
        return generation;
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != pendingPickerRequest) return;
        long generation = pendingPickerGeneration; pendingPickerGeneration = -1; pendingPickerRequest = -1;
        if (generation != accountGeneration || result != RESULT_OK || data == null || data.getData() == null || isDestroyed()) return;
        Uri document = data.getData();
        if (!"content".equals(document.getScheme())) { toast("Select an account file from the document picker"); return; }
        toast("Reading selected account file");
        pendingImport = accountFiles.submit(() -> {
            JSONObject account;
            try (InputStream input = getContentResolver().openInputStream(document)) { account = CodexAccountAuth.readImport(input); }
            catch (Exception invalid) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && generation == accountGeneration) {
                        pendingImport = null; toast("Unable to import account. Select a valid Codex auth.json with account and refresh tokens.");
                    }
                }); return;
            }
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing() || generation != accountGeneration) return;
                pendingImport = null;
                try { saveAccount(account); invalidateAccountOperations(); toast(account.optLong("expiresAt") <= System.currentTimeMillis() ? "Account imported · refresh required on next request" : "Account imported"); settings(); }
                catch (Exception error) { toast(activeWork() ? "Stop active work, then import the account again" : "Unable to save the protected account"); }
            });
        });
    }
    private void signInAccount(boolean voiceOnly) {
        final long generation = invalidateAccountOperations();
        CodexAccountAuth flow = new CodexAccountAuth(); accountFlow = flow;
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView instructions = text("Requesting a device code…"); panel.addView(instructions);
        TextView code = text(""); code.setTextSize(26); code.setTypeface(null, Typeface.BOLD); code.setTextIsSelectable(true); panel.addView(code);
        Button browser = button("Open OpenAI sign-in", () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CodexAccountAuth.VERIFICATION_URI))); }
            catch (Exception unavailable) { toast("Open auth.openai.com/codex/device in a browser and enter the displayed code"); }
        }); browser.setEnabled(false); panel.addView(browser);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Sign in to Codex").setView(panel).setNegativeButton("Cancel", null).create(); accountDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (generation == accountGeneration) ++accountGeneration;
            flow.close(); if (accountFlow == flow) accountFlow = null; if (accountDialog == dialog) accountDialog = null;
        });
        dialog.show(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        flow.signIn(new CodexAccountAuth.Listener() {
            @Override public void onDeviceCode(String userCode) {
                if (isDestroyed() || generation != accountGeneration || !dialog.isShowing()) return;
                instructions.setText("Open the sign-in page and enter this code. Do not share it. Waiting for authorization (up to 15 minutes)…"); code.setText(userCode); browser.setEnabled(true);
            }
            @Override public void onSuccess(JSONObject account) {
                if (isDestroyed() || generation != accountGeneration || !dialog.isShowing()) return;
                try {
                    if (voiceOnly) saveVoiceAccount(account);
                    else saveAccount(account);
                    dialog.dismiss();
                    toast(voiceOnly ? "ChatGPT voice account connected" : "Codex account connected");
                    settings();
                }
                catch (Exception error) { instructions.setText(activeWork() ? "Stop active work, then start sign-in again. The previous connection was preserved." : "Unable to save the protected account. The previous connection was preserved."); code.setText(""); browser.setEnabled(false); flow.close(); }
            }
            @Override public void onFailure(String safeMessage) {
                if (isDestroyed() || generation != accountGeneration || !dialog.isShowing()) return;
                instructions.setText(safeMessage + " The previous connection was preserved."); code.setText(""); browser.setEnabled(false); flow.close();
            }
        });
    }
    private void enableVirtualDisplays() {
        try {
            if(!rikka.shizuku.Shizuku.pingBinder()) {
                Intent open=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if(open!=null)startActivity(open);
                else toast("Install and start Shizuku, then return to authorize Pocket Agent");
            } else if(rikka.shizuku.Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) {
                rikka.shizuku.Shizuku.requestPermission(1001);
            } else toast("Shizuku permission granted; up to three background displays are available on demand");
        }catch(Exception error){toast("Shizuku unavailable; start it and authorize Pocket Agent");}
    }
    private void enableWakeWord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 108);
            return;
        }
        boolean configured;
        try { configured = new VoiceCredentialStore(this).configured(); }
        catch (RuntimeException unreadable) {
            toast("Stored Deepgram key is unavailable; re-enter your voice key"); return;
        }
        if (!configured) { toast("Configure Deepgram voice key first"); return; }
        try {
            startForegroundService(new Intent(this, WakeWordService.class)
                .setAction(WakeWordService.ACTION_ENABLE));
            toast("Hey Jarvis enabled");
        } catch (RuntimeException denied) { toast("Android denied background listening"); }
    }

    private void disconnectVoiceAccount(AlertDialog settingsDialog) {
        if (activeWork()) { toast("Stop active work before changing voice login"); return; }
        try {
            stopService(new Intent(this, SubscriptionVoiceService.class));
            new CredentialStore(this).signOutVoiceCodex();
            VoiceConversationInvitation.cancel(this, "voice_account_signed_out");
            invalidateAccountOperations();
            if (AgentService.isRunning()) command(json("action", "configure"));
            settingsDialog.dismiss();
            toast("Voice login disconnected");
        } catch (Exception error) { toast("Unable to remove the protected voice login"); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == START_VOICE_PERMISSIONS) {
            startVoiceConversation();
            return;
        }
        if (requestCode == 108) {
            if (results.length != 0 && results[0] == PackageManager.PERMISSION_GRANTED) enableWakeWord();
            else toast("Microphone permission is required for Hey Jarvis");
        }
    }

    private void permissions() {
        String[] labels = {"Contacts", "Phone calls and phone status", "Send SMS", "Read and edit calendar", "Runtime notifications"};
        boolean[] selected = new boolean[labels.length];
        new AlertDialog.Builder(this).setTitle("Allow tools you want to use").setMultiChoiceItems(labels, selected, (dialog, index, checked) -> selected[index] = checked)
                .setNegativeButton("Close", null).setPositiveButton("Request selected", (dialog, which) -> {
                    java.util.ArrayList<String> needed = new java.util.ArrayList<>();
                    String[][] groups = {{Manifest.permission.READ_CONTACTS}, {Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE}, {Manifest.permission.SEND_SMS}, {Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, Build.VERSION.SDK_INT >= 33 ? new String[]{Manifest.permission.POST_NOTIFICATIONS} : new String[0]};
                    for (int index = 0; index < groups.length; index++) if (selected[index]) for (String permission : groups[index]) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) needed.add(permission);
                    if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), 1); else toast("No additional permissions requested");
                }).show();
    }
    private EditText field(LinearLayout parent, String label, String value) { parent.addView(text(label)); EditText field = new EditText(this); field.setText(value); field.setSingleLine(); parent.addView(field); return field; }
    private static JSONObject json(String... values) { JSONObject object = new JSONObject(); try { for (int i = 0; i < values.length; i += 2) object.put(values[i], values[i + 1]); } catch (Exception e) { throw new IllegalArgumentException(e); } return object; }
    private TextView text(String value) { TextView view = new TextView(this); view.setText(value); view.setTextSize(15); return view; }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private Button button(String label, Runnable action) { Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setOnClickListener(ignored -> action.run()); return button; }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
