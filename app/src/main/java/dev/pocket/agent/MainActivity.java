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
import android.text.InputType;
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
public final class MainActivity extends Activity implements AgentService.Observer {
    private TextView status, transcript, workerTranscript;
    private ScrollView messages;
    private EditText input;
    private LinearLayout taskList;
    private Button send, jumpToBottom;
    private JSONObject state = new JSONObject();
    private String viewedWorker;
    private String deferredUserMessage;
    private Spinner modelPicker;
    private Spinner reasoningPicker;
    private String modelRequestId;
    private String mcpRequestId;
    private String[] modelIds = {};
    private final java.util.Map<String, String[]> modelReasoningEfforts = new java.util.HashMap<>();
    private final java.util.Map<String, String> modelDefaultReasoningEfforts = new java.util.HashMap<>();
    private static final String[] REASONING_EFFORTS = {"minimal", "low", "medium", "high", "xhigh", "max"};
    private static final String[] DEFAULT_REASONING_CHOICES = {"minimal", "low", "medium", "high", "xhigh"};
    private static final String[] CODEX_REASONING_CHOICES = {"low", "medium", "high", "xhigh"};
    private StateStore store;
    private static final int IMPORT_CODEX = 90;
    private final ExecutorService accountFiles = Executors.newSingleThreadExecutor();
    private CodexAccountAuth accountFlow;
    private AlertDialog accountDialog;
    private long accountGeneration;
    private long pendingPickerGeneration = -1;
    private int pendingPickerRequest = -1;
    private Future<?> pendingImport;
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
        toolbar.addView(button("Settings", this::settings)); root.addView(toolbar);
        status = text("Stopped · Send starts the runtime"); root.addView(status);
        LinearLayout historyActions = row();
        historyActions.addView(button("New main thread", this::newMainThread), new LinearLayout.LayoutParams(0, -2, 1));
        historyActions.addView(button("Copy history", this::copyHistory), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(historyActions);
        FrameLayout transcriptFrame = new FrameLayout(this);
        messages = new ScrollView(this); transcript = text(""); transcript.setTextIsSelectable(true); transcript.setPadding(0, dp(12), 0, dp(12)); messages.addView(transcript);
        transcriptFrame.addView(messages, new FrameLayout.LayoutParams(-1, -1));
        jumpToBottom = button("↓", this::jumpToLatestMessage);
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
                command(json("action", "send", "text", value));
                deferredUserMessage = null;
                input.setText("");
            } catch (RuntimeException error) { status.setText("Unable to submit message · try again"); }
        }); controls.addView(send, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(button("Stop response", () -> command(json("action", "stop_main"))));
        controls.addView(button("Stop all", () -> {
            if (AgentService.isRunning()) startService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_STOP));
        })); root.addView(controls);
        root.addView(button("Stop voice listening", () -> startService(new Intent(this,
            VoiceListeningService.class).setAction(VoiceListeningService.ACTION_STOP))));
        setContentView(root);
    }
    @Override protected void onStart() {
        super.onStart(); AgentService.observe(this); state = store.load(); render();
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
    @Override protected void onStop() { AgentService.unobserve(this); super.onStop(); }
    @Override protected void onDestroy() {
        invalidateAccountOperations();
        accountFiles.shutdownNow(); store.close(); super.onDestroy();
    }
    @Override public void onAgentEvent(JSONObject event) {
        String type = event.optString("type");
        if (type.equals("state")) { state = event.optJSONObject("state"); if (state == null) state = new JSONObject(); render(); }
        else if (type.equals("models") && modelPicker != null && event.optString("requestId").equals(modelRequestId)) {
            String failure = event.optString("error");
            if (!failure.isEmpty()) { toast(failure); return; }
            JSONArray models = event.optJSONArray("models");
            if (models == null) { toast("Provider returned no model list"); return; }
            String[] labels = new String[models.length() + 1];
            modelIds = new String[models.length() + 1];
            modelReasoningEfforts.clear();
            modelDefaultReasoningEfforts.clear();
            labels[0] = "Select discovered main model";
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
                    if (!supported.isEmpty()) modelReasoningEfforts.put(id, supported.toArray(new String[0]));
                }
                String defaultEffort = entry == null ? "" : entry.optString("defaultReasoningLevel");
                if (isReasoningEffort(defaultEffort)) modelDefaultReasoningEfforts.put(id, defaultEffort);
            }
            modelPicker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
            modelPicker.setSelection(0);
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
                    command(json("action", "send", "text", deferredUserMessage));
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
            if(follow)jumpToLatestMessage();
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
        for (int i = 0; i < messages.length(); i++) { JSONObject message = messages.optJSONObject(i); if (message == null) continue; text.append(message.optString("role")).append(":\n").append(message.optString("text", message.optString("content"))).append("\n\n"); }
        return text.toString();
    }
    private String workerMessages(String id) {
        JSONObject all = state.optJSONObject("workerMessages"); return formatMessages(all == null ? null : all.optJSONArray(id));
    }
    private int latestMessageScrollY() {
        if (messages == null || messages.getChildCount() == 0) return 0;
        View content = messages.getChildAt(0);
        int viewport = messages.getHeight() - messages.getPaddingTop() - messages.getPaddingBottom();
        return Math.max(0, content.getHeight() - Math.max(0, viewport));
    }
    private void jumpToLatestMessage() {
        if (messages == null) return;
        messages.post(() -> {
            messages.scrollTo(0, latestMessageScrollY());
            messages.postOnAnimation(() -> {
                messages.scrollTo(0, latestMessageScrollY());
                updateJumpToBottom();
            });
        });
    }
    private void updateJumpToBottom() {
        if (jumpToBottom == null || messages == null || messages.getChildCount() == 0) return;
        boolean away = latestMessageScrollY() - messages.getScrollY() > dp(48);
        jumpToBottom.setVisibility(away ? View.VISIBLE : View.GONE);
    }
    private void newMainThread() {
        if (activeWork()) { toast("Finish active work before starting a new main thread"); return; }
        new AlertDialog.Builder(this).setTitle("Start a new main thread?")
            .setMessage("The main chat will start fresh. Saved worker tasks and their histories stay available for debugging and follow-up.")
            .setNegativeButton("Cancel", null).setPositiveButton("Start new thread", (dialog, which) -> {
                if (AgentService.isRunning()) command(json("action", "new_main"));
                else { state = store.requestFreshMain(); render(); toast("New main thread ready"); }
            }).show();
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
            debug.put("mainSystemPrompt", snapshot.optString("mainSystemPrompt"));
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
    private boolean activeWork() { return AgentService.isRunning() && (state.optBoolean("busy") || state.optBoolean("active")); }
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
            String verifiedModel, String[] verifiedLevels) {
        if (mode.equals("openai") || mode.equals("codex")) return selectedEffort;
        if (mode.equals(CredentialStore.CODEX_ACCOUNT)
                && (containsReasoningEffort(advertised, selectedEffort)
                || hasPersistedReasoningProof(selectedModel, selectedEffort, verifiedModel, verifiedLevels)))
            return selectedEffort;
        return preferred;
    }
    private void populateReasoningPicker(Spinner picker, String modelId, String preferred,
            boolean codexAccount, boolean codexLike, String verifiedModel, String[] verifiedLevels) {
        String[] advertised = modelReasoningEfforts.get(modelId);
        String[] fallback = codexLike ? CODEX_REASONING_CHOICES : DEFAULT_REASONING_CHOICES;
        String[] values;
        if (advertised != null && advertised.length > 0) values = advertised;
        else if (codexAccount && modelId.equals(verifiedModel) && verifiedLevels.length > 0) values = verifiedLevels;
        else if (codexAccount) values = new String[]{CredentialStore.DEFAULT_REASONING_EFFORT};
        else values = fallback;
        String[] labels = new String[values.length];
        int selection = -1;
        for (int i = 0; i < values.length; i++) {
            labels[i] = reasoningLabel(values[i]);
            if (values[i].equals(preferred)) selection = i;
        }
        if (selection < 0) {
            String advertisedDefault = modelDefaultReasoningEfforts.get(modelId);
            if (advertisedDefault != null) {
                for (int i = 0; i < values.length; i++) if (advertisedDefault.equals(values[i])) { selection = i; break; }
            }
        }
        if (selection < 0) {
            for (int i = 0; i < values.length; i++) if (CredentialStore.DEFAULT_REASONING_EFFORT.equals(values[i])) { selection = i; break; }
        }
        picker.setTag(values);
        picker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        picker.setSelection(Math.max(0, selection));
    }
    private void settings() {
        CredentialStore credentials = new CredentialStore(this); final JSONObject saved;
        try { saved = credentials.get(); } catch (Exception e) { toast("Unable to read protected credentials"); return; }
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(4), dp(20), dp(8));
        panel.addView(text("Main system prompt"));
        EditText mainPrompt = new EditText(this); mainPrompt.setText(state.optString("mainSystemPrompt")); mainPrompt.setHint("Built-in default will be used if left blank");
        mainPrompt.setMinLines(5); mainPrompt.setMaxLines(12); mainPrompt.setGravity(Gravity.TOP | Gravity.START);
        mainPrompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); panel.addView(mainPrompt);
        panel.addView(text("This controls the main agent only. Worker prompts stay unchanged."));
        panel.addView(button("Reset to built-in default", () -> {
            mainPrompt.setText(""); mainPrompt.setHint("Built-in default selected; tap Save");
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
        populateReasoningPicker(reasoning, model.getText().toString(),
                selected == 5 ? savedActiveReasoning : preferredReasoning[0],
                selected == 5, selected == 4 || selected == 5, savedReasoningModel, savedReasoningLevels);
        panel.addView(reasoning);
        TextView reasoningHelp = text("");
        panel.addView(reasoningHelp);
        Spinner picker = new Spinner(this);
        modelPicker = picker;
        picker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Tap Discover models to choose"}));
        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0 && position < modelIds.length && modelIds[position] != null) {
                    String selectedMode = modes[mode.getSelectedItemPosition()];
                    String currentModel = model.getText().toString().trim();
                    preferredReasoning[0] = reasoningPreferenceAfterSelection(selectedMode, currentModel,
                            selectedReasoningEffort(reasoning), preferredReasoning[0],
                            modelReasoningEfforts.get(currentModel), savedReasoningModel, savedReasoningLevels);
                    model.setText(modelIds[position]);
                    String pickerPreference = selectedMode.equals(CredentialStore.CODEX_ACCOUNT)
                            ? selectedReasoningEffort(reasoning) : preferredReasoning[0];
                    populateReasoningPicker(reasoning, modelIds[position], pickerPreference,
                            selectedMode.equals(CredentialStore.CODEX_ACCOUNT),
                            selectedMode.equals("codex") || selectedMode.equals(CredentialStore.CODEX_ACCOUNT),
                            savedReasoningModel, savedReasoningLevels);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        panel.addView(picker);
        final EditText[] newKey = new EditText[1];
        panel.addView(button("Discover models", () -> {
            if (activeWork()) { toast("Finish active work before discovering models"); return; }
            String selectedMode = modes[mode.getSelectedItemPosition()];
            String savedMode = saved.optString("authMode");
            boolean sameMode = selectedMode.equals(savedMode)
                    || (selectedMode.equals("openai") && savedMode.equals("api-key"))
                    || (selectedMode.equals("codex") && (savedMode.equals("codexRelay") || savedMode.equals("codex-relay")));
            boolean fixedProvider = selectedMode.equals(CredentialStore.OPENCODE) || selectedMode.equals(CredentialStore.NEBIUS);
            if (!sameMode
                    || (!selectedMode.equals(CredentialStore.CODEX_ACCOUNT) && !fixedProvider && !endpoint.getText().toString().trim().equals(saved.optString("endpoint")))
                    || (newKey[0] != null && newKey[0].getText().length() > 0)) {
                toast("Save your provider connection, then reopen Settings to discover its models");
                return;
            }
            modelRequestId = Long.toHexString(System.nanoTime());
            command(json("action", "list_models", "requestId", modelRequestId));
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
        accountPanel.addView(button("Sign in", () -> {
            if (activeWork()) { toast("Stop active work before changing accounts"); return; }
            settingsDialog[0].dismiss(); signInAccount();
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
            try { credentials.signOutCodex(); invalidateAccountOperations(); if (AgentService.isRunning()) command(json("action", "configure")); settingsDialog[0].dismiss(); toast("Account signed out on this phone"); }
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
                            ? "Codex account uses only reasoning levels verified for the selected model. A temporary low fallback does not replace your saved preference for other compatible providers."
                            : "Discovered models narrow this list to reasoning levels advertised by the provider. For an editable model ID, support can vary by model."
                    : "This provider does not expose the OpenAI/Codex reasoning-effort control. The saved value is kept for compatible providers.");
            boolean sameProvider = selectedMode.equals(auth)
                    || (selectedMode.equals("openai") && auth.equals("api-key"))
                    || (selectedMode.equals("codex") && (auth.equals("codexRelay") || auth.equals("codex-relay")));
            endpointPanel.setVisibility(account || fixedProvider ? View.GONE : View.VISIBLE);
            secretPanel.setVisibility(account ? View.GONE : View.VISIBLE);
            accountPanel.setVisibility(account ? View.VISIBLE : View.GONE);
            secret.setHint(openCode ? "OpenCode Console service-account key (required)" : nebius ? "Nebius API key (required)" : "API key / relay token (blank keeps saved value)");
            if (sameProvider) {
                if (!account) endpoint.setText(saved.optString("endpoint", endpoint.getText().toString()));
                model.setText(saved.optString("model", model.getText().toString()));
            } else if (account) model.setText(CredentialStore.CODEX_MODEL);
            else if (openCode) { endpoint.setText(CredentialStore.OPENCODE_ENDPOINT); model.setText(CredentialStore.OPENCODE_MODEL); }
            else if (nebius) { endpoint.setText(CredentialStore.NEBIUS_ENDPOINT); model.setText(CredentialStore.NEBIUS_MODEL); }
            else if (selectedMode.equals("codex")) { endpoint.setText("http://10.0.2.2:8787/v1"); model.setText(CredentialStore.CODEX_MODEL); }
            else if (selectedMode.equals("openai")) { endpoint.setText("https://api.openai.com/v1"); model.setText("gpt-4.1-mini"); }
            else { endpoint.setText("https://api.openai.com/v1"); model.setText("scripted"); }
            String pickerPreference = account ? selectedReasoningEffort(reasoning) : preferredReasoning[0];
            populateReasoningPicker(reasoning, model.getText().toString(), pickerPreference,
                    account, account || selectedMode.equals("codex"), savedReasoningModel, savedReasoningLevels);
        };
        final int[] lastProvider = {selected};
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != lastProvider[0]) {
                    String previousMode = modes[lastProvider[0]];
                    String currentModel = model.getText().toString().trim();
                    preferredReasoning[0] = reasoningPreferenceAfterSelection(previousMode, currentModel,
                            selectedReasoningEffort(reasoning), preferredReasoning[0],
                            modelReasoningEfforts.get(currentModel), savedReasoningModel, savedReasoningLevels);
                    secret.setText("");
                    modelRequestId = null;
                    modelIds = new String[0];
                    modelReasoningEfforts.clear();
                    modelDefaultReasoningEfforts.clear();
                    picker.setAdapter(new ArrayAdapter<>(MainActivity.this, android.R.layout.simple_spinner_dropdown_item,
                            new String[]{"Tap Discover models to choose"}));
                    picker.setSelection(0);
                }
                lastProvider[0] = position;
                renderProviderFields.accept(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        renderProviderFields.accept(selected);
        if (BuildConfig.DEVELOPMENT_TOOLS) panel.addView(button("Development MCP and ngrok", () ->
            startActivity(new Intent().setClassName(this, "dev.pocket.agent.DevToolsActivity"))));
        panel.addView(button("Accessibility settings", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        panel.addView(button("Enable Shizuku virtual displays", this::enableVirtualDisplays));
        panel.addView(button("Notification access settings", () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        panel.addView(button("Choose phone permissions", this::permissions));
        panel.addView(button("Remote MCP servers", this::mcpSettings));
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
                    boolean persistedModelProof = hasPersistedReasoningModelProof(selectedModel,
                            savedReasoningModel, savedReasoningLevels);
                    String[] verifiedLevels = advertised != null && advertised.length > 0
                            ? advertised : persistedModelProof ? savedReasoningLevels : null;
                    preferredReasoning[0] = reasoningPreferenceAfterSelection(selectedMode, selectedModel,
                            selectedEffort, preferredReasoning[0], advertised,
                            savedReasoningModel, savedReasoningLevels);
                    JSONObject config = json("authMode", selectedMode, "model", selectedModel, "reasoningEffort", selectedEffort,
                            "preferredReasoningEffort", preferredReasoning[0]);
                    if (selectedMode.equals(CredentialStore.CODEX_ACCOUNT)) {
                        if (!CredentialStore.DEFAULT_REASONING_EFFORT.equals(selectedEffort)
                                && !containsReasoningEffort(verifiedLevels, selectedEffort)) {
                            toast("Discover this model before using that reasoning effort");
                            return;
                        }
                        if (verifiedLevels != null) {
                            config.put("reasoningEffortModel", selectedModel);
                            config.put("reasoningEfforts", reasoningEffortsJson(verifiedLevels));
                        }
                    }
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
                    if (prompt.length() > 50000) { toast("Main system prompt must be 50000 characters or fewer"); return; }
                    credentials.save(config); invalidateAccountOperations();
                    if (AgentService.isRunning()) {
                        JSONObject configure = json("action", "configure");
                        if (prompt.isEmpty()) configure.put("resetSystemPrompt", true); else configure.put("systemPrompt", prompt);
                        command(configure);
                    } else {
                        store.saveMainSystemPrompt(prompt); state = store.load(); render();
                    }
                    dialog.dismiss(); toast("Settings saved");
                } catch (Exception error) { toast(error instanceof IllegalArgumentException ? error.getMessage() : "Could not save connection"); }
            });
        });
        dialog.setOnDismissListener(ignored -> {
            if (modelPicker == picker) {
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
    private void signInAccount() {
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
                try { saveAccount(account); dialog.dismiss(); toast("Codex account connected"); settings(); }
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

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
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
