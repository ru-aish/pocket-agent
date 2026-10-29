package dev.pocket.agent;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.IBinder;
import android.view.Display;
import android.view.Surface;
import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku UserService, executed under Android shell UID.
 * The app process never receives ADD_TRUSTED_DISPLAY or INJECT_EVENTS.
 */
public final class ShellDisplayService extends IDisplayShell.Stub {
    private static final int PUBLIC=1, PRESENTATION=2, OWN_CONTENT=8;
    private static final int TOUCH=1<<6, ROTATE=1<<7, DESTROY_CONTENT=1<<8;
    private static final int TRUSTED=1<<10, OWN_GROUP=1<<11, ALWAYS_UNLOCKED=1<<12;
    private static final int OWN_FOCUS=1<<14, DEVICE_GROUP=1<<15;
    private static final int DISPLAY_IME_POLICY_HIDE=2;
    private final Context context;
    private final Map<Integer,OwnedDisplay> displays=new LinkedHashMap<>();
    private final DisplayRestorationReservations reservations=new DisplayRestorationReservations();
    private HandlerThread frameThread;

    private static final class OwnedDisplay {
        final VirtualDisplay display;
        final ImageReader reader;
        final DisplayPreviewSurface preview;
        final Map<Integer,Integer> protectedTasks=new LinkedHashMap<>();
        OwnedDisplay(VirtualDisplay display,ImageReader reader,DisplayTaskSnapshot before) {
            this.display=display;this.reader=reader;protectedTasks.putAll(before.taskDisplays);
            preview=new DisplayPreviewSurface(display::setSurface,reader.getSurface());
        }
        void close() {try {preview.close();}finally {try {display.release();}finally {reader.close();}}}
    }

    public ShellDisplayService() {this.context=null;android.util.Log.i("PocketDisplayShizuku","Shizuku UserService no-context constructor uid="+Process.myUid());}
    public ShellDisplayService(Context context) {this.context=context;android.util.Log.i("PocketDisplayShizuku","Shizuku UserService context constructor uid="+Process.myUid());}

