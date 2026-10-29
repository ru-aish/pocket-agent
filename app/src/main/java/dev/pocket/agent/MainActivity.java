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
public final class MainActivity extends androidx.activity.ComponentActivity implements AgentService.Observer, SubscriptionVoiceService.Observer {
    @Override protected void attachBaseContext(android.content.Context base) {
        String theme = base.getSharedPreferences("pocket-ui", 0).getString("theme", "system");
        if (!theme.equals("system")) { android.content.res.Configuration config = new android.content.res.Configuration(base.getResources().getConfiguration()); config.uiMode = (config.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | (theme.equals("dark") ? android.content.res.Configuration.UI_MODE_NIGHT_YES : android.content.res.Configuration.UI_MODE_NIGHT_NO); base = base.createConfigurationContext(config); }
        super.attachBaseContext(base);
    }
    private TextView status, transcript, workerTranscript;
    private ScrollView messages;
    private EditText input;
    private LinearLayout taskList;
    private Button send, jumpToBottom, voiceConversation;
    private TextView voiceConversationStatus, tasksHeading, displayIndicator;
    private Button scheduleIndicator;
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
    private static final int DICTATE_MESSAGE = 65000;
    private final ExecutorService accountFiles = Executors.newSingleThreadExecutor();
    private final ExecutorService historyQueries = Executors.newSingleThreadExecutor();
    private final ExecutorService uiReads = Executors.newSingleThreadExecutor();
    private CodexAccountAuth accountFlow;
    private AlertDialog accountDialog;
    private long accountGeneration;
    private long pendingPickerGeneration = -1;
    private int pendingPickerRequest = -1;
    private Future<?> pendingImport;
    private LinearLayout nativeMessages, voicePanel;
    private View welcomeView;
    private Button providerLabel;
    private int visibleMessages = 60;
    private boolean showAllSettings;
    private GroqSpeechInput groqCapture;
    private String pendingDictationEngine="system";
    private PocketWebUi pocketUi;
    private volatile JSONObject webAuth = json("state", "idle");
    private final java.util.Map<String, PocketWebUi.Reply> webRequests = new java.util.HashMap<>();
    private final java.util.Map<String, JSONArray> webModelProofs = new java.util.HashMap<>();
    private String pendingVoiceInvitation;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); store = new StateStore(this);
        LinearLayout root = column(); root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setBackgroundColor(getColor(R.color.pocket_canvas));
        root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()|android.view.WindowInsets.Type.ime());
            android.view.WindowInsetsController controller=view.getWindowInsetsController();
            int appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            boolean dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            if(controller!=null)controller.setSystemBarsAppearance(dark ? 0 : appearance, appearance);
            view.setPadding(dp(20)+bars.left,dp(12)+bars.top,dp(20)+bars.right,dp(12)+bars.bottom);return insets;
        });
        LinearLayout toolbar = row(); TextView title = text("Pocket Agent"); title.setTextSize(20); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        toolbar.setGravity(Gravity.CENTER_VERTICAL); toolbar.setPadding(0, 0, 0, dp(12));
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(button("Activity", this::autonomyActivity));
        toolbar.addView(button("Settings", this::settings)); root.addView(toolbar);
        status = text("Stopped · Send starts the runtime"); status.setTextColor(getColor(R.color.pocket_muted)); status.setTextSize(12); status.setPadding(0, 0, 0, dp(16)); root.addView(status);
        LinearLayout historyActions = row();
        historyActions.addView(button("New chat", this::newMainThread), new LinearLayout.LayoutParams(0, -2, 1));
        historyActions.addView(button("Copy", this::copyHistory), new LinearLayout.LayoutParams(0, -2, 1));
        historyActions.addView(button("Search", this::searchHistory), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(historyActions);
        FrameLayout transcriptFrame = new FrameLayout(this);
        messages = new ScrollView(this); messages.setFillViewport(true); transcript = text(""); transcript.setTextIsSelectable(true); transcript.setLineSpacing(dp(6), 1); transcript.setPadding(0, dp(32), 0, dp(24));
        LinearLayout conversationBody = column(); conversationBody.addView(transcript);
        scheduleIndicator = button("Schedules", this::autonomyActivity); scheduleIndicator.setVisibility(View.GONE); conversationBody.addView(scheduleIndicator);
        displayIndicator = activityMeta(""); displayIndicator.setVisibility(View.GONE); conversationBody.addView(displayIndicator);
        tasksHeading = activityMeta("Subagents"); tasksHeading.setVisibility(View.GONE); conversationBody.addView(tasksHeading);
        taskList = column(); conversationBody.addView(taskList); messages.addView(conversationBody);
        transcriptFrame.addView(messages, new FrameLayout.LayoutParams(-1, -1));
        jumpToBottom = button("↓", () -> { messages.fullScroll(View.FOCUS_DOWN); messages.post(this::updateJumpToBottom); });
        jumpToBottom.setContentDescription("Jump to latest message"); jumpToBottom.setVisibility(View.GONE); jumpToBottom.setMinWidth(0); jumpToBottom.setMinimumWidth(0);
        FrameLayout.LayoutParams jumpParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.BOTTOM); jumpParams.setMargins(0, 0, dp(6), dp(6));
        transcriptFrame.addView(jumpToBottom, jumpParams);
        messages.setOnScrollChangeListener((view, x, y, oldX, oldY) -> updateJumpToBottom());
        root.addView(transcriptFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout composer = column(); composer.setPadding(dp(12), dp(8), dp(12), dp(8)); composer.setBackground(surface(R.color.pocket_surface, 16)); root.addView(composer);
        input = new EditText(this); input.setHint("What’s on your mind?"); input.setMinLines(1); input.setMaxLines(4); input.setTextSize(16); input.setTextColor(getColor(R.color.pocket_ink)); input.setHintTextColor(getColor(R.color.pocket_muted)); input.setBackground(null); input.setPadding(dp(4), dp(8), dp(4), dp(12)); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); LinearLayout entry = row(); entry.addView(input, new LinearLayout.LayoutParams(0, -2, 1)); entry.addView(button("Voice", this::startVoiceConversation)); composer.addView(entry);
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
        })); composer.addView(controls);
        send.setBackground(surface(R.color.pocket_accent, 10)); send.setTextColor(getColor(R.color.pocket_on_accent));
        voiceConversation = button("Start Codex voice", this::toggleVoiceConversation);
        root.addView(voiceConversation);
        voiceConversationStatus = text("Codex voice is off");
        root.addView(voiceConversationStatus);
        Button voiceControl = button("Stop assistant listening", () -> stopService(new Intent(this, VoiceListeningService.class)));
        voiceControl.setBackgroundColor(android.graphics.Color.TRANSPARENT); root.addView(voiceControl);
        buildNativeShell(root, transcriptFrame);
        if (saved != null) input.setText(saved.getString("composeDraft", ""));
        pocketUi = new PocketWebUi(this, this::webRequest);
        getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){navigateBack();}
        });
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) { if(pocketUi!=null&&!s.toString().isEmpty())pocketUi.event("dictation",json("text",s.toString())); }
            public void afterTextChanged(Editable value) { }
        });
        FrameLayout webRoot = new FrameLayout(this);
        webRoot.setBackgroundColor(getColor(R.color.pocket_canvas));
        webRoot.addView(pocketUi.view, new FrameLayout.LayoutParams(-1, -1));
        final boolean[] imeAnimating={false};
        webRoot.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()|android.view.WindowInsets.Type.ime());
            if(pocketUi!=null)pocketUi.keyboardVisibility(insets.isVisible(android.view.WindowInsets.Type.ime()));
            if(!imeAnimating[0])view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        webRoot.setWindowInsetsAnimationCallback(new android.view.WindowInsetsAnimation.Callback(android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE){
            @Override public void onPrepare(android.view.WindowInsetsAnimation animation){if((animation.getTypeMask()&android.view.WindowInsets.Type.ime())!=0)imeAnimating[0]=true;}
            @Override public android.view.WindowInsets onProgress(android.view.WindowInsets insets,java.util.List<android.view.WindowInsetsAnimation> running){if(imeAnimating[0]){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()|android.view.WindowInsets.Type.ime());webRoot.setPadding(bars.left,bars.top,bars.right,bars.bottom);}return insets;}
            @Override public void onEnd(android.view.WindowInsetsAnimation animation){if((animation.getTypeMask()&android.view.WindowInsets.Type.ime())!=0){imeAnimating[0]=false;webRoot.requestApplyInsets();}}
        });
        setContentView(webRoot);
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
        if (input != null) saved.putString("composeDraft", input.getText().toString());
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
    @Override protected void onStop() { if(groqCapture!=null){groqCapture.close();groqCapture=null;if(pocketUi!=null)pocketUi.event("voice",json("active",false,"message","Recording stopped"));}AgentService.unobserve(this); SubscriptionVoiceService.unobserve(this); super.onStop(); }
    @Override public void onVoiceState(boolean active, String message) {
        if(pocketUi!=null)pocketUi.event("voice",json("active",active,"message",message));
        voiceConversation.setText(active ? "Disconnect Codex voice" : "Start Codex voice");
        voiceConversationStatus.setText(message);
        if (active && voicePanel != null) voicePanel.setVisibility(View.VISIBLE);
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
        if (pocketUi != null) pocketUi.close();
        if (groqCapture != null) groqCapture.close();
        invalidateAccountOperations();
        accountFiles.shutdownNow(); historyQueries.shutdownNow(); uiReads.shutdownNow(); store.close(); super.onDestroy();
    }
    @Override public void onAgentEvent(JSONObject event) {
        if (pocketUi != null) {
            // State is pulled by the WebView; do not serialize its full transcript twice.
            pocketUi.event("agent", json("type",event.optString("type"),"error",event.optString("error")));
            PocketWebUi.Reply reply = webRequests.remove(event.optString("requestId"));
            if(reply!=null&&event.optString("type").equals("models")){JSONArray catalog=event.optJSONArray("models");if(catalog!=null)for(int i=0;i<catalog.length();i++){JSONObject model=catalog.optJSONObject(i);if(model!=null&&model.optJSONArray("supportedReasoningLevels")!=null)webModelProofs.put(model.optString("id"),model.optJSONArray("supportedReasoningLevels"));}}
            if (reply != null) reply.complete(event, event.optString("error").isEmpty() ? null : event.optString("error"));
        }
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
        if(pocketUi!=null){if(workerTranscript!=null&&viewedWorker!=null){String text=workerMessages(viewedWorker);if(!text.contentEquals(workerTranscript.getText()))workerTranscript.setText(text);}return;}
        status.setText(!AgentService.isRunning() ? "Stopped · Send starts the runtime" : state.optBoolean("busy") ? "Main is responding" : state.optBoolean("active") ? "Workers active · Main is available" : "Ready");
        if(AgentService.isRunning() && !state.isNull("lastError") && !state.optString("lastError").isEmpty()) status.setText("Error · "+state.optString("lastError"));
        String rendered=formatMessages(state.optJSONArray("messages"));
        if(!rendered.contentEquals(transcript.getText())) {
            boolean follow=messages.getScrollY()+messages.getHeight()>=messages.getChildAt(0).getHeight()-dp(48);
            transcript.setText(styledTranscript(rendered, state.optJSONArray("messages")));
            renderNativeMessages(state.optJSONArray("messages"));
            if(follow)messages.post(()->{ messages.fullScroll(View.FOCUS_DOWN); updateJumpToBottom(); });
            else messages.post(this::updateJumpToBottom);
        } else messages.post(this::updateJumpToBottom);
        taskList.removeAllViews(); JSONArray tasks = state.optJSONArray("tasks");
        tasksHeading.setVisibility(tasks == null || tasks.length() == 0 ? View.GONE : View.VISIBLE);
        if (tasks == null || tasks.length() == 0) taskList.setVisibility(View.GONE);
        else for (int i = 0; i < tasks.length(); i++) {
            taskList.setVisibility(View.VISIBLE);
            JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
            String id = task.optString("conversationId", task.optString("id"));
            LinearLayout card = activityCard(taskList); card.addView(text(task.optString("label", "Worker task")));
            card.addView(activityMeta(taskStatus(task.optString("status")) + " · Run " + task.optInt("generation")));
            String display = displayLabel(task.optJSONObject("display")); if (!display.isEmpty()) card.addView(activityMeta(display));
            card.addView(button("Open worker", () -> worker(id)));
        }
        JSONObject autonomy = state.optJSONObject("autonomy"); JSONArray schedules = autonomy == null ? null : autonomy.optJSONArray("schedules");
        scheduleIndicator.setVisibility(schedules == null || schedules.length() == 0 ? View.GONE : View.VISIBLE);
        if (schedules != null) scheduleIndicator.setText("Schedules · " + schedules.length() + " saved · View activity");
        JSONObject displays = state.optJSONObject("displays"); String mainDisplay = displayLabel(displays == null ? null : displays.optJSONObject("main"));
        displayIndicator.setText(mainDisplay); displayIndicator.setVisibility(mainDisplay.isEmpty() ? View.GONE : View.VISIBLE);
        if (welcomeView != null) welcomeView.setVisibility((state.optJSONArray("messages") == null || state.optJSONArray("messages").length() == 0) && (tasks == null || tasks.length() == 0) && (schedules == null || schedules.length() == 0) ? View.VISIBLE : View.GONE);
        status.setVisibility(state.optBoolean("busy") || !state.optString("lastError").isEmpty() ? View.VISIBLE : View.GONE);
        updateProviderLabel();
        if (workerTranscript != null && viewedWorker != null) workerTranscript.setText(workerMessages(viewedWorker));
    }
    private String formatMessages(JSONArray messages) {
        if (messages == null || messages.length() == 0) return "A little help.\nA lot more done.\n\nA thought, a question, a thing to take care of.\nStart here. Pocket will take it from there.";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) { JSONObject message = messages.optJSONObject(i); if (message == null) continue; text.append(message.optString("role")).append(message.has("inputSource") ? " (" + message.optString("inputSource") + ")" : "").append(":\n").append(message.optString("text", message.optString("content"))).append("\n\n"); }
        return text.toString();
    }
    private static String displayLabel(JSONObject display) {
        if (display == null) return "";
        String id = display.isNull("displayId") ? "" : " #" + display.optInt("displayId");
        return switch (display.optString("state")) { case "waiting" -> "Waiting for a virtual display"; case "preparing" -> "Preparing virtual display"; case "active" -> "Using virtual display" + id; case "releasing" -> "Releasing virtual display" + id; default -> ""; };
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
    /** Resolves a blank key only from this provider's saved profile and endpoint. */
    static String savedProviderKeyForEndpoint(JSONObject profile, String mode, String endpoint, String enteredKey) {
        String key = enteredKey.trim();
        if (!key.isEmpty()) return key;
        String resolvedEndpoint = mode.equals(CredentialStore.OPENCODE) ? CredentialStore.OPENCODE_ENDPOINT
                : mode.equals(CredentialStore.NEBIUS) ? CredentialStore.NEBIUS_ENDPOINT : endpoint.trim();
        return resolvedEndpoint.equals(profile.optString("endpoint")) ? profile.optString("apiKey") : "";
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
    private void showAutonomyActivity(JSONObject saved, JSONObject automationStatus) {
        android.app.Dialog dialog = new android.app.Dialog(this, R.style.PocketTheme);
        LinearLayout root = column(); root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setBackgroundColor(getColor(R.color.pocket_canvas));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(dp(20) + bars.left, dp(16) + bars.top, dp(20) + bars.right, dp(16) + bars.bottom); return insets;
        });
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(button("Back", dialog::dismiss));
        TextView title = text("Activity"); title.setTextSize(22); title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(button("Refresh", () -> { dialog.dismiss(); autonomyActivity(); })); root.addView(header);
        ScrollView scroll = new ScrollView(this); LinearLayout panel = column(); panel.setPadding(0, dp(20), 0, dp(12)); scroll.addView(panel);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout policy = activityCard(panel);
        CheckBox enabled = new CheckBox(this); enabled.setText("Scheduled & autonomous work"); enabled.setChecked(AutonomyStore.enabled(this));
        enabled.setOnCheckedChangeListener((button, checked) -> {
            AutonomyStore.requestEnabled(this, checked, error -> runOnUiThread(() -> toast("Could not update automation")));
            if (!checked) { RuntimeHost shared = RuntimeOwner.peekHost(); if (shared != null) shared.dispatch(json("action", "stop_autonomy")); }
        }); policy.addView(enabled);
        policy.addView(activityMeta("Quiet hours: 10 PM–8 AM. Stop all also pauses future automation."));
        if (!automationStatus.optBoolean("exactAlarmPermission")) {
            LinearLayout timing = activityCard(panel);
            timing.addView(activityMeta("Allow Alarms & reminders for precise schedule wakeups."));
            timing.addView(button("Allow precise schedule timing", () -> webPhoneAction("exact-alarms")));
        }
        if (automationStatus.optBoolean("batteryRestricted")) {
            LinearLayout battery = activityCard(panel); battery.addView(text("Background runs may be delayed"));
            battery.addView(activityMeta("Allow unrestricted battery use for more reliable screen-off work."));
            battery.addView(button("Battery settings", () -> { try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (RuntimeException unavailable) { toast("Open Pocket Agent battery settings in Android"); } }));
        }
        String startupIssue = automationStatus.optString("lastFailure");
        if (!startupIssue.isEmpty()) { LinearLayout issue = activityCard(panel); issue.addView(text("Background work needs attention")); issue.addView(activityMeta(startupIssue)); }
        activitySection(panel, "Needs your input");
        JSONArray decisions = saved.optJSONArray("decisions"); boolean pending = false;
        if (decisions != null) for (int i = 0; i < decisions.length(); i++) {
            JSONObject decision = decisions.optJSONObject(i); if (decision == null || !"pending".equals(decision.optString("status"))) continue; pending = true;
            LinearLayout card = activityCard(panel); card.addView(text(decision.optString("question", "A task needs your input")));
            if ("approval".equals(decision.optString("kind"))) {
                JSONArray tools = decision.optJSONArray("tools"); java.util.ArrayList<String> names = new java.util.ArrayList<>();
                if (tools != null) for (int tool = 0; tool < tools.length(); tool++) names.add(tools.optString(tool));
                card.addView(activityMeta("Next run only · expires in one hour\n" + android.text.TextUtils.join(", ", names)));
                card.addView(button("Approve next run", () -> answerDecision(decision, true, "Approved the listed phone tools for the next run")));
                card.addView(button("Decline", () -> answerDecision(decision, false, "Declined")));
            } else {
                EditText answer = field(card, "Your answer", "");
                card.addView(button("Send answer", () -> { String value = answer.getText().toString().trim(); if (!value.isEmpty()) answerDecision(decision, true, value); }));
            }
        }
        if (!pending) panel.addView(activityMeta("Nothing needs your attention."));
        activitySection(panel, "Schedules");
        JSONArray schedules = saved.optJSONArray("schedules");
        if (schedules == null || schedules.length() == 0) panel.addView(activityMeta("Ask Pocket to schedule a task. Its next run and pause control will appear here."));
        else for (int i = 0; i < schedules.length(); i++) {
            JSONObject schedule = schedules.optJSONObject(i); if (schedule == null) continue;
            String id = schedule.optString("id"); boolean active = schedule.optBoolean("enabled");
            LinearLayout card = activityCard(panel); card.addView(text(scheduleTitle(schedule, saved)));
            card.addView(activityMeta((active ? "Active" : "Paused or finished") + " · " + scheduleTime(schedule)));
            card.addView(button(active ? "Pause schedule" : "Resume schedule", () -> {
                command(Json.obj("action", "automation_control", "tool", "schedule", "args", json("action", active ? "pause" : "resume", "id", id)));
                dialog.dismiss(); root.postDelayed(this::autonomyActivity, 250);
            }));
        }
        activitySection(panel, "Recent runs");
        JSONArray runs = saved.optJSONArray("runs");
        if (runs == null || runs.length() == 0) panel.addView(activityMeta("Results will appear after a scheduled task runs."));
        else for (int i = runs.length() - 1; i >= Math.max(0, runs.length() - 20); i--) {
            JSONObject run = runs.optJSONObject(i); if (run == null) continue;
            LinearLayout card = activityCard(panel); card.addView(text(taskStatus(run.optString("status")) + " · " + run.optString("source", "Background task")));
            String output = run.optString("output"); card.addView(activityMeta(runSummary(run)));
            if (!output.isEmpty()) card.addView(button("View details", () -> activityDetails("Run details", output)));
        }
        activitySection(panel, "Results");
        JSONArray results = saved.optJSONArray("notifications");
        if (results == null || results.length() == 0) panel.addView(activityMeta("No new results."));
        else for (int i = results.length() - 1; i >= Math.max(0, results.length() - 20); i--) {
            JSONObject result = results.optJSONObject(i); if (result == null) continue;
            LinearLayout card = activityCard(panel); card.addView(text(result.optString("title", "Task result")));
            card.addView(activityMeta(result.optString("message")));
        }
        dialog.setContentView(root); dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
    }
    private void activitySection(LinearLayout parent, String label) {
        TextView heading = text(label); heading.setTextSize(13); heading.setTextColor(getColor(R.color.pocket_muted)); heading.setPadding(0, dp(24), 0, dp(12)); parent.addView(heading);
    }
    private LinearLayout activityCard(LinearLayout parent) {
        LinearLayout card = column(); card.setPadding(dp(16), dp(16), dp(16), dp(16)); card.setBackground(surface(R.color.pocket_surface, 14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, 0, 0, dp(12)); parent.addView(card, params); return card;
    }
    private TextView activityMeta(String label) {
        TextView view = text(label); view.setTextSize(13); view.setTextColor(getColor(R.color.pocket_muted)); view.setLineSpacing(dp(3), 1); view.setPadding(0, dp(8), 0, dp(8)); return view;
    }
    private static String scheduleTime(JSONObject schedule) {
        long next = schedule.optLong("nextAt", 0);
        return next > 0 ? "Next: " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(new java.util.Date(next)) : "No upcoming run";
    }
    private static String scheduleTitle(JSONObject schedule, JSONObject saved) {
        String title = schedule.optString("title").trim();
        if (!title.isEmpty()) return title;
        JSONArray tasks = saved.optJSONArray("tasks");
        if (tasks != null) for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task != null && task.optString("id").equals(schedule.optString("taskId"))) {
                String taskTitle = task.optString("title").trim(); if (!taskTitle.isEmpty()) return taskTitle;
            }
        }
        return "Scheduled task";
    }
    private static String taskStatus(String value) {
        return switch (value) { case "succeeded", "done" -> "Completed"; case "running", "dispatching" -> "Working"; case "queued", "waiting" -> "Queued"; case "failed", "error" -> "Failed"; case "cancelled", "aborted" -> "Stopped"; default -> value.isEmpty() ? "Pending" : value; };
    }
    private static String runSummary(JSONObject run) {
        String output = run.optString("summary", run.optString("output")).trim();
        if (output.isEmpty()) return "Waiting for a result.";
        if (output.startsWith("{") || output.startsWith("[") || output.startsWith("Verified worker result:") || output.contains("\"operation_prefix\""))
            return "failed".equals(run.optString("status")) ? "The run failed. Open details for the error." : "The run finished. Open details to inspect its technical result.";
        return output.length() > 240 ? output.substring(0, 240) + "…" : output;
    }
    private void activityDetails(String title, String details) {
        ScrollView scroll = new ScrollView(this); TextView content = text(details); content.setPadding(dp(20), dp(12), dp(20), dp(12)); content.setTextIsSelectable(true); scroll.addView(content);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close", null).show();
    }
    private void answerDecision(JSONObject decision,boolean approved,String answer) {
        AutonomyScheduler.execute(()->{
            try(AutonomyStore automation=new AutonomyStore(this)) {
                automation.incoming(Json.obj("id","decision:"+decision.getString("id"),"type","decision","decisionId",decision.getString("id"),"approved",approved,"answer",answer));
                runOnUiThread(()->toast("Response saved. The task will continue when background work can run."));
            } catch(Exception error){android.util.Log.w("PocketAutonomy","Could not save decision: "+error.getClass().getSimpleName());runOnUiThread(()->toast("Could not save the response"));}
        });
    }
    private void detach(View view) { if (view.getParent() instanceof android.view.ViewGroup parent) parent.removeView(view); }
    private View iconControl(String icon, String label, Runnable action) {
        android.widget.ImageButton view = new android.widget.ImageButton(this); view.setImageDrawable(new PocketIcon(icon, getColor(R.color.pocket_ink), getColor(R.color.pocket_accent)));
        view.setContentDescription(label); view.setPadding(dp(14), dp(14), dp(14), dp(14)); view.setBackground(surface(R.color.pocket_surface, 24)); view.setOnClickListener(ignored -> action.run()); view.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48))); return view;
    }
    private void buildNativeShell(LinearLayout root, FrameLayout conversationFrame) {
        detach(conversationFrame); detach(input); detach(send); detach(status); detach(voiceConversation); detach(voiceConversationStatus); root.removeAllViews();
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL); header.addView(iconControl("menu", "Open chat sidebar", this::openChats));
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1)); header.addView(iconControl("more", "Conversation actions", this::conversationActions)); root.addView(header);
        root.addView(status); root.addView(conversationFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout conversationBody = (LinearLayout) messages.getChildAt(0); transcript.setVisibility(View.GONE); nativeMessages = column(); conversationBody.addView(nativeMessages, 1);
        LinearLayout welcome = column(); welcome.setGravity(Gravity.CENTER); welcome.setPadding(dp(12), 0, dp(12), dp(24));
        android.widget.ImageView mark = new android.widget.ImageView(this); mark.setImageDrawable(new PocketIcon("brand", getColor(R.color.pocket_on_accent), getColor(R.color.pocket_accent))); welcome.addView(mark, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView greeting = text("What can I help with?"); greeting.setTextSize(22); greeting.setGravity(Gravity.CENTER); greeting.setPadding(0, dp(24), 0, dp(12)); welcome.addView(greeting);
        TextView help = activityMeta("Ask a question or give Pocket a task."); help.setGravity(Gravity.CENTER); welcome.addView(help);
        conversationFrame.addView(welcome, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)); welcomeView = welcome;
        voicePanel = column(); voicePanel.setPadding(dp(12), dp(8), dp(12), dp(8)); voicePanel.setBackground(surface(R.color.pocket_surface, 14)); voicePanel.addView(voiceConversation); voicePanel.addView(voiceConversationStatus); voicePanel.addView(button("Close voice", () -> { stopService(new Intent(this, SubscriptionVoiceService.class)); voicePanel.setVisibility(View.GONE); })); voicePanel.setVisibility(View.GONE); root.addView(voicePanel);
        LinearLayout composer = column(); composer.setPadding(dp(12), dp(12), dp(12), dp(8)); composer.setBackground(surface(R.color.pocket_surface, 16)); input.setHint("What’s on your mind?"); composer.addView(input, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = row(); actions.setGravity(Gravity.CENTER_VERTICAL); providerLabel = button("Pocket Agent", this::settings); providerLabel.setBackgroundColor(android.graphics.Color.TRANSPARENT); actions.addView(providerLabel, new LinearLayout.LayoutParams(0, dp(48), 1));
        View mic = iconControl("mic", "Voice input; hold for voice modes", this::startDictation); mic.setOnLongClickListener(view -> { showVoiceChoices(); return true; }); actions.addView(mic);
        send.setText(""); send.setContentDescription("Send message"); PocketIcon arrow = new PocketIcon("send", getColor(R.color.pocket_on_accent), getColor(R.color.pocket_accent)); arrow.setBounds(0, 0, dp(20), dp(20)); send.setCompoundDrawables(arrow, null, null, null); send.setPadding(dp(14), 0, dp(14), 0); send.setGravity(Gravity.CENTER); LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(48), dp(48)); sendParams.setMargins(dp(8), 0, 0, 0); actions.addView(send, sendParams); composer.addView(actions); root.addView(composer);
        updateProviderLabel();
    }
    private void updateProviderLabel() {
        if (providerLabel == null) return;
        try { String mode = new CredentialStore(this).get().optString("authMode"); providerLabel.setText(switch (mode) { case "nebius" -> "Nebius"; case "opencode" -> "OpenCode"; case "codex-account" -> "Codex"; default -> "Pocket Agent"; }); }
        catch (Exception unavailable) { providerLabel.setText("Connect provider"); }
    }
    private void renderNativeMessages(JSONArray items) {
        if (nativeMessages == null) return; nativeMessages.removeAllViews(); if (items == null) return;
        int start = Math.max(0, items.length() - visibleMessages);
        if (start > 0) nativeMessages.addView(button("Load earlier messages", () -> { visibleMessages += 60; renderNativeMessages(state.optJSONArray("messages")); }));
        for (int i = start; i < items.length(); i++) {
            JSONObject message = items.optJSONObject(i); if (message == null) continue;
            LinearLayout card = column(); card.setPadding(dp(12), dp(12), dp(12), dp(16));
            boolean user = "user".equals(message.optString("role")); if (user) card.setBackground(surface(R.color.pocket_raised, 16));
            TextView label = activityMeta(user ? "You" : "Pocket"); label.setTextSize(12); card.addView(label);
            TextView body = text(message.optString("text", message.optString("content"))); body.setTextIsSelectable(true); body.setLineSpacing(dp(4), 1); card.addView(body);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(user ? dp(24) : 0, dp(8), 0, dp(12)); nativeMessages.addView(card, params);
        }
    }
    private void copyConversation() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) { toast("Clipboard unavailable"); return; }
        StringBuilder result = new StringBuilder();
        JSONArray messages = state.optJSONArray("messages");
        if (messages != null) for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            if (result.length() > 0) result.append("\n\n");
            result.append("user".equals(message.optString("role")) ? "You: " : "Pocket: ");
            result.append(message.optString("text", message.optString("content")));
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Pocket Agent conversation", result.toString()));
        toast("Conversation copied");
    }
    private JSONObject webStatus() throws Exception {
        CredentialStore credentials=new CredentialStore(this);JSONObject config=credentials.get();String mode=config.optString("authMode");
        JSONObject providers = new JSONObject();
        for(String provider:new String[]{"nebius","opencode","codex-account"}){JSONObject profile=credentials.getProviderProfile(provider);providers.put(provider,json("configured",!profile.optString(provider.equals("codex-account")?"accessToken":"apiKey").isEmpty(),"active",mode.equals(provider),"model",profile.optString("model"),"reasoning",profile.optString("reasoningEffort","low")));}
        return json("providers",providers,"auth",webAuth,"voiceAccount",!new CredentialStore(this).getVoiceCodex().optString("accessToken").isEmpty(),"voice",json("groq",new VoiceCredentialStore(this,"groq").configured(),"deepgram",new VoiceCredentialStore(this).configured()));
    }
    private JSONObject webSnapshot() throws Exception { return webSnapshot(state); }
    private JSONObject webSnapshot(JSONObject currentState) throws Exception {
        JSONObject config = new CredentialStore(this).get(); String mode=config.optString("authMode");
        JSONObject prefs=json("name",getSharedPreferences("pocket-ui",0).getString("name",""),"provider",java.util.List.of("nebius","opencode","codex-account").contains(mode)?mode:"none","model",config.optString("model"),"reasoning",config.optString("reasoningEffort","low"),"instructions",currentState.optString("personalInstructions"),"stt",getSharedPreferences("pocket-ui",0).getString("voiceEngine","system"),"wake",WakeWordService.enabled(this),"autonomy",AutonomyStore.enabled(this));
        JSONArray servers=new JSONArray(),stored=credentialsForUi();
        for(int i=0;i<stored.length();i++){JSONObject server=stored.getJSONObject(i);servers.put(json("id",server.optString("id"),"name",server.optString("name"),"url",server.optString("url"),"authMode",server.optString("authMode"),"enabled",server.optBoolean("enabled")));}
        JSONObject result=json("state",currentState,"preferences",prefs,"connection",webStatus(),"servers",servers,"voiceActive",SubscriptionVoiceService.isActive());
        try(AutonomyStore automation=new AutonomyStore(this)){result.put("autonomy",automation.load());result.put("autonomyStatus",automation.status());}
        return result;
    }
    private JSONArray credentialsForUi() throws Exception { return new CredentialStore(this).mcpServers(); }
    private void finishChatDeletion(String threadId,PocketWebUi.Reply reply,long deadline) {
        if(isDestroyed()){reply.complete(null,"Chat deletion was interrupted");return;}
        if(!threadId.equals(state.optString("mainThreadId"))){
            boolean saved=getSharedPreferences("conversation-ui",0).edit().putBoolean(threadId+".deleted",true).commit();
            reply.complete(saved?json("ok",true):null,saved?null:"Could not save chat changes");return;
        }
        if(android.os.SystemClock.elapsedRealtime()>=deadline){reply.complete(null,"Could not close this chat. Stop active work and try again.");return;}
        pocketUi.view.postDelayed(()->finishChatDeletion(threadId,reply,deadline),50);
    }
    static JSONObject webModelSettings(JSONObject saved) throws Exception {
        JSONObject settings=new JSONObject(saved.toString());
        if(settings.optString("authMode").equals("codex-account"))
            for(String field:new String[]{"accessToken","refreshToken","accountId","expiresAt","idToken"})settings.remove(field);
        return settings;
    }
    private void webRequest(String path,JSONObject body,PocketWebUi.Reply reply) throws Exception {
        CredentialStore credentials=new CredentialStore(this);
        switch(path) {
            case "/native/snapshot" -> {
                JSONObject currentState=state;
                uiReads.submit(()->{try {reply.complete(webSnapshot(currentState),null);}
                    catch(Exception failure){reply.complete(null,"Could not refresh the phone state. Try again.");}});
                return;
            }
            case "/native/keyboard-hide" -> {android.view.inputmethod.InputMethodManager ime=getSystemService(android.view.inputmethod.InputMethodManager.class);ime.hideSoftInputFromWindow(pocketUi.view.getWindowToken(),0);pocketUi.keyboardVisibility(false);}
            case "/native/display-preview" -> {int id=body.optInt("displayId",-1);historyQueries.submit(()->{try {reply.complete(AgentDisplayCoordinator.shared(getApplicationContext()).preview(id),null);}catch(Exception unavailable){reply.complete(null,"This private screen has finished or is unavailable");}});return;}
            case "/api/provider/status" -> { replyWebStatus(reply);return; }
            case "/native/chats" -> { historyQueries.submit(()->{try(StateStore history=new StateStore(getApplicationContext())){java.util.Map<String,String> titles=new java.util.HashMap<>();for(java.util.Map.Entry<String,?> entry:getSharedPreferences("conversation-titles",0).getAll().entrySet())if(entry.getValue() instanceof String text)titles.put(entry.getKey(),text);JSONArray saved=history.listConversations(body.optString("query"),titles),chats=new JSONArray();android.content.SharedPreferences metadata=getSharedPreferences("conversation-ui",0);for(int i=0;i<saved.length();i++){JSONObject chat=saved.getJSONObject(i);String threadId=chat.optString("threadId");if(metadata.getBoolean(threadId+".deleted",false))continue;String title=titles.getOrDefault(threadId,"");chat.put("snippet",chat.optString("title"));chat.put("named",!title.isEmpty());chat.put("pinned",metadata.getBoolean(threadId+".pinned",false));if(!title.isEmpty())chat.put("title",title);chats.put(chat);}reply.complete(json("chats",chats),null);}catch(Exception failure){reply.complete(null,"Could not read saved chats");}});return; }
            case "/native/chat-state" -> {
                String threadId=body.optString("threadId");if(!threadId.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid chat");
                if(body.optBoolean("deleted")){
                    JSONArray tasks=state.optJSONArray("tasks");if(tasks!=null)for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task!=null&&threadId.equals(task.optString("mainThreadId"))&&java.util.List.of("running","queued","waiting").contains(task.optString("status")))throw new IllegalStateException("Stop this chat's active work before deleting it");}
                    if(threadId.equals(state.optString("mainThreadId"))){if(activeWork())throw new IllegalStateException("Stop active work before deleting this chat");VoiceConversationInvitation.cancel(this,"thread_changed");stopService(new Intent(this,SubscriptionVoiceService.class));command(json("action","new_main"));finishChatDeletion(threadId,reply,android.os.SystemClock.elapsedRealtime()+20000);return;}
                }
                android.content.SharedPreferences.Editor metadata=getSharedPreferences("conversation-ui",0).edit();
                if(body.has("pinned"))metadata.putBoolean(threadId+".pinned",body.optBoolean("pinned"));
                if(body.has("deleted"))metadata.putBoolean(threadId+".deleted",body.optBoolean("deleted"));
                if(!metadata.commit())throw new IllegalStateException("Could not save chat changes");
            }
            case "/native/chat-rename" -> {
                String threadId=body.optString("threadId"),title=body.optString("title").trim().replaceAll("\\s+"," ");
                if(!threadId.matches("[a-f0-9-]{36}")||title.isEmpty()||title.length()>80)throw new IllegalArgumentException("Enter a chat name of 1 to 80 characters");
                if(!getSharedPreferences("conversation-titles",0).edit().putString(threadId,title).commit())throw new IllegalStateException("Could not rename this chat");
            }
            case "/native/title" -> {
                String threadId=body.optString("threadId");if(threadId.isEmpty()||threadId.length()>300)throw new IllegalArgumentException("Invalid conversation");
                String saved=getSharedPreferences("conversation-titles",0).getString(threadId,"");if(!saved.isEmpty()){reply.complete(json("title",saved),null);return;}
                historyQueries.submit(()->{try(StateStore history=new StateStore(getApplicationContext())){
                    JSONArray items=history.readConversation(threadId,0).getJSONArray("messages");StringBuilder sample=new StringBuilder();for(int i=0;i<Math.min(2,items.length());i++){JSONObject message=items.getJSONObject(i);sample.append(message.optString("role")).append(": ").append(message.optString("text")).append('\n');}String text=sample.substring(0,Math.min(4000,sample.length()));
                    runOnUiThread(()->{if(isDestroyed())return;String id="title-"+System.nanoTime();webRequests.put(id,(result,error)->{if(error==null&&result!=null){String title=result.optString("title").trim();if(!title.isEmpty()&&title.length()<=100&&getSharedPreferences("conversation-titles",0).getString(threadId,"").isEmpty())getSharedPreferences("conversation-titles",0).edit().putString(threadId,title).apply();}reply.complete(result,error);});command(json("action","generate_title","requestId",id,"text",text));pocketUi.view.postDelayed(()->{PocketWebUi.Reply pending=webRequests.remove(id);if(pending!=null)pending.complete(null,"Conversation naming timed out");},25000);});
                }catch(Exception failure){reply.complete(null,"Conversation naming is unavailable");}});return;
            }
            case "/native/archive" -> { historyQueries.submit(()->{try(StateStore history=new StateStore(getApplicationContext())){reply.complete(history.readConversation(body.optString("threadId"),body.optInt("offset")),null);}catch(Exception failure){reply.complete(null,"Could not read saved conversation");}});return; }
            case "/native/open-chat" -> {String threadId=body.optString("threadId");if(!threadId.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid chat");if(state.optBoolean("busy"))throw new IllegalStateException("Stop the current response before switching chats");String id="open-"+System.nanoTime();webRequests.put(id,(value,error)->{if(error!=null){reply.complete(null,error);return;}try{reply.complete(webSnapshot(),null);}catch(Exception failure){reply.complete(null,"Could not load the opened chat");}});VoiceConversationInvitation.cancel(this,"thread_changed");stopService(new Intent(this,SubscriptionVoiceService.class));command(json("action","open_main","threadId",threadId,"requestId",id));pocketUi.view.postDelayed(()->{PocketWebUi.Reply pending=webRequests.remove(id);if(pending!=null)pending.complete(null,"Could not open this chat. Try again.");},20000);return;}
            case "/native/send" -> { String text=body.optString("text").trim();if(text.isEmpty()||text.length()>12000)throw new IllegalArgumentException("Enter a message of 12000 characters or fewer");sendMainMessage(text); }
            case "/native/new" -> { if(state.optBoolean("busy"))throw new IllegalStateException("Stop the current response before starting a new chat");VoiceConversationInvitation.cancel(this,"thread_changed");stopService(new Intent(this,SubscriptionVoiceService.class));command(json("action","new_main")); }
            case "/native/stop" -> command(json("action","stop_main"));
            case "/native/minimize" -> moveTaskToBack(true);
            case "/native/theme" -> {
                boolean light=body.optString("theme").equals("light");int color=android.graphics.Color.parseColor(light?"#FAFAFA":"#000000");
                getWindow().setStatusBarColor(color);getWindow().setNavigationBarColor(color);
                if(pocketUi.view.getParent() instanceof View parent)parent.setBackgroundColor(color);
                android.view.WindowInsetsController controller=getWindow().getDecorView().getWindowInsetsController();
                int flags=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                if(controller!=null)controller.setSystemBarsAppearance(light?flags:0,flags);
            }
            case "/native/copy" -> {ClipboardManager clipboard=getSystemService(ClipboardManager.class);if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("Pocket Agent conversation",body.optString("text")));}
            case "/native/preferences" -> {
                if(body.has("name"))getSharedPreferences("pocket-ui",0).edit().putString("name",body.optString("name").substring(0,Math.min(120,body.optString("name").length()))).apply();
                if(body.has("instructions")){String value=body.optString("instructions");if(value.length()>50000)throw new IllegalArgumentException("Instructions are too long");command(json("action","configure","personalInstructions",value));}
                if(body.has("stt")){String value=body.optString("stt");if(!java.util.List.of("system","groq","deepgram","codex").contains(value))throw new IllegalArgumentException("Unknown voice mode");getSharedPreferences("pocket-ui",0).edit().putString("voiceEngine",value).apply();}
            }
            case "/native/select-provider" -> {
                if(activeWork())throw new IllegalStateException("Stop active work before changing connections");String provider=body.optString("provider");
                if(!java.util.List.of("nebius","opencode","codex-account").contains(provider))throw new IllegalArgumentException("Choose a provider");
                JSONObject profile=credentials.getProviderProfile(provider);boolean configured=!profile.optString(provider.equals("codex-account")?"accessToken":"apiKey").isEmpty();
                invalidateAccountOperations();webAuth=json("state","idle");webModelProofs.clear();
                if(configured){credentials.save(profile);if(AgentService.isRunning())command(json("action","configure"));}
                reply.complete(json("configured",configured,"provider",provider,"model",profile.optString("model"),"reasoning",profile.optString("reasoningEffort","low")),null);return;
            }
            case "/native/provider" -> {
                if(activeWork())throw new IllegalStateException("Stop active work before changing connections");
                JSONObject config=credentials.get();String provider=body.optString("provider");
                if(!java.util.List.of("nebius","opencode","codex-account").contains(provider))throw new IllegalArgumentException("Choose a provider");
                if(!provider.equals(config.optString("authMode")))throw new IllegalStateException("Connect this provider first");
                // Match native Settings: an existing account model change is not an import.
                config=webModelSettings(config);
                config.put("preferredReasoningEffort",body.optString("reasoning","low"));
                config.put("model",body.optString("model"));config.put("reasoningEffort",body.optString("reasoning","low"));config.put("modelSource",body.has("supportedReasoningLevels")?"catalog":"manual");
                if(body.has("supportedReasoningLevels")){JSONArray proof=webModelProofs.get(body.optString("model"));if(proof==null)throw new IllegalStateException("Discover models again before saving this reasoning choice");config.put("reasoningEffortModel",body.optString("model"));config.put("reasoningEfforts",proof);}
                if(!provider.equals("codex-account")){validateWebConnection(config,reply);return;}
                credentials.save(config);if(AgentService.isRunning())command(json("action","configure"));
            }
            case "/api/provider/key" -> {
                if(activeWork())throw new IllegalStateException("Stop active work before changing connections");String provider=body.optString("provider");if(!provider.equals("nebius")&&!provider.equals("opencode"))throw new IllegalArgumentException("Choose a key provider");
                JSONObject draft=json("authMode",provider,"apiKey",body.optString("apiKey"),"model",body.optString("model",provider.equals("nebius")?"zai-org/GLM-5.3-Flash":"big-pickle"));
                validateWebConnection(draft,reply);return;
            }
            case "/api/provider/disconnect" -> {
                if(activeWork())throw new IllegalStateException("Stop active work before disconnecting");invalidateAccountOperations();webAuth=json("state","idle");
                if(body.optString("provider").equals("codex-account")){boolean shared=credentials.signOutCodex();if(shared)stopService(new Intent(this,SubscriptionVoiceService.class));}
                else credentials.removeKeyProvider(body.optString("provider"));
                if(AgentService.isRunning())command(json("action","configure"));
            }
            case "/api/auth/codex/start" -> { if(activeWork())throw new IllegalStateException("Stop active work before signing in");webSignIn(body.optBoolean("voiceOnly")); }
            case "/api/auth/codex/cancel" -> {invalidateAccountOperations();webAuth=json("state","idle");}
            case "/api/auth/codex/import" -> {if(activeWork())throw new IllegalStateException("Stop active work before importing");JSONObject account=CodexAccountAuth.readImport(new java.io.ByteArrayInputStream(body.getJSONObject("document").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));invalidateAccountOperations();saveAccount(account);webAuth=json("state","idle");}
            case "/api/provider/models", "/native/mcp/discover" -> {
                if(path.equals("/api/provider/models")&&!body.optString("provider",credentials.get().optString("authMode")).equals(credentials.get().optString("authMode")))throw new IllegalStateException("Connect the selected provider before discovering models");
                String id="web-"+System.nanoTime();webRequests.put(id,reply);
                command(path.equals("/api/provider/models")?json("action","list_models","requestId",id):json("action","mcp_discover","requestId",id,"serverId",body.optString("id")));
                pocketUi.view.postDelayed(()->{PocketWebUi.Reply waiting=webRequests.remove(id);if(waiting!=null)waiting.complete(null,"Discovery timed out. Try again.");},45000);return;
            }
            case "/api/voice/key" -> new VoiceCredentialStore(this,body.optString("engine")).save(body.optString("apiKey"));
            case "/api/voice/disconnect" -> {String engine=body.optString("engine");if(!java.util.List.of("groq","deepgram").contains(engine))throw new IllegalArgumentException("Unknown voice provider");getSharedPreferences(engine.equals("deepgram")?"voice-credentials":"groq-voice-credentials",0).edit().remove("secret").commit();}
            case "/native/voice" -> {String engine=getSharedPreferences("pocket-ui",0).getString("voiceEngine","system");if((engine.equals("groq")||engine.equals("deepgram"))&&!new VoiceCredentialStore(this,engine).configured())throw new IllegalStateException("Save your "+(engine.equals("groq")?"Groq":"Deepgram")+" key in Voice settings first");startDictation();}
            case "/native/dictation" -> {String engine=getSharedPreferences("pocket-ui",0).getString("voiceEngine","system");startDictation(engine.equals("codex")?"system":engine);}
            case "/native/live-voice" -> {if(credentials.getVoiceCodex().optString("accessToken").isEmpty())throw new IllegalStateException("Connect a ChatGPT voice account in Voice settings");toggleVoiceConversation();}
            case "/native/voice/finish" -> {if(groqCapture!=null)groqCapture.finish();}
            case "/native/voice/stop" -> {if(groqCapture!=null){groqCapture.close();groqCapture=null;}stopService(new Intent(this,SubscriptionVoiceService.class));stopService(new Intent(this,VoiceListeningService.class));}
            case "/native/voice/disconnect-account" -> {if(activeWork())throw new IllegalStateException("Stop active work before disconnecting voice");invalidateAccountOperations();webAuth=json("state","idle");credentials.signOutVoiceCodex();VoiceConversationInvitation.cancel(this,"voice_account_signed_out");stopService(new Intent(this,SubscriptionVoiceService.class));}
            case "/native/action" -> webPhoneAction(body.optString("action"));
            case "/native/autonomy" -> {boolean enabled=body.optBoolean("enabled");AutonomyStore.requestEnabled(this,enabled,error->runOnUiThread(()->pocketUi.event("notice",json("text","Could not update background work"))));if(!enabled){RuntimeHost runtime=RuntimeOwner.peekHost();if(runtime!=null)runtime.dispatch(json("action","stop_autonomy"));}}
            case "/native/schedule" -> {String action=body.optString("action");if(!action.equals("pause")&&!action.equals("resume"))throw new IllegalArgumentException("Invalid schedule action");command(json("action","automation_control","tool","schedule","args",json("action",action,"id",body.optString("id"))));}
            case "/native/worker" -> {String action=switch(body.optString("action")){case "steer"->"steer_worker";case "cancel"->"cancel_worker";case "inspect"->"inspect_worker";case "message"->"send";default->throw new IllegalArgumentException("Invalid worker action");};command(json("action",action,"conversationId",body.optString("id"),"text",body.optString("text")));}
            case "/native/mcp/save" -> {if(activeWork())throw new IllegalStateException("Stop active work before changing connections");credentials.saveMcpServer(body);if(AgentService.isRunning())command(json("action","mcp_reload"));}
            case "/native/mcp/delete" -> {if(activeWork())throw new IllegalStateException("Stop active work before changing connections");credentials.deleteMcpServer(body.optString("id"));if(AgentService.isRunning())command(json("action","mcp_reload"));}
            case "/native/response" -> VoiceConversationInvitation.setMode(this,body.optString("mode"));
            case "/native/decision" -> {String id=body.optString("id");try(AutonomyStore automation=new AutonomyStore(this)){automation.incoming(json("id","decision:"+id,"type","decision","decisionId",id,"approved",body.optBoolean("approved"),"answer",body.optString("answer")));}}
            default -> throw new IllegalArgumentException("Unknown phone action");
        }
        replyWebStatus(reply);
    }
    private void replyWebStatus(PocketWebUi.Reply reply) {
        uiReads.submit(()->{try {reply.complete(webStatus(),null);}
            catch(Exception failure){reply.complete(null,"Could not read connection status. Try again.");}});
    }
    private void webPhoneAction(String action) {
        switch(action) {
            case "permissions" -> permissions();case "microphone" -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},111);case "accessibility" -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            case "notifications" -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));case "displays" -> enableVirtualDisplays();
            case "assistant" -> chooseAssistant();case "wake-on" -> enableWakeWord();case "wake-off" -> startService(new Intent(this,WakeWordService.class).setAction(WakeWordService.ACTION_DISABLE));
            case "battery" -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            case "exact-alarms" -> startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));
            case "advanced" -> {showAllSettings=true;settings();}
            case "activity-advanced" -> autonomyActivity();
            case "stop-all" -> {AutonomyStore.requestEnabled(this,false,error->runOnUiThread(()->toast("Could not pause automation")));RuntimeHost runtime=RuntimeOwner.peekHost();if(runtime!=null)runtime.dispatch(json("action","stop_all"));if(AgentService.isRunning())startService(new Intent(this,AgentService.class).setAction(AgentService.ACTION_STOP));}
            default -> throw new IllegalArgumentException("Unknown phone action");
        }
    }
    private void validateWebConnection(JSONObject draft,PocketWebUi.Reply reply) throws Exception {
        long generation=invalidateAccountOperations();String id="connection-"+System.nanoTime();
        CredentialStore credentials=new CredentialStore(this);JSONObject previous=credentials.getProviderProfile(draft.optString("authMode"));
        if(!previous.optString("apiKey").isEmpty()&&matchesSavedConnection(previous,draft.optString("authMode"),draft.optString("endpoint",previous.optString("endpoint")),draft.optString("apiKey"))){
            credentials.save(draft);webAuth=json("state","idle");webModelProofs.clear();if(AgentService.isRunning())command(json("action","configure"));reply.complete(webStatus(),null);return;
        }
        webRequests.put(id,(result,error)->{
            if(error!=null){reply.complete(null,error+" Settings were not saved.");return;}
            if(generation!=accountGeneration||activeWork()){reply.complete(null,"Connection settings changed during the test. Try again.");return;}
            try {
                new CredentialStore(this).save(draft);webAuth=json("state","idle");webModelProofs.clear();
                command(json("action","configure"));reply.complete(webStatus(),null);
            }catch(Exception failure){reply.complete(null,"Could not save the tested connection.");}
        });
        command(json("action","validate_connection","requestId",id,"credentials",draft));
        pocketUi.view.postDelayed(()->{PocketWebUi.Reply pending=webRequests.remove(id);if(pending!=null)pending.complete(null,"Connection test timed out. Settings were not saved.");},75000);
    }
    private void webSignIn(boolean voiceOnly) {
        long generation=invalidateAccountOperations();webAuth=json("state","starting");CodexAccountAuth flow=new CodexAccountAuth();accountFlow=flow;
        flow.signIn(new CodexAccountAuth.Listener(){
            public void onDeviceCode(String code){if(generation==accountGeneration)webAuth=json("state","pending","userCode",code,"verificationUri",CodexAccountAuth.VERIFICATION_URI);}
            public void onSuccess(JSONObject account){if(generation!=accountGeneration||isDestroyed())return;try{if(voiceOnly)saveVoiceAccount(account);else saveAccount(account);webAuth=json("state","idle");}catch(Exception failure){webAuth=json("state","error","error","Could not save this account. Stop active work and try again.");}flow.close();}
            public void onFailure(String message){if(generation==accountGeneration)webAuth=json("state","error","error",message);flow.close();}
        });
    }
    private void navigateBack(){if(pocketUi!=null)pocketUi.view.evaluateJavascript("window.pocketBack?.()",null);else finish();}
    private void conversationActions() {
        String[] labels = {"New chat", "Copy conversation", "Search saved conversations", "Stop response", "Stop all work", "Stop assistant listening", "Voice modes"};
        new AlertDialog.Builder(this).setTitle("Conversation").setItems(labels, (dialog, which) -> {
            switch (which) {
                case 0 -> newMainThread(); case 1 -> copyConversation(); case 2 -> searchHistory(); case 3 -> command(json("action", "stop_main"));
                case 4 -> { AutonomyStore.requestEnabled(this, false, error -> runOnUiThread(() -> toast("Could not pause automation"))); RuntimeHost runtime = RuntimeOwner.peekHost(); if (runtime != null) runtime.dispatch(json("action", "stop_all")); if (AgentService.isRunning()) startService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_STOP)); }
                case 5 -> stopService(new Intent(this, VoiceListeningService.class)); case 6 -> showVoiceChoices(); default -> { }
            }
        }).setNegativeButton("Close", null).show();
    }
    private android.app.Dialog phonePage(String title, java.util.function.Consumer<LinearLayout> content) {
        android.app.Dialog dialog = new android.app.Dialog(this, R.style.PocketTheme); LinearLayout root = column(); root.setPadding(dp(20), dp(16), dp(20), dp(16)); root.setBackgroundColor(getColor(R.color.pocket_canvas));
        root.setOnApplyWindowInsetsListener((view, insets) -> { android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime()); view.setPadding(dp(20) + bars.left, dp(16) + bars.top, dp(20) + bars.right, dp(16) + bars.bottom); return insets; });
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL); header.addView(iconControl("back", "Back", dialog::dismiss)); TextView heading = text(title); heading.setTextSize(22); heading.setPadding(dp(12), 0, 0, 0); header.addView(heading); root.addView(header);
        ScrollView scroll = new ScrollView(this); LinearLayout body = column(); body.setPadding(0, dp(20), 0, dp(20)); scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); content.accept(body); dialog.setContentView(root); dialog.show(); if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1); return dialog;
    }
    private void settingsRow(LinearLayout parent, String title, String detail, Runnable action) {
        LinearLayout card = activityCard(parent); card.setMinimumHeight(dp(72)); card.addView(text(title)); if (!detail.isEmpty()) card.addView(activityMeta(detail)); card.setFocusable(true); card.setOnClickListener(view -> action.run());
    }
    private void settingsHome() {
        phonePage("Settings", body -> {
            String name = getSharedPreferences("pocket-ui", 0).getString("name", "Your Pocket"); settingsRow(body, name.isBlank() ? "Your Pocket" : name, "Personal preferences", () -> phonePage("Your profile", profile -> { EditText value = field(profile, "Your name", name.equals("Your Pocket") ? "" : name); profile.addView(button("Save name", () -> { getSharedPreferences("pocket-ui", 0).edit().putString("name", value.getText().toString().trim()).apply(); toast("Profile saved"); })); }));
            activitySection(body, "Your assistant"); settingsRow(body, "AI & model", "Provider connection, sign-in, models & reasoning", this::settings);
            settingsRow(body, "Personal instructions", "Tone, priorities and preferences", () -> phonePage("Personal instructions", page -> { EditText value = field(page, "Instructions for Pocket", state.optString("personalInstructions")); value.setSingleLine(false); value.setMinLines(6); value.setGravity(Gravity.TOP); page.addView(button("Save instructions", () -> { String prompt = value.getText().toString().trim(); if (prompt.length() > 50000) { toast("Instructions must be 50000 characters or fewer"); return; } command(json("action", "configure", "personalInstructions", prompt)); toast("Instructions saved"); })); }));
            settingsRow(body, "Voice & wake word", "System speech, Groq Whisper, Deepgram & Codex voice", this::voiceSettingsPage);
            activitySection(body, "On your phone"); settingsRow(body, "Phone access", "Permissions, Accessibility & virtual displays", this::phoneAccessPage); settingsRow(body, "Connections", "Remote MCP servers & tool discovery", this::mcpSettings); settingsRow(body, "Background work", "Schedules, approvals and run history", this::autonomyActivity);
            activitySection(body, "Appearance"); Spinner theme = new Spinner(this); String[] labels = {"System", "Warm charcoal", "Soft paper"}; String[] values = {"system", "dark", "light"}; theme.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels)); String savedTheme = getSharedPreferences("pocket-ui", 0).getString("theme", "system"); theme.setSelection(java.util.Arrays.asList(values).indexOf(savedTheme)); body.addView(theme); theme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { public void onNothingSelected(AdapterView<?> parent) { } public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { if (!values[position].equals(getSharedPreferences("pocket-ui", 0).getString("theme", "system"))) { getSharedPreferences("pocket-ui", 0).edit().putString("theme", values[position]).apply(); recreate(); } } });
            settingsRow(body, "Advanced settings", "All original connection and voice controls", () -> { showAllSettings = true; settings(); });
        });
    }
    private void showVoiceChoices() {
        String[] labels = {"System dictation", "Groq Whisper dictation", "Codex live voice", "Assistant voice (Deepgram / hybrid)", "Voice settings"};
        new AlertDialog.Builder(this).setTitle("Voice").setItems(labels, (dialog, which) -> {
            if (which == 4) { voiceSettingsPage(); return; }
            String[] engines = {"system", "groq", "codex", "deepgram"}; getSharedPreferences("pocket-ui", 0).edit().putString("voiceEngine", engines[which]).apply(); startDictation();
        }).setNegativeButton("Close", null).show();
    }
    private void voiceSettingsPage() {
        phonePage("Voice & wake word", body -> {
            body.addView(activityMeta("The chat microphone records independently of your AI connection. Hold it to choose a voice mode."));
            String[] labels = {"System dictation", "Groq Whisper", "Assistant voice · Deepgram / hybrid", "Codex live conversation"}; String[] engines = {"system", "groq", "deepgram", "codex"};
            body.addView(text("Microphone mode")); Spinner picker = new Spinner(this); picker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels)); int selected = java.util.Arrays.asList(engines).indexOf(getSharedPreferences("pocket-ui", 0).getString("voiceEngine", "system")); picker.setSelection(Math.max(0, selected)); body.addView(picker); LinearLayout controls = column(); body.addView(controls);
            picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onNothingSelected(AdapterView<?> parent) { }
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    String engine = engines[position]; getSharedPreferences("pocket-ui", 0).edit().putString("voiceEngine", engine).apply(); controls.removeAllViews();
                    if (engine.equals("groq")) { controls.addView(activityMeta("Whisper large v3 turbo · 16 kHz short recordings · review text before sending.")); controls.addView(button("Groq speech key", MainActivity.this::groqKeySettings)); }
                    else if (engine.equals("deepgram")) { controls.addView(activityMeta("Uses the original live assistant pipeline, including interruption and spoken replies.")); controls.addView(button("Deepgram voice key", MainActivity.this::voiceKeySettings)); controls.addView(button("Enable Hey Jarvis", MainActivity.this::enableWakeWord)); controls.addView(button("Disable Hey Jarvis", () -> startService(new Intent(MainActivity.this, WakeWordService.class).setAction(WakeWordService.ACTION_DISABLE)))); }
                    else if (engine.equals("codex")) {
                        try { JSONObject account = new CredentialStore(MainActivity.this).getVoiceCodex(); controls.addView(activityMeta(account.optString("accountId").isEmpty() ? "Voice account not connected" : "Protected ChatGPT voice account connected")); } catch (Exception unavailable) { controls.addView(activityMeta("Protected voice login is unavailable.")); }
                        controls.addView(button("Sign in to ChatGPT for voice", () -> { if (activeWork()) toast("Stop active work before changing voice login"); else signInAccount(true); }));
                        controls.addView(button(SubscriptionVoiceService.isActive() ? "Disconnect Codex voice" : "Start Codex voice", () -> { voicePanel.setVisibility(View.VISIBLE); toggleVoiceConversation(); })); controls.addView(button("Voice invitation preference", MainActivity.this::responsePreference));
                    } else controls.addView(activityMeta("Uses your installed speech recognizer. No Groq or Deepgram key is required."));
                }
            });
            settingsRow(body, "Advanced voice controls", "Hybrid thresholds, calibration, account security & audio troubleshooting", () -> { showAllSettings = true; settings(); });
            settingsRow(body, "Default assistant", "Choose Pocket in Android settings", this::chooseAssistant);
        });
    }
    private void groqKeySettings() {
        EditText value = new EditText(this); value.setHint("Groq speech API key"); value.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); value.setSaveEnabled(false); value.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Groq Whisper connection").setView(value).setNegativeButton("Cancel", null).setPositiveButton("Save key", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> { try { new VoiceCredentialStore(this, "groq").save(value.getText().toString()); value.setText(""); dialog.dismiss(); toast("Groq speech key saved on this phone"); } catch (Exception invalid) { value.setError("Enter a valid Groq API key"); } })); dialog.show(); if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void startGroqDictation() {
        if(pocketUi!=null){
            if(groqCapture!=null)groqCapture.close();
            groqCapture=new GroqSpeechInput(this,new GroqSpeechInput.Listener(){
                public void status(String message){pocketUi.event("voice",json("active",true,"message",message,"groq",true));}
                public void result(String text){pocketUi.event("dictation",json("text",text));pocketUi.event("voice",json("active",false,"message",""));if(groqCapture!=null){groqCapture.close();groqCapture=null;}}
                public void failure(String message){pocketUi.event("voice",json("active",false,"message",message));pocketUi.event("notice",json("text",message));if(groqCapture!=null){groqCapture.close();groqCapture=null;}}
            });groqCapture.start();return;
        }
        try { if (!new VoiceCredentialStore(this, "groq").configured()) { groqKeySettings(); return; } } catch (RuntimeException unavailable) { toast("Re-enter your Groq speech key"); return; }
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(12), dp(20), dp(12)); TextView detail = text("Preparing microphone…"); panel.addView(detail);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Groq Whisper").setView(panel).setNegativeButton("Cancel", null).setPositiveButton("Finish recording", null).create();
        GroqSpeechInput capture = new GroqSpeechInput(this, new GroqSpeechInput.Listener() {
            public void status(String message) { if (!dialog.isShowing()) return; detail.setText(message); if (message.startsWith("Transcribing")) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false); }
            public void result(String text) { if (isDestroyed() || !dialog.isShowing()) return; input.setText(text); input.requestFocus(); dialog.dismiss(); toast("Voice text added. Review it before sending."); }
            public void failure(String message) { if (dialog.isShowing()) { detail.setText(message); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false); } }
        });
        if (groqCapture != null) groqCapture.close(); groqCapture = capture; dialog.setOnDismissListener(ignored -> { capture.close(); if (groqCapture == capture) groqCapture = null; }); dialog.setOnShowListener(ignored -> { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> capture.finish()); capture.start(); }); dialog.show();
    }
    private void openChats() {
        android.app.Dialog drawer = new android.app.Dialog(this, R.style.PocketTheme); LinearLayout panel = column(); panel.setPadding(dp(16), dp(24), dp(16), dp(20)); panel.setBackgroundColor(getColor(R.color.pocket_surface));
        panel.setOnApplyWindowInsetsListener((view, insets) -> { android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime()); view.setPadding(dp(16) + bars.left, dp(16) + bars.top, dp(16) + bars.right, dp(16) + bars.bottom); return insets; });
        LinearLayout header = row(); TextView brand = text("Pocket Agent"); brand.setTextSize(20); header.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1)); header.addView(iconControl("close", "Close sidebar", drawer::dismiss)); panel.addView(header);
        panel.addView(button("New chat", () -> { drawer.dismiss(); newMainThread(); })); EditText search = new EditText(this); search.setSingleLine(true); search.setHint("Search chats"); search.setTextSize(16); search.setPadding(dp(12), dp(12), dp(12), dp(12)); search.setBackground(surface(R.color.pocket_raised, 10)); search.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(300)}); panel.addView(search);
        ScrollView scroll = new ScrollView(this); LinearLayout list = column(); scroll.addView(list); panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        panel.addView(button("Schedules & activity", () -> { drawer.dismiss(); autonomyActivity(); })); panel.addView(button("Connections & tools", () -> { drawer.dismiss(); mcpSettings(); })); panel.addView(button("Settings", () -> { drawer.dismiss(); settingsHome(); }));
        java.util.concurrent.atomic.AtomicInteger request = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<String> load = query -> {
            int generation = request.incrementAndGet(); list.removeAllViews(); list.addView(activityMeta("Reading saved chats…"));
            historyQueries.submit(() -> { JSONArray rows; try (StateStore history = new StateStore(getApplicationContext())) { rows = history.listConversations(query); } catch (RuntimeException failure) { runOnUiThread(() -> { if (drawer.isShowing() && request.get() == generation) { list.removeAllViews(); list.addView(activityMeta("Saved chats could not be read. Your data has not been changed.")); } }); return; }
                runOnUiThread(() -> { if (isDestroyed() || !drawer.isShowing() || request.get() != generation) return; list.removeAllViews();
                    list.addView(button("Current conversation", drawer::dismiss)); JSONArray tasks = state.optJSONArray("tasks"); if (tasks != null && tasks.length() > 0) { activitySection(list, "Workers"); for (int i = 0; i < tasks.length(); i++) { JSONObject task = tasks.optJSONObject(i); if (task == null) continue; String id = task.optString("conversationId"); list.addView(button(task.optString("label", "Worker") + " · " + taskStatus(task.optString("status")), () -> { drawer.dismiss(); worker(id); })); } }
                    activitySection(list, "Saved chats"); if (rows.length() == 0) list.addView(activityMeta(query.isBlank() ? "Your conversations will appear here." : "No saved chats match your search."));
                    for (int i = 0; i < rows.length(); i++) { JSONObject item = rows.optJSONObject(i); if (item == null) continue; String id = item.optString("threadId"), title = item.optString("title", "Saved conversation").replace('\n', ' '); list.addView(button(title, () -> { drawer.dismiss(); if (id.equals(state.optString("mainThreadId"))) messages.fullScroll(View.FOCUS_DOWN); else openArchive(id, title, 0); })); }
                });
            });
        };
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence value, int start, int count, int after) { } public void onTextChanged(CharSequence value, int start, int before, int count) { load.accept(value.toString()); } public void afterTextChanged(Editable value) { } });
        drawer.setContentView(panel); drawer.setOnDismissListener(ignored -> request.incrementAndGet()); drawer.show(); if (drawer.getWindow() != null) { drawer.getWindow().setGravity(Gravity.START); drawer.getWindow().setLayout(Math.min(dp(320), getResources().getDisplayMetrics().widthPixels - dp(48)), -1); drawer.getWindow().setBackgroundDrawableResource(android.R.color.transparent); }
        load.accept("");
    }
    private void openArchive(String threadId, String title, int offset) {
        historyQueries.submit(() -> { JSONObject page; try (StateStore history = new StateStore(getApplicationContext())) { page = history.readConversation(threadId, offset); } catch (RuntimeException unavailable) { runOnUiThread(() -> toast("Could not read this saved conversation")); return; }
            runOnUiThread(() -> { if (isDestroyed()) return; final android.app.Dialog[] current = new android.app.Dialog[1]; current[0] = phonePage(title, body -> { body.addView(activityMeta("Saved conversation · read-only archive. Your current chat stays unchanged.")); JSONArray items = page.optJSONArray("messages"); if (items != null) for (int i = 0; i < items.length(); i++) { JSONObject item = items.optJSONObject(i); if (item == null) continue; LinearLayout card = activityCard(body); card.addView(activityMeta(item.optString("role"))); TextView content = text(item.optString("text")); content.setTextIsSelectable(true); card.addView(content); }
                if (offset > 0) body.addView(button("Earlier messages", () -> { current[0].dismiss(); openArchive(threadId, title, Math.max(0, offset - 60)); })); if (offset + 60 < page.optInt("total")) body.addView(button("More messages", () -> { current[0].dismiss(); openArchive(threadId, title, offset + 60); }));
            }); });
        });
    }
    private void phoneAccessPage() {
        phonePage("Phone access", page -> { settingsRow(page, "Phone permissions", "Contacts, calls, SMS, calendar & notifications", this::permissions); settingsRow(page, "Accessibility", "Read and control the visible screen", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); settingsRow(page, "Virtual displays", "Start Shizuku, then authorize background screen tools", this::enableVirtualDisplays); settingsRow(page, "Notification access", "Allow Pocket to read notifications", () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))); settingsRow(page, "Default assistant", "Use Pocket as your phone assistant", this::chooseAssistant); });
    }
    private void settings() {
        final boolean advanced = showAllSettings; showAllSettings = false;
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
        final int aiStart = panel.getChildCount();
        panel.addView(text("Provider")); Spinner mode = new Spinner(this);
        String[] names = advanced ? new String[]{"Scripted model (local validation)", "API key (HTTPS)", "OpenCode Free (Console key)", "Nebius Token Factory", "Codex emulator relay (development)", "Codex account (ChatGPT)"} : new String[]{"Nebius Token Factory", "OpenCode Free", "Codex account"};
        String[] modes = advanced ? new String[]{"scripted", "openai", CredentialStore.OPENCODE, CredentialStore.NEBIUS, "codex", CredentialStore.CODEX_ACCOUNT} : new String[]{CredentialStore.NEBIUS, CredentialStore.OPENCODE, CredentialStore.CODEX_ACCOUNT};
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        String auth = saved.optString("authMode");
        String normalizedAuth = auth.equals("api-key") ? "openai" : auth.equals("codexRelay") || auth.equals("codex-relay") ? "codex" : auth;
        int selected = java.util.Arrays.asList(modes).indexOf(normalizedAuth); if (selected < 0) selected = modes.length - 1;
        mode.setSelection(selected); panel.addView(mode);
        panel.addView(text("Each provider keeps its own encrypted connection. Switching back reuses saved keys and sign-ins."));
        TextView providerStatus = text(""); panel.addView(providerStatus);
        LinearLayout endpointPanel = column(); panel.addView(endpointPanel);
        String defaultEndpoint = modes[selected].equals(CredentialStore.OPENCODE) ? CredentialStore.OPENCODE_ENDPOINT : modes[selected].equals(CredentialStore.NEBIUS) ? CredentialStore.NEBIUS_ENDPOINT : "https://api.openai.com/v1";
        EditText endpoint = field(endpointPanel, "API endpoint", saved.optString("endpoint", defaultEndpoint)); endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        String defaultModel = modes[selected].equals(CredentialStore.OPENCODE) ? CredentialStore.OPENCODE_MODEL : modes[selected].equals(CredentialStore.NEBIUS) ? CredentialStore.NEBIUS_MODEL : modes[selected].equals(CredentialStore.CODEX_ACCOUNT) ? CredentialStore.CODEX_MODEL : "gpt-4.1-mini";
        EditText model = field(panel, "Main chat model ID (editable fallback)", saved.optString("model", defaultModel));
        panel.addView(text("Reasoning effort"));
        Spinner reasoning = new Spinner(this);
        reasoningPicker = reasoning;
        final JSONObject[] currentSaved = {saved};
        final String[] savedActiveReasoning = {saved.optString("reasoningEffort", CredentialStore.DEFAULT_REASONING_EFFORT)};
        final String[] preferredReasoning = {saved.optString("preferredReasoningEffort", savedActiveReasoning[0])};
        final String[] savedReasoningModel = {saved.optString("reasoningEffortModel")};
        final String[][] savedReasoningLevels = {reasoningEfforts(saved.optJSONArray("reasoningEfforts"))};
        final String[] savedModelSource = {saved.optString("modelSource",
                savedReasoningModel[0].isEmpty() ? "manual" : "catalog")};
        final String[] modelSource = {savedModelSource[0]};
        final boolean[] savedProofApplies = {true};
        final boolean[] changingModel = {false};
        final boolean[] manualModelEdited = {false};
        final String[] sourceBeforeConnectionEdit = {savedModelSource[0]};
        final String[] modelBeforeConnectionEdit = {model.getText().toString().trim()};
        populateReasoningPicker(reasoning, model.getText().toString(), savedActiveReasoning[0],
                modelSource[0].equals("manual"), savedReasoningModel[0], savedReasoningLevels[0]);
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
                            modelReasoningEfforts.get(currentModel), savedReasoningModel[0], savedReasoningLevels[0],
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
                            false, savedProofApplies[0] ? savedReasoningModel[0] : "",
                            savedProofApplies[0] ? savedReasoningLevels[0] : new String[0]);
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
            String apiKey = savedProviderKeyForEndpoint(currentSaved[0], selectedMode,
                    endpoint.getText().toString(), enteredKey);
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
        TextView accountStatusView = text(accountStatus);
        accountPanel.addView(accountStatusView);
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
            JSONObject profile;
            try { profile = credentials.getProviderProfile(selectedMode); }
            catch (Exception error) { toast("Unable to read saved provider connection"); return; }
            currentSaved[0] = profile;
            savedActiveReasoning[0] = profile.optString("reasoningEffort", CredentialStore.DEFAULT_REASONING_EFFORT);
            preferredReasoning[0] = profile.optString("preferredReasoningEffort", savedActiveReasoning[0]);
            savedReasoningModel[0] = profile.optString("reasoningEffortModel");
            savedReasoningLevels[0] = reasoningEfforts(profile.optJSONArray("reasoningEfforts"));
            savedModelSource[0] = profile.optString("modelSource",
                    savedReasoningModel[0].isEmpty() ? "manual" : "catalog");
            boolean sameProvider = selectedMode.equals(profile.optString("authMode"))
                    || (selectedMode.equals("openai") && profile.optString("authMode").equals("api-key"))
                    || (selectedMode.equals("codex") && (profile.optString("authMode").equals("codexRelay")
                            || profile.optString("authMode").equals("codex-relay")));
            if (account) {
                String status = profile.optString("accountId").isEmpty() ? "No account connected"
                        : profile.optLong("expiresAt") <= System.currentTimeMillis()
                                ? "Account saved · access token needs refresh" : "Account connected · access token valid";
                accountStatusView.setText(status);
                providerStatus.setText(profile.optString("accountId").isEmpty()
                        ? "Not signed in · Sign in to use this provider" : "Saved ChatGPT account · ready to reuse");
            } else if (selectedMode.equals("scripted")) providerStatus.setText("Local test provider · no credentials needed");
            else providerStatus.setText(profile.optString("apiKey").isEmpty()
                    ? "No saved key for this provider" : "Saved encrypted API key · ready to reuse");
            endpointPanel.setVisibility(account || fixedProvider ? View.GONE : View.VISIBLE);
            secretPanel.setVisibility(account ? View.GONE : View.VISIBLE);
            accountPanel.setVisibility(account ? View.VISIBLE : View.GONE);
            secret.setHint(openCode ? "OpenCode Console service-account key (required)" : nebius ? "Nebius API key (required)" : "API key / relay token (blank keeps saved value)");
            changingModel[0] = true;
            modelSource[0] = sameProvider ? savedModelSource[0] : "manual";
            manualModelEdited[0] = false;
            if (sameProvider) {
                if (!account) endpoint.setText(profile.optString("endpoint", endpoint.getText().toString()));
                model.setText(profile.optString("model", model.getText().toString()));
            } else if (account) model.setText(CredentialStore.CODEX_MODEL);
            else if (openCode) { endpoint.setText(CredentialStore.OPENCODE_ENDPOINT); model.setText(CredentialStore.OPENCODE_MODEL); }
            else if (nebius) { endpoint.setText(CredentialStore.NEBIUS_ENDPOINT); model.setText(CredentialStore.NEBIUS_MODEL); }
            else if (selectedMode.equals("codex")) { endpoint.setText("http://10.0.2.2:8787/v1"); model.setText(CredentialStore.CODEX_MODEL); }
            else if (selectedMode.equals("openai")) { endpoint.setText("https://api.openai.com/v1"); model.setText("gpt-4.1-mini"); }
            else { endpoint.setText("https://api.openai.com/v1"); model.setText("scripted"); }
            changingModel[0] = false;
            boolean sameConnection = matchesSavedConnection(profile, selectedMode,
                    endpoint.getText().toString(), secret.getText().toString());
            savedProofApplies[0] = sameConnection;
            if (!sameConnection) modelSource[0] = "manual";
            sourceBeforeConnectionEdit[0] = modelSource[0];
            modelBeforeConnectionEdit[0] = model.getText().toString().trim();
            picker.setEnabled(sameConnection);
            String pickerPreference = sameProvider ? savedActiveReasoning[0] : preferredReasoning[0];
            populateReasoningPicker(reasoning, model.getText().toString(), pickerPreference,
                    modelSource[0].equals("manual"), sameConnection ? savedReasoningModel[0] : "",
                    sameConnection ? savedReasoningLevels[0] : new String[0]);
        };
        final int[] lastProvider = {selected};
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != lastProvider[0]) {
                    String previousMode = modes[lastProvider[0]];
                    String currentModel = model.getText().toString().trim();
                    preferredReasoning[0] = reasoningPreferenceAfterSelection(previousMode, currentModel,
                            selectedReasoningEffort(reasoning), preferredReasoning[0],
                            modelReasoningEfforts.get(currentModel), savedReasoningModel[0], savedReasoningLevels[0],
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
                boolean sameConnection = matchesSavedConnection(currentSaved[0], modes[mode.getSelectedItemPosition()],
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
                        sameConnection ? savedReasoningModel[0] : "", sameConnection ? savedReasoningLevels[0] : new String[0]);
            }
        };
        endpoint.addTextChangedListener(connectionChanges);
        secret.addTextChangedListener(connectionChanges);
        final int aiEnd = panel.getChildCount();
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
        if (!advanced) { java.util.ArrayList<View> connectionViews = new java.util.ArrayList<>(); for (int child = aiStart; child < aiEnd; child++) connectionViews.add(panel.getChildAt(child)); panel.removeAllViews(); for (View child : connectionViews) panel.addView(child); }
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(advanced ? "Advanced connections & voice" : "AI & model").setView(scroll).setNegativeButton("Close", null).setPositiveButton("Save", null).create(); settingsDialog[0] = dialog;
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
                            savedReasoningModel[0], savedReasoningLevels[0]);
                    String[] verifiedLevels = modelSource[0].equals("manual") ? null
                            : advertised != null ? advertised : persistedModelProof ? savedReasoningLevels[0] : null;
                    preferredReasoning[0] = reasoningPreferenceAfterSelection(selectedMode, selectedModel,
                            selectedEffort, preferredReasoning[0], advertised,
                            savedReasoningModel[0], savedReasoningLevels[0],
                            modelSource[0].equals("manual") && (manualModelEdited[0]
                                    || !selectedEffort.equals(renderedReasoningEffort)));
                    JSONObject config = json("authMode", selectedMode, "model", selectedModel, "modelSource", modelSource[0], "reasoningEffort", selectedEffort,
                            "preferredReasoningEffort", preferredReasoning[0]);
                    config.put("reasoningEffortModel", modelSource[0].equals("catalog") ? selectedModel : "");
                    config.put("reasoningEfforts", reasoningEffortsJson(verifiedLevels));
                    if (!selectedMode.equals(CredentialStore.CODEX_ACCOUNT)) {
                        config.put("endpoint", endpoint.getText().toString().trim());
                        // Blank keys resolve only against the selected provider's encrypted profile.
                        config.put("apiKey", secret.getText().toString().trim());
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
                    JSONObject testConfig = new JSONObject(config.toString());
                    testConfig.put("apiKey", savedProviderKeyForEndpoint(currentSaved[0], selectedMode,
                            endpoint.getText().toString(), secret.getText().toString()));
                    command(Json.obj("action", "validate_connection", "requestId", connectionTestId, "credentials", testConfig));
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
        if(request==64001&&pocketUi!=null){pocketUi.fileResult(result,data);return;}
        if (request == DICTATE_MESSAGE) {
            if (result == RESULT_OK && data != null) {
                java.util.ArrayList<String> lines = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
                if (lines != null && !lines.isEmpty()) { input.setText(lines.get(0)); input.requestFocus(); toast("Voice text added. Review it before sending."); }
            }
            return;
        }
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
    private void startDictation() {
        startDictation(getSharedPreferences("pocket-ui",0).getString("voiceEngine","system"));
    }
    private void startDictation(String engine) {
        if (engine.equals("codex")) { voicePanel.setVisibility(View.VISIBLE); toggleVoiceConversation(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingDictationEngine=engine;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 110); return;
        }
        if (engine.equals("groq")) { startGroqDictation(); return; }
        if (engine.equals("system")) {
            try {
                startActivityForResult(new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to Pocket"), DICTATE_MESSAGE);
            } catch (android.content.ActivityNotFoundException unavailable) { toast("No system speech recognizer is installed. Choose another speech engine in settings."); }
            return;
        }
        try {
            if (!new VoiceCredentialStore(this).configured()) { voiceKeySettings(); return; }
            startForegroundService(new Intent(this, VoiceListeningService.class).setAction(VoiceListeningService.ACTION_START));
        } catch (RuntimeException unavailable) { toast("Unable to start voice. Check your voice key and microphone permission."); }
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
        if (requestCode == 110) {
            if (results.length != 0 && results[0] == PackageManager.PERMISSION_GRANTED) startDictation(pendingDictationEngine);
            else toast("Microphone permission is required for voice");
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
    private static JSONObject json(Object... values) { return Json.obj(values); }
    private CharSequence styledTranscript(String value, JSONArray messageItems) {
        android.text.SpannableString styled = new android.text.SpannableString(value);
        if (messageItems == null || messageItems.length() == 0) {
            int end = value.indexOf("\n\n");
            if (end > 0) {
                styled.setSpan(new android.text.style.TypefaceSpan("serif"), 0, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new android.text.style.AbsoluteSizeSpan(30, true), 0, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int secondLine = value.indexOf('\n') + 1;
                styled.setSpan(new android.text.style.StyleSpan(Typeface.ITALIC), secondLine, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.pocket_muted)), secondLine, value.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return styled;
    }
    private android.graphics.drawable.Drawable surface(int color, int radius) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(getColor(color)); shape.setCornerRadius(dp(radius));
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(getColor(R.color.pocket_line)), shape, null);
    }
    private TextView text(String value) { TextView view = new TextView(this); view.setText(value); view.setTextSize(16); view.setTextColor(getColor(R.color.pocket_ink)); return view; }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private Button button(String label, Runnable action) { Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(12); button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); button.setTextColor(getColor(R.color.pocket_ink)); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(12), dp(8), dp(12), dp(8)); button.setBackground(surface(R.color.pocket_raised, 10)); button.setOnClickListener(ignored -> action.run()); return button; }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
