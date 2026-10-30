package dev.pocket.agent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Offline, synthetic-account tests. No invitation test starts a voice session or connects a model. */
@RunWith(AndroidJUnit4.class)
public final class VoiceInvitationDeviceTest {
    private interface Check { void run(Context context, StateStore store) throws Exception; }
    private static final String THREAD = "11111111-2222-4333-8444-555555555555";
    private static JSONObject account() throws Exception {
        String header = encode("{\"alg\":\"RS256\"}");
        String claims = encode(Json.obj("https://api.openai.com/auth", Json.obj("chatgpt_account_id", "invitation-test"),
            "exp", System.currentTimeMillis() / 1000L + 7200).toString());
        return Json.obj("accessToken", header + "." + claims + ".c2ln", "refreshToken", "synthetic-refresh",
            "accountId", "invitation-test", "expiresAt", System.currentTimeMillis() + 3_600_000L);
    }
    private static String encode(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
    private void fixture(Check check) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Invitation fixture requires an idle runtime", AgentService.isRunning());
        SharedPreferences preferences = context.getSharedPreferences("voice_response_preferences", Context.MODE_PRIVATE);
        SharedPreferences secrets = context.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE);
        Map<String, ?> priorPreferences = preferences.getAll();
        String priorSecrets = secrets.getString("encrypted", null);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        boolean hadChannel = manager.getNotificationChannel(VoiceConversationInvitation.CHANNEL) != null;
        boolean microphoneGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notificationsGranted = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        android.app.UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        try (StateStore store = new StateStore(context)) {
            JSONObject durable = store.loadDurable(), snapshot = store.load();
            try {
                assertTrue(preferences.edit().clear().commit());
                new CredentialStore(context).saveVoiceCodex(account());
                store.saveDurable(Json.obj("mainThreadId", THREAD));
                store.save(Json.obj("mainThreadId", THREAD));
                check.run(context, store);
                assertEquals("Invitations must preserve the existing microphone permission", microphoneGranted,
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
            } finally {
                manager.cancel(VoiceConversationInvitation.NOTIFICATION_ID);
                if (!hadChannel) manager.deleteNotificationChannel(VoiceConversationInvitation.CHANNEL);
                store.saveDurable(durable); store.save(snapshot);
                SharedPreferences.Editor restore = preferences.edit().clear();
                for (Map.Entry<String, ?> entry : priorPreferences.entrySet()) restore.putString(entry.getKey(), (String) entry.getValue());
                assertTrue(restore.commit());
                SharedPreferences.Editor restoreSecrets = secrets.edit();
                if (priorSecrets == null) restoreSecrets.remove("encrypted"); else restoreSecrets.putString("encrypted", priorSecrets);
                assertTrue(restoreSecrets.commit());
            }
        } finally {
            if (!notificationsGranted && Build.VERSION.SDK_INT >= 33) ui.revokeRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        }
    }
    private JSONObject request(Context context) throws Exception {
        return VoiceConversationInvitation.request(context, Json.obj("threadId", THREAD, "message", "Would talking help?"));
    }
    private static Notification awaitInvitationNotification(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        long until = SystemClock.elapsedRealtime() + 3_000L;
        while (manager != null && SystemClock.elapsedRealtime() < until) {
            for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications())
                if (item.getId() == VoiceConversationInvitation.NOTIFICATION_ID) return item.getNotification();
            SystemClock.sleep(25L);
        }
        return null;
    }
    @Test public void silentInvitationNeedsNoMicrophoneAndCoalescesRequests() throws Exception {
        fixture((context, store) -> {
            int microphonePermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO);
            JSONObject first = request(context), second = request(context);
            assertEquals("pending", first.getString("status"));
            assertEquals(first.getString("id"), second.getString("id"));
            Notification notice = awaitInvitationNotification(context);
            assertNotNull("The invitation notification should be visible after it is posted", notice);
            assertNull(notice.fullScreenIntent);
            assertNull(notice.sound);
            assertTrue(notice.vibrate == null || notice.vibrate.length == 0);
            assertEquals(0, notice.defaults);
            assertEquals(VoiceConversationInvitation.CHANNEL, notice.getGroup());
            assertEquals(Notification.GROUP_ALERT_SUMMARY, notice.getGroupAlertBehavior());
            assertEquals(0, notice.flags & Notification.FLAG_GROUP_SUMMARY);
            assertNotEquals(Notification.CATEGORY_CALL, notice.category);
            assertEquals(2, notice.actions.length);
            assertEquals("Accept", notice.actions[0].title.toString());
            assertEquals("Decline", notice.actions[1].title.toString());
            assertFalse(SubscriptionVoiceService.isActive());
            assertEquals(microphonePermission, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
            notice.actions[1].actionIntent.send();
            long until = SystemClock.elapsedRealtime() + 3000;
            while ("pending".equals(VoiceConversationInvitation.pending(context).optString("status")) && SystemClock.elapsedRealtime() < until)
                SystemClock.sleep(30);
            assertEquals("declined", VoiceConversationInvitation.pending(context).getString("status"));
            assertEquals("declined", request(context).getString("status"));
            assertFalse(SubscriptionVoiceService.isActive());
        });
    }
    @Test public void acceptanceIsSingleUseAndRejectsChangedThreadsAndExpiredRequests() throws Exception {
        fixture((context, store) -> {
            String id = request(context).getString("id");
            assertFalse(VoiceConversationInvitation.accept(context, "wrong-id"));
            assertTrue(VoiceConversationInvitation.canAccept(context, id));
            assertTrue(VoiceConversationInvitation.accept(context, id));
            assertFalse(VoiceConversationInvitation.accept(context, id));
            // A receipt alone never starts audio; the notification Activity does that after permissions.
            assertFalse(SubscriptionVoiceService.isActive());
            String next = request(context).getString("id");
            VoiceConversationInvitation.decline(context, id); // Stale notification cannot decline the replacement.
            assertEquals("pending", VoiceConversationInvitation.pending(context).getString("status"));
            store.saveDurable(Json.obj("mainThreadId", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"));
            assertFalse(VoiceConversationInvitation.accept(context, next));
            store.saveDurable(Json.obj("mainThreadId", THREAD));
            JSONObject expired = VoiceConversationInvitation.pending(context).put("expiresAt", System.currentTimeMillis() - 1);
            assertTrue(context.getSharedPreferences("voice_response_preferences", Context.MODE_PRIVATE).edit()
                .putString("invitation", expired.toString()).commit());
            assertEquals("expired", VoiceConversationInvitation.preferences(context).getString("invitationStatus"));
            assertFalse(VoiceConversationInvitation.accept(context, next));
        });
    }
    @Test public void failedStartPreservesInvitationForRetry() throws Exception {
        fixture((context, store) -> {
            String id = request(context).getString("id");
            try {
                VoiceConversationInvitation.startAccepted(context, id, () -> { throw new SecurityException("synthetic start rejection"); });
                fail("Expected the service start to fail");
            } catch (SecurityException expected) { }
            assertTrue(VoiceConversationInvitation.canAccept(context, id));
            assertEquals("pending", VoiceConversationInvitation.preferences(context).getString("invitationStatus"));
            assertNotNull("A failed start must preserve the invitation notification",
                awaitInvitationNotification(context));
            int[] starts = {0};
            assertTrue(VoiceConversationInvitation.startAccepted(context, id, () -> starts[0]++));
            assertEquals("accepted", VoiceConversationInvitation.preferences(context).getString("invitationStatus"));
            assertFalse(VoiceConversationInvitation.startAccepted(context, id, () -> starts[0]++));
            assertEquals(1, starts[0]);
            assertFalse(SubscriptionVoiceService.isActive());
        });
    }
    @Test public void dismissalIsSeparateFromDeclineAndPreservesLegacyAnnouncementText() throws Exception {
        fixture((context, store) -> {
            String message = "Legacy result: " + new String(new char[600]).replace('\0', 'x');
            String id = VoiceConversationInvitation.request(context, Json.obj("threadId", THREAD, "message", message)).getString("id");
            Notification notice = VoiceConversationInvitation.notification(context, VoiceConversationInvitation.pending(context));
            assertEquals(message, notice.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString());
            assertNotEquals(notice.actions[1].actionIntent, notice.deleteIntent);
            notice.deleteIntent.send();
            long until = SystemClock.elapsedRealtime() + 3000;
            while ("pending".equals(VoiceConversationInvitation.pending(context).optString("status")) && SystemClock.elapsedRealtime() < until)
                SystemClock.sleep(30);
            assertEquals("dismissed", VoiceConversationInvitation.preferences(context).getString("invitationStatus"));
            JSONObject next = request(context);
            assertEquals("pending", next.getString("status"));
            assertNotEquals(id, next.getString("id"));
            VoiceConversationInvitation.dismiss(context, id);
            assertEquals(next.getString("id"), VoiceConversationInvitation.pending(context).getString("id"));
            assertEquals("pending", VoiceConversationInvitation.preferences(context).getString("invitationStatus"));
        });
    }
    @Test public void textPreferenceCancelsOffersAndNoAccountNeverClaimsAConnection() throws Exception {
        fixture((context, store) -> {
            String id = request(context).getString("id");
            VoiceConversationInvitation.setMode(context, VoiceConversationInvitation.TEXT);
            assertFalse(VoiceConversationInvitation.canAccept(context, id));
            assertEquals("text_only", request(context).getString("status"));
            assertFalse(SubscriptionVoiceService.isActive());
            VoiceConversationInvitation.setMode(context, VoiceConversationInvitation.OFFER);
            new CredentialStore(context).signOutVoiceCodex();
            assertEquals("voice_sign_in_required", request(context).getString("status"));
            assertFalse(SubscriptionVoiceService.isActive());
        });
    }
}