    private Context asShell() {
        if(context==null) throw new IllegalStateException("Shizuku v13 Context required");
        return new ContextWrapper(context) {
            @Override public String getPackageName() {return "com.android.shell";}
            @Override public String getOpPackageName() {return "com.android.shell";}
        };
    }
    private DisplayManager displayManager() throws Exception {
        Constructor<DisplayManager> constructor=DisplayManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(asShell());
    }
    private Handler frameHandler() {
        if(frameThread==null) {frameThread=new HandlerThread("pocket-display-frames");frameThread.start();}
        return new Handler(frameThread.getLooper());
    }
    @Override public synchronized int createDisplay(int width,int height,int dpi) {
        if(Process.myUid()!=2000 && Process.myUid()!=0) throw new SecurityException("Requires Shizuku shell/root user service");
        if(width<320 || width>1920 || height<320 || height>2560 || dpi<120 || dpi>640) throw new IllegalArgumentException("Invalid display dimensions");
        if(displays.size()>=3) throw new IllegalStateException("Three virtual displays already allocated");
        DisplayTaskSnapshot before=taskSnapshot();
        ImageReader reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
        reader.setOnImageAvailableListener(r->{
            try(Image frame=r.acquireLatestImage()) {
                // Drain producer frames without recording content or running a video encoder.
            } catch(IllegalStateException ignored) {}
        },frameHandler());
        int required=PUBLIC|PRESENTATION|OWN_CONTENT|TOUCH|ROTATE|DESTROY_CONTENT|TRUSTED;
        int extended=required|OWN_GROUP|ALWAYS_UNLOCKED;
        if(Build.VERSION.SDK_INT>=34) extended|=OWN_FOCUS|DEVICE_GROUP;
        int[] choices={extended,required|OWN_GROUP,required};
        Exception last=null;
        try {
            DisplayManager dm=displayManager();
            for(int flags:choices) {
                try {
                    VirtualDisplay display=dm.createVirtualDisplay("PocketAgent-Worker",width,height,dpi,reader.getSurface(),flags);
                    if(display==null || display.getDisplay()==null || display.getDisplay().getDisplayId()<=Display.DEFAULT_DISPLAY) {
                        if(display!=null) display.release();
                        throw new IllegalStateException("Android returned no independent display");
                    }
                    int id=display.getDisplay().getDisplayId();
                    if(displays.containsKey(id)) {
                        display.release();
                        throw new IllegalStateException("Android reused an allocated display ID");
                    }
                    try {hideIme(id);}
                    catch(Exception imeFailure) {
                        display.release();
                        last=imeFailure;
                        continue;
                    }
                    displays.put(id,new OwnedDisplay(display,reader,before));
                    return id;
                } catch(Exception e) {last=e;}
            }
        } catch(Exception e) {last=e;}
        reader.close();
        if(displays.isEmpty() && frameThread!=null) {frameThread.quitSafely();frameThread=null;}
        throw new IllegalStateException("Trusted virtual display unavailable: "+(last==null?"unknown":last.getClass().getSimpleName()),last);
    }
    private void owned(int id) {
        if(id<=Display.DEFAULT_DISPLAY || !displays.containsKey(id)) throw new SecurityException("Display is not owned by Pocket Agent");
        if(reservations.isRemoving(id)) throw new IllegalStateException("Display cleanup is in progress");
    }
    @Override public synchronized void showPreview(int id,Surface surface,IBinder session) {
        try {owned(id);}
        catch(RuntimeException failure) {if(surface!=null)surface.release();throw failure;}
        displays.get(id).preview.show(surface,session);
    }
    @Override public synchronized void hidePreview(int id,IBinder session) {
        OwnedDisplay active=displays.get(id);
        if(active!=null)active.preview.hide(session);
    }
    @Override public void removeDisplay(int id) {
        // Only reservation/finalization hold the service monitor. Task inspection,
        // migration, and shell retries can each take seconds, but must not block
        // createDisplay or launch on an unrelated worker display.
        DisplayRemovalSequence.run(this,
            () -> {
                owned(id);
                OwnedDisplay active=displays.get(id);
                // Stop the preview before native release runs outside this monitor.
                active.preview.detach();
                reservations.beginRemoval(id);
                return active;
            },
            active -> reconcileAndRemoveTasks(id,active),
            active -> active.close(),
            active -> {
                displays.remove(id);
                if(displays.isEmpty() && frameThread!=null) {frameThread.quitSafely();frameThread=null;}
            },
            () -> reservations.finishRemoval(id));
    }
    static void hideIme(int displayId) throws Exception {
        Class<?> serviceManager=Class.forName("android.os.ServiceManager");
        android.os.IBinder binder=(android.os.IBinder)serviceManager.getMethod("getService",String.class).invoke(null,"window");
        if(binder==null)throw new IllegalStateException("Window manager unavailable");
        Class<?> stub=Class.forName("android.view.IWindowManager$Stub");
        Object windowManager=stub.getMethod("asInterface",android.os.IBinder.class).invoke(null,binder);
        Class<?> iWindowManager=Class.forName("android.view.IWindowManager");
        iWindowManager.getMethod("setDisplayImePolicy",int.class,int.class).invoke(windowManager,displayId,DISPLAY_IME_POLICY_HIDE);
    }
    static Set<Integer> parseRootTasks(String output,int displayId) {
        DisplayTaskSnapshot stacks=DisplayTaskSnapshot.stacks(output);
        return (stacks.displays.isEmpty()?DisplayTaskSnapshot.activities(output):stacks).onDisplay(displayId);
    }
    private static final class CommandResult {
        final boolean ok; final String output;
        CommandResult(boolean ok,String output){this.ok=ok;this.output=output;}
    }
    private static CommandResult run(String... command) {
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);
        java.lang.Process child=null;Thread reader=null;ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        java.util.concurrent.atomic.AtomicBoolean incomplete=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            child=builder.start();
            InputStream stream=child.getInputStream();
            reader=new Thread(()->{
                byte[] buffer=new byte[4096];int read,total=0;
                try {
                    while((read=stream.read(buffer))!=-1) {
                        int keep=Math.min(read,262_144-total);
                        if(keep<read)incomplete.set(true);
                        if(keep>0){bytes.write(buffer,0,keep);total+=keep;}
                    }
                } catch(Exception ignored) {incomplete.set(true);}
            },"PocketShellCommandOutput");
            reader.setDaemon(true);reader.start();
            if(!child.waitFor(8,TimeUnit.SECONDS)) {
                child.destroy();
                if(!child.waitFor(250,TimeUnit.MILLISECONDS))child.destroyForcibly();
                return new CommandResult(false,"");
            }
            if(reader!=null)reader.join(250);
            return new CommandResult(child.exitValue()==0&&!reader.isAlive()&&!incomplete.get(),new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        } catch(Exception e) {
            if(child!=null)child.destroyForcibly();
            return new CommandResult(false,"");
        } finally {
            if(reader!=null&&reader.isAlive())reader.interrupt();
        }
    }
    private static String execOutput(String... command) {CommandResult result=run(command);return result.ok?result.output:"";}
    private static boolean exec(String... command) {return run(command).ok;}
    private static Set<String> accessibilityServices(String raw) {
        Set<String> result=new LinkedHashSet<>();
        if(raw==null||raw.trim().isEmpty()||"null".equals(raw.trim()))return result;
        for(String item:raw.split(":"))if(!item.trim().isEmpty())result.add(item.trim());
        return result;
    }
    private static boolean containsComponent(Set<String> services,android.content.ComponentName target) {
        for(String item:services)if(target.equals(android.content.ComponentName.unflattenFromString(item)))return true;
        return false;
    }
    private static void removeComponent(Set<String> services,android.content.ComponentName target) {
        services.removeIf(item->target.equals(android.content.ComponentName.unflattenFromString(item)));
    }
    private static boolean putAccessibilityServices(Set<String> services) {
        return services.isEmpty()
            ? exec("/system/bin/settings","delete","secure","enabled_accessibility_services")
            : exec("/system/bin/settings","put","secure","enabled_accessibility_services",String.join(":",services));
    }
    private boolean safeForAccessibilityRepair() {
        if(context==null)return false;
        android.os.PowerManager power=context.getSystemService(android.os.PowerManager.class);
        android.app.KeyguardManager keyguard=context.getSystemService(android.app.KeyguardManager.class);
        return power!=null&&keyguard!=null&&(!power.isInteractive()||keyguard.isKeyguardLocked());
    }
    @Override public synchronized boolean repairAccessibility(String componentName,boolean restoreMissing) {
        android.content.ComponentName component=android.content.ComponentName.unflattenFromString(componentName);
        if(component==null||!BuildConfig.APPLICATION_ID.equals(component.getPackageName())
            ||!"dev.pocket.agent.AgentAccessibility".equals(component.getClassName()))
            throw new SecurityException("Only Pocket Agent accessibility may be repaired");
        if(!"1".equals(execOutput("/system/bin/settings","get","secure","accessibility_enabled").trim()))return false;
        Set<String> current=accessibilityServices(execOutput("/system/bin/settings","get","secure","enabled_accessibility_services"));
        if(!containsComponent(current,component)) {
            if(!restoreMissing||!safeForAccessibilityRepair())return false;
            current.add(component.flattenToShortString());
            return putAccessibilityServices(current);
        }

        // The real-device recovery path is a scoped remove/wait/re-add. Preserve every
        // unrelated service and never toggle the global accessibility switch.
        removeComponent(current,component);
        if(!putAccessibilityServices(current))return false;
        try {Thread.sleep(450);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();return false;}
        // If the phone became unlocked while our own temporary removal was in flight,
        // refuse to re-add the service. That transition creates a real opportunity for
        // the user to have intentionally disabled Pocket Agent during recovery.
        if(!safeForAccessibilityRepair())return false;
        if(!"1".equals(execOutput("/system/bin/settings","get","secure","accessibility_enabled").trim()))return false;
        Set<String> after=accessibilityServices(execOutput("/system/bin/settings","get","secure","enabled_accessibility_services"));
        if(containsComponent(after,component))return true;
        after.add(component.flattenToShortString());
        return putAccessibilityServices(after);
    }
    private DisplayTaskSnapshot taskSnapshot() {
        CommandResult stacks=run("/system/bin/am","stack","list");
        DisplayTaskSnapshot result=DisplayTaskSnapshot.stacks(stacks.output);
        // An empty target display is a valid answer, not a reason to change data sources.
        if(stacks.ok&&!result.displays.isEmpty())return result;
        CommandResult activities=run("/system/bin/dumpsys","activity","activities");
        result=DisplayTaskSnapshot.activities(activities.output);
        if(activities.ok&&!result.displays.isEmpty())return result;
        throw new IllegalStateException("Cannot establish task display membership; refusing display cleanup or launch");
    }
    private void protectOutsideTasks(int id,OwnedDisplay owned,DisplayTaskSnapshot snapshot) {
        snapshot.protectOutside(id,owned.protectedTasks);
    }
    private boolean restoreBorrowedTasks(int id,OwnedDisplay owned,DisplayTaskSnapshot snapshot) {
        protectOutsideTasks(id,owned,snapshot);
        boolean borrowed=false;
        for(int task:snapshot.onDisplay(id)) {
            Integer origin=owned.protectedTasks.get(task);
            if(origin==null)continue;
            borrowed=true;
            // Never destroy a reused user/other-worker task along with this display.
            // Pin the destination until the move is verified. A different worker's
            // removal cannot pass its empty-display check and then destroy this task.
            int destination;
            synchronized(this) {
                destination=reservations.pinOrigin(origin,displays.containsKey(origin));
            }
            try {
                if(!exec("/system/bin/am","display","move-stack",String.valueOf(task),String.valueOf(destination))
                    ||taskSnapshot().onDisplay(id).contains(task))
                    throw new IllegalStateException("Cannot restore a pre-existing task; retaining virtual display");
            } finally {
                reservations.unpinOrigin(destination);
            }
        }
        return borrowed;
    }
    private void reconcileAndRemoveTasks(int id,OwnedDisplay owned) {
        for(int pass=0;pass<3;pass++) {
            DisplayTaskSnapshot current=taskSnapshot();
            restoreBorrowedTasks(id,owned,current);
            for(int rootTask:current.removable(id,owned.protectedTasks)) {
                // Cached launch IDs are not authority to delete a task. A user can
                // have moved it to display zero since it was first observed.
                DisplayTaskSnapshot verified=taskSnapshot();
                protectOutsideTasks(id,owned,verified);
                if(verified.removable(id,owned.protectedTasks).contains(rootTask))
                    exec("/system/bin/am","stack","remove",String.valueOf(rootTask));
            }
            if(taskSnapshot().onDisplay(id).isEmpty())return;
            try {Thread.sleep(80);} catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
        // DESTROY_CONTENT handles remaining worker activities on display removal.
        // Recheck protected tasks before that implicit destruction too.
        restoreBorrowedTasks(id,owned,taskSnapshot());
    }
    private boolean launchTracked(int id,String... command) {
        DisplayTaskSnapshot before=taskSnapshot();
        OwnedDisplay owned=displays.get(id);
        protectOutsideTasks(id,owned,before);
        if(!exec(command))return false;
        long deadline=android.os.SystemClock.elapsedRealtime()+3000;
        do {
            DisplayTaskSnapshot snapshot=taskSnapshot();
            if(restoreBorrowedTasks(id,owned,snapshot))return false;
            Set<Integer> created=snapshot.onDisplay(id);created.removeAll(before.taskDisplays.keySet());
            if(!created.isEmpty())return true;
            try {Thread.sleep(75);} catch(InterruptedException e) {Thread.currentThread().interrupt();return false;}
        } while(android.os.SystemClock.elapsedRealtime()<deadline);
        return false;
    }
    private static final String WORKER_ACTIVITY_FLAGS="0x18800000";
    private boolean canLaunchSeparately(int id,android.content.Intent intent) {
        // MULTIPLE_TASK does not override singleTask/singleInstance. Refuse to
        // deliver a new intent to an existing personal task (which can clear its
        // back stack) before the post-launch migration check gets a chance to run.
        android.content.pm.ResolveInfo resolved=asShell().getPackageManager().resolveActivity(intent,0);
        if(resolved==null||resolved.activityInfo==null)return false;
        android.content.pm.ActivityInfo activity=resolved.activityInfo;
        if(activity.launchMode!=android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK
            &&activity.launchMode!=android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE)return true;
        DisplayTaskSnapshot snapshot=taskSnapshot();
        return snapshot.hasPackageRecords()&&!snapshot.packageOutside(activity.packageName,id);
    }
    @Override public synchronized boolean launchActivity(int id,String componentName) {
        owned(id);
        android.content.ComponentName component=android.content.ComponentName.unflattenFromString(componentName);
        if(component==null || component.getPackageName().trim().isEmpty()) throw new IllegalArgumentException("Expected component name");
        if(!canLaunchSeparately(id,new android.content.Intent().setComponent(component)))return false;
        return launchTracked(id,"/system/bin/am","start","--display",String.valueOf(id),"-f",WORKER_ACTIVITY_FLAGS,"-n",component.flattenToString());
    }
    @Override public synchronized boolean openUrl(int id,String url) {
        owned(id);
        android.net.Uri uri=android.net.Uri.parse(url);
        String scheme=uri.getScheme();
        if(uri.getHost()==null || uri.getHost().trim().isEmpty() || uri.getUserInfo()!=null ||
            (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)))
            throw new IllegalArgumentException("Only ordinary HTTP(S) URLs without embedded credentials are supported");
        if(!canLaunchSeparately(id,new android.content.Intent(android.content.Intent.ACTION_VIEW,uri)))return false;
        return launchTracked(id,"/system/bin/am","start","--display",String.valueOf(id),"-f",WORKER_ACTIVITY_FLAGS,
            "-a",android.content.Intent.ACTION_VIEW,"-d",uri.toString());
    }
    @Override public synchronized boolean showClock(int id,String screen) {
        owned(id);
        String action=switch(screen) {
            case "alarms" -> android.provider.AlarmClock.ACTION_SHOW_ALARMS;
            case "timers" -> android.provider.AlarmClock.ACTION_SHOW_TIMERS;
            default -> throw new IllegalArgumentException("Expected alarms or timers");
        };
        if(!canLaunchSeparately(id,new android.content.Intent(action)))return false;
        return launchTracked(id,"/system/bin/am","start","--display",String.valueOf(id),"-f",WORKER_ACTIVITY_FLAGS,"-a",action);
    }
    @Override public synchronized boolean injectKey(int id,int keyCode) {
        owned(id);
        if(keyCode!=4 && keyCode!=3 && keyCode!=187) throw new SecurityException("Key not permitted");
        return exec("/system/bin/input","-d",String.valueOf(id),"keyevent",String.valueOf(keyCode));
    }
    @Override public synchronized String health() {
        return "uid="+Process.myUid()+";display_count="+displays.size()+";android="+Build.VERSION.SDK_INT;
    }
    @Override public synchronized void destroy() {
        // A concurrent removal owns the task-reconciliation obligation. Do not
        // run a second cleanup over the same display or implicitly destroy tasks.
        if(reservations.hasReservations())throw new IllegalStateException("Display cleanup or restoration in progress; retry destroy");
        for(Map.Entry<Integer,OwnedDisplay> entry:displays.entrySet()) {
            // Failure to prove ownership must also prevent implicit DESTROY_CONTENT.
            reconcileAndRemoveTasks(entry.getKey(),entry.getValue());
            try {entry.getValue().close();}catch(Exception ignored){}
        }
        displays.clear();
        if(frameThread!=null) {frameThread.quitSafely();frameThread=null;}
        System.exit(0);
    }
}
