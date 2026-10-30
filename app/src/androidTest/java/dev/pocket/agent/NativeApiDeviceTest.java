package dev.pocket.agent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Emulator-only native capability tests: never places a real call or sends a message. */
@RunWith(AndroidJUnit4.class)
public class NativeApiDeviceTest {
    private Context context() {return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    @Test public void backgroundStoreHandoffNeverTakesVisibleOrWorkerTasks() {
        String hidden="RootTask id=210 displayId=0 userId=0\n  taskId=210: com.android.vending/.MainActivity visible=false\n";
        DisplayTaskSnapshot idle=DisplayTaskSnapshot.stacks(hidden);
        assertEquals(java.util.Set.of(210),idle.backgroundStoreTasks(15));
        assertTrue(DisplayTaskSnapshot.stacks(hidden.replace("visible=false","visible=true")).backgroundStoreTasks(15).isEmpty());
        assertTrue(DisplayTaskSnapshot.stacks(hidden.replace("displayId=0","displayId=16")).backgroundStoreTasks(15).isEmpty());
        assertTrue(DisplayTaskSnapshot.stacks(hidden+"  taskId=211: personal.app/.Editor visible=false\n").backgroundStoreTasks(15).isEmpty());
        assertTrue(idle.packageOn("com.android.vending",0));
        assertFalse(idle.packageOn("com.android.vending",15));
        assertTrue(DisplayTaskSnapshot.stacks("Display #0 (activities from top to bottom):\n  RootTask #210\n").backgroundStoreTasks(15).isEmpty());
    }
    @Test public void onlyExactPlayStoreDetailsUrlsUseStoreSession() {
        assertTrue(ShellDisplayService.isPlayStoreDetails(android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.keep")));
        for(String url:new String[]{"https://play.google.com.evil.test/store/apps/details?id=com.google.android.keep","https://play.google.com:123/store/apps/details?id=com.google.android.keep","https://play.google.com/store/apps/details?id=bad%3Bcommand","https://play.google.com/store/apps/details","http://play.google.com/store/apps/details?id=com.google.android.keep"})
            assertFalse(url,ShellDisplayService.isPlayStoreDetails(android.net.Uri.parse(url)));
    }
    @Test public void chooserTargetsCannotReusePersonalSingletonTasks() {
        assertFalse(ShellDisplayService.canLaunchTargetSeparately(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK,true,true));
        assertFalse(ShellDisplayService.canLaunchTargetSeparately(android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE,false,false));
        assertTrue(ShellDisplayService.canLaunchTargetSeparately(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK,true,false));
        assertTrue(ShellDisplayService.canLaunchTargetSeparately(android.content.pm.ActivityInfo.LAUNCH_MULTIPLE,true,true));
    }
    @Test public void idleVirtualDisplayIsReportedReadyInsteadOfBlocked() throws Exception {
        JSONObject idle=AgentDisplayCoordinator.capabilityStatus("idle",0,"idle");
        assertTrue(idle.getBoolean("available"));assertTrue(idle.getBoolean("shizuku_running"));assertTrue(idle.getBoolean("shizuku_authorized"));assertEquals("ready_on_demand",idle.getString("status"));assertEquals(0,idle.getInt("active"));
        assertTrue(AgentDisplayCoordinator.capabilityStatus("disconnected",0,"idle").getBoolean("available"));
        assertFalse(AgentDisplayCoordinator.capabilityStatus("permission_required",0,"permission_required").getBoolean("available"));
        assertFalse(AgentDisplayCoordinator.capabilityStatus("shizuku_unavailable",0,"shizuku_unavailable").getBoolean("available"));
    }
    @Test public void directApiValidationAndCalendarRead() throws Exception {
        NativeTools tools=new NativeTools(context());
        try {
            JSONArray list=tools.list();
            for(String required:new String[]{"phone_calendar_events","phone_notification_read","phone_open_url","phone_clock_screen"}) {
                boolean found=false;
                for(int i=0;i<list.length();i++)if(list.getJSONObject(i).getString("name").equals(required))found=true;
                assertTrue("Missing native tool: "+required,found);
            }
            String operationId=tools.execute("phone_capabilities",new JSONObject(),"emulator-native-tests")
                .getString("operation_prefix")+"_badurl0001";
            JSONObject invalid=tools.execute("phone_open_url",new JSONObject()
                .put("url","javascript:alert(1)").put("operation_id",operationId),"emulator-native-tests");
            assertEquals(invalid.toString(),"INVALID_ARGUMENT",invalid.optString("code"));
            JSONObject badClock=tools.execute("phone_clock_screen",new JSONObject().put("screen","unsupported").put("operation_id","fake"),"emulator-native-tests");
            assertEquals(badClock.toString(),"INVALID_ARGUMENT",badClock.optString("code"));
            JSONObject invalidWindow=tools.execute("phone_calendar_events",new JSONObject().put("start_ms",1000).put("end_ms",999),"emulator-native-tests");
            assertEquals(invalidWindow.toString(),"INVALID_ARGUMENT",invalidWindow.optString("code"));
            if(context().checkSelfPermission(Manifest.permission.READ_CALENDAR)==android.content.pm.PackageManager.PERMISSION_GRANTED) {
                long now=System.currentTimeMillis();
                JSONObject result=tools.execute("phone_calendar_events",new JSONObject().put("start_ms",now).put("end_ms",now+86400000L),"emulator-native-tests");
                assertFalse(result.toString(),result.optBoolean("isError"));
                assertNotNull(result.toString(),result.optJSONArray("events"));
                JSONObject calendars=tools.execute("phone_calendars",new JSONObject(),"emulator-native-tests");
                assertFalse(calendars.toString(),calendars.optBoolean("isError"));
                assertNotNull(calendars.toString(),calendars.optJSONArray("calendars"));
            } else {
                JSONObject denied=tools.execute("phone_calendars",new JSONObject(),"emulator-native-tests");
                assertEquals(denied.toString(),"PERMISSION_REQUIRED",denied.optString("code"));
            }
        } finally {tools.close();}
    }
    @Test public void escapeHeavyNotificationProducesProgressablePage() throws Exception {
        java.util.List<dev.pocket.android.AndroidNotificationActions.Message> messages=new java.util.ArrayList<>();
        JSONArray serialized=new JSONArray();
        for(int i=0;i<10;i++) {
            String control="\u0001".repeat(500);
            messages.add(new dev.pocket.android.AndroidNotificationActions.Message("Sender",control,1000+i));
            serialized.put(Json.obj("sender","Sender","text",control,"timestamp_ms",1000+i));
        }
        dev.pocket.android.AndroidNotificationActions.Entry entry=new dev.pocket.android.AndroidNotificationActions.Entry(
            "key".repeat(300),100L,"dev.pocket.agent","Synthetic","Notification content",
            "Expanded",null,false,messages);
        JSONObject bounded=NativeTools.boundedNotification(entry,serialized);
        assertTrue("Fallback must fit within page budget",bounded.toString().length()<16000);
        assertTrue("Should disclose compacted notification fields",bounded.getBoolean("content_truncated"));
        assertEquals("Oversized extras must not corrupt notification identity",entry.notificationKey(),bounded.getString("notification_key"));
        assertEquals(10,bounded.getInt("message_count"));
    }
    @Test public void readSyntheticActiveNotification() throws Exception {
        assertNotNull("Enable the authorized notification listener on emulator before this test",PhoneNotifications.connected);
        Context c=context();
        if(c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.app.UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();
            automation.adoptShellPermissionIdentity("android.permission.GRANT_RUNTIME_PERMISSIONS");
            try {automation.grantRuntimePermission(c.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);}
            finally {automation.dropShellPermissionIdentity();}
        }
        assertEquals("Notification test requires POST_NOTIFICATIONS",android.content.pm.PackageManager.PERMISSION_GRANTED,
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        String channel="pocket-native-api-instrumentation";
        manager.createNotificationChannel(new NotificationChannel(channel,"Pocket test",NotificationManager.IMPORTANCE_DEFAULT));
        int id=46071;
        try(NativeTools tools=new NativeTools(c)) {
            manager.notify(id,new Notification.Builder(c,channel).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Pocket native test").setContentText("Synthetic notification content").build());
            JSONObject result=new JSONObject();
            boolean found=false;
            for(int n=0;n<25&&!found;n++) {
                result=tools.execute("phone_notification_read",new JSONObject()
                    .put("package_name",c.getPackageName()).put("limit",30),"emulator-notification-tests");
                JSONArray entries=result.optJSONArray("notifications");
                if(entries!=null)for(int i=0;i<entries.length();i++)
                    if("Synthetic notification content".equals(entries.getJSONObject(i).optString("text")))found=true;
                if(!found)Thread.sleep(150);
            }
            assertTrue("Posted test notification not returned by listener: "+result,found);
            String longText="n".repeat(1000);
            for(int i=1;i<=35;i++)manager.notify(id+i,new Notification.Builder(c,channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Pocket volume "+i)
                .setContentText(longText).build());
            Thread.sleep(400);
            JSONObject first=tools.execute("phone_notification_read",new JSONObject()
                .put("package_name",c.getPackageName()).put("limit",50),"emulator-notification-tests");
            assertFalse(first.toString(),first.optBoolean("isError"));
            assertTrue("Response exceeded embedded JS limit",first.toString().length()<32000);
            assertTrue("Expected bounded output to report truncation",first.optBoolean("truncated"));
            int offset=first.getInt("next_offset");
            assertTrue("Pagination must advance",offset>0);
            JSONObject second=tools.execute("phone_notification_read",new JSONObject()
                .put("package_name",c.getPackageName()).put("offset",offset).put("limit",50),"emulator-notification-tests");
            assertTrue("Pagination must return further notifications",second.getJSONArray("notifications").length()>0);
        }finally {for(int i=0;i<=35;i++)manager.cancel(id+i);}
    }
}
