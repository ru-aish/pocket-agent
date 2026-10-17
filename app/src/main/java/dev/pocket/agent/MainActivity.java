package dev.pocket.agent;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.app.AlertDialog;
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
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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
    private Button send;
    private JSONObject state = new JSONObject();
    private String viewedWorker;
    private String deferredUserMessage;
    private Spinner modelPicker;
    private String modelRequestId;
    private String[] modelIds = {};
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
        messages = new ScrollView(this); transcript = text(""); transcript.setTextIsSelectable(true); transcript.setPadding(0, dp(12), 0, dp(12)); messages.addView(transcript);
        root.addView(messages, new LinearLayout.LayoutParams(-1, 0, 1));
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
            labels[0] = "Select discovered main model";
            for (int i = 0; i < models.length(); i++) {
                JSONObject entry = models.optJSONObject(i);
                String id = entry == null ? "" : entry.optString("id");
                modelIds[i + 1] = id;
                String name = entry == null ? id : entry.optString("name", id);
                labels[i + 1] = name.equals(id) ? id : name + " (" + id + ")";
            }
            modelPicker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
            modelPicker.setSelection(0);
            toast("Discovered " + models.length() + " models");
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
            if(follow)messages.post(()->messages.fullScroll(View.FOCUS_DOWN));
        }
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
    private boolean activeWork() { return state.optBoolean("busy") || state.optBoolean("active"); }
    private void settings() {
        CredentialStore credentials = new CredentialStore(this); final JSONObject saved;
        try { saved = credentials.get(); } catch (Exception e) { toast("Unable to read protected credentials"); return; }
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(4), dp(20), dp(8));
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
        Spinner picker = new Spinner(this);
        modelPicker = picker;
        picker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Tap Discover models to choose"}));
        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0 && position < modelIds.length && modelIds[position] != null)
                    model.setText(modelIds[position]);
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
        };
        final int[] lastProvider = {selected};
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != lastProvider[0]) secret.setText("");
                lastProvider[0] = position;
                renderProviderFields.accept(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        renderProviderFields.accept(selected);
        panel.addView(button("Accessibility settings", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        panel.addView(button("Enable Shizuku virtual displays", this::enableVirtualDisplays));
        panel.addView(button("Notification access settings", () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        panel.addView(button("Choose phone permissions", this::permissions));
        panel.addView(text("Voice uses Deepgram streaming speech recognition and synthesis with the Pi main agent. Listening stops after three seconds of idle silence or when you tap Stop listening in the notification. Background workers continue."));
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
        panel.addView(text("Optional calibration tunes the phrase detector; it does NOT authenticate your identity. Deepgram and the agent start only after detection."));
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
                    JSONObject config = json("authMode", selectedMode, "model", model.getText().toString().trim());
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
                    credentials.save(config); invalidateAccountOperations(); if (AgentService.isRunning()) command(json("action", "configure")); dialog.dismiss(); toast("Connection saved");
                } catch (Exception error) { toast(error instanceof IllegalArgumentException ? error.getMessage() : "Could not save connection"); }
            });
        });
        dialog.setOnDismissListener(ignored -> {
            if (modelPicker == picker) { modelPicker = null; modelRequestId = null; modelIds = new String[0]; }
        });
        dialog.show();
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
