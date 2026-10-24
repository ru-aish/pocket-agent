package dev.pocket.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.util.Log;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArraySet;
import org.json.JSONObject;

/** User-started foreground ownership survives Activity destruction, without a wake lock. */
public final class AgentService extends Service {
    public static final String ACTION_START = "dev.pocket.agent.START";
    public static final String ACTION_COMMAND = "dev.pocket.agent.COMMAND";
    public static final String ACTION_STOP = "dev.pocket.agent.STOP";
    public interface Observer { void onAgentEvent(JSONObject event); }
    private static final CopyOnWriteArraySet<Observer> observers = new CopyOnWriteArraySet<>();
    private static volatile AgentService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private RuntimeHost host;
    private volatile boolean stopping;
    private java.util.concurrent.CompletableFuture<Void> startup;
    public static void observe(Observer observer) { observers.add(observer); }
    public static void unobserve(Observer observer) { observers.remove(observer); }
    public static boolean isRunning() { return instance != null; }
    public static boolean isStopping() { AgentService current = instance; return current != null && current.stopping; }
    static RuntimeHost peekHost() { AgentService current = instance; return current == null ? null : current.host; }
    @Override public void onCreate() {
        super.onCreate(); instance = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("agent", "Agent runtime", NotificationManager.IMPORTANCE_LOW));
        Notification notification = notification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, notification);
        host = new RuntimeHost(this, this::publish); startup = host.start();
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, AgentService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "agent").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Pocket Agent is available").setContentText("User-started runtime · tap to chat").setContentIntent(open)
                .setOngoing(true).addAction(new Notification.Action.Builder(null, "Stop all", stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopping = true;
            WakeWordService.disableImmediately(this);
            stopService(new Intent(this, WakeWordService.class));
            stopService(new Intent(this, VoiceListeningService.class));
            stopService(new Intent(this, SubscriptionVoiceService.class));
            stopSelf(); return START_NOT_STICKY;
        }
        if (stopping) return START_NOT_STICKY;
        if (ACTION_START.equals(intent.getAction()) && host != null && host.terminalFailure()) {
            startup=host.recover();
            startup.exceptionally(error->{Log.w("PocketAgent","Runtime recovery failed after explicit restart request");return null;});
            return START_NOT_STICKY;
        }
        if (ACTION_COMMAND.equals(intent.getAction())) {
            String value = intent.getStringExtra("command");
            try {
                if(host!=null&&host.terminalFailure())startup=host.recover();
                JSONObject command = new JSONObject(value == null ? "{}" : value);
                String voiceSessionId = command.optString("voiceSessionId", "");
                startup.thenCompose(ignored -> host.dispatch(command)).whenComplete((response, failure) -> {
                    if (failure == null && response != null && response.optBoolean("ok")) return;
                    try {
                        JSONObject event = new JSONObject().put("type", voiceSessionId.isEmpty() ? "error" : "voice_error")
                            .put("error", failure == null && response != null ? response.optString("error", "Command rejected") : "Agent unavailable");
                        if (!voiceSessionId.isEmpty()) event.put("sessionId", voiceSessionId);
                        publish(event);
                    } catch (Exception ignored) { }
                });
            }
            catch (Exception e) { try { publish(new JSONObject().put("type", "error").put("error", "Invalid agent command")); } catch (Exception ignored) { } }
        }
        return START_NOT_STICKY;
    }
    static void reportVoiceFailure(Context context, String id, String reason) {
        try {
            JSONObject event = new JSONObject().put("type", "voice_delivery_error")
                .put("id", id).put("error", reason).put("atMs", System.currentTimeMillis());
            try (StateStore store = new StateStore(context)) { store.record(event); }
            catch (Exception persistenceError) { Log.w("PocketVoice", "Could not save speech failure", persistenceError); }
            try {
                NotificationManager notifications = context.getSystemService(NotificationManager.class);
                notifications.createNotificationChannel(new NotificationChannel("agent", "Agent runtime", NotificationManager.IMPORTANCE_LOW));
                notifications.notify(33, new Notification.Builder(context, "agent")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Pocket Agent couldn't speak")
                    .setContentText(reason)
                    .setContentIntent(PendingIntent.getActivity(context, 33, new Intent(context, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                    .setAutoCancel(true).build());
            } catch (RuntimeException notificationError) { Log.w("PocketVoice", "Could not display speech failure notification", notificationError); }
            AgentService running = instance;
            if (running != null && !running.stopping) running.publish(event);
        } catch (Exception error) { Log.w("PocketVoice", "Could not report speech failure", error); }
    }

    private void publish(JSONObject event) {
        main.post(() -> {
            if (stopping && "voice_announce".equals(event.optString("type"))) return;
            if ("voice_announce".equals(event.optString("type"))) {
                try {
                    VoiceAnnouncement request = VoiceAnnouncement.fromEvent(event);
                    startForegroundService(SubscriptionVoiceSettings.announcementIntent(this, request));
                } catch (RuntimeException failure) {
                    Log.w("PocketVoice", "Agent-initiated speech could not be started", failure);
                    reportVoiceFailure(this, event.optString("id"),
                        "Speech service could not start; check Pocket Agent voice permissions");
                }
            }
            for (Observer observer : observers) {
                try {observer.onAgentEvent(event);}
                catch (Throwable failure) {Log.w("PocketAgent","Observer callback failed; continuing other observers",failure);}
            }
        });
    }
    @Override public void onDestroy() {
        stopping = true;
        instance = null; if (host != null) host.close(); stopForeground(STOP_FOREGROUND_REMOVE);
        try { publish(new JSONObject().put("type", "stopped")); } catch (Exception ignored) { }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
