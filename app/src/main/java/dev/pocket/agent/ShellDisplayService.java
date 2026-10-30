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
import android.view.Display;
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
    private static final int OWN_FOCUS=1<<14, DEVICE_GROUP=1<<15, NO_STEAL_TOP_FOCUS=1<<16;
    private static final int DISPLAY_OWN_FOCUS=1<<11, DISPLAY_NO_STEAL_TOP_FOCUS=1<<12;
    private static final int DISPLAY_IME_POLICY_HIDE=2;
    private final Context context;
    private final Map<Integer,OwnedDisplay> displays=new LinkedHashMap<>();
    private final DisplayRestorationReservations reservations=new DisplayRestorationReservations();
    private HandlerThread frameThread;
    private final ShellTerminal terminal=new ShellTerminal(new java.io.File("/data/local/tmp/pocket-agent-shell"));

    private static final class DisplayWakeLock implements AutoCloseable {
        final Object power;
        final android.os.IBinder token=new android.os.Binder();
        final java.lang.reflect.Method release;
        DisplayWakeLock(android.view.Display display) throws Exception {
            Class<?> infoType=Class.forName("android.view.DisplayInfo");
            Object info=infoType.getConstructor().newInstance();
            if(!Boolean.TRUE.equals(android.view.Display.class.getMethod("getDisplayInfo",infoType).invoke(display,info)))
                throw new IllegalStateException("Private display information unavailable");
            int group=infoType.getField("displayGroupId").getInt(info);
            if(group<=0)throw new SecurityException("Private display must have its own power group");
            android.os.IBinder binder=(android.os.IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"power");
            power=Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface",android.os.IBinder.class).invoke(null,binder);
            Class<?> api=Class.forName("android.os.IPowerManager");
            release=api.getMethod("releaseWakeLock",android.os.IBinder.class,int.class);
            java.lang.reflect.Method acquire=null;
            for(java.lang.reflect.Method method:api.getMethods()) {
                Class<?>[] parameters=method.getParameterTypes();
                if(method.getName().equals("acquireWakeLock")&&(parameters.length==7||parameters.length==8)&&parameters[6]==int.class){acquire=method;break;}
            }
            if(acquire==null)throw new NoSuchMethodException("Display-scoped wake locks unavailable");
            Object[] args=acquire.getParameterCount()==8
                ?new Object[]{token,0x1000000a,"PocketAgent-PrivateDisplay","com.android.shell",null,null,display.getDisplayId(),null}
                :new Object[]{token,0x1000000a,"PocketAgent-PrivateDisplay","com.android.shell",null,null,display.getDisplayId()};
            try {acquire.invoke(power,args);}catch(Exception failure){close();throw failure;}
        }
        @Override public void close(){try {release.invoke(power,token,0);}catch(Exception failure){android.util.Log.w("PocketDisplayShizuku","Private display wake lock release failed");}}
    }

    private static final class OwnedDisplay {
        final VirtualDisplay display;
        final ImageReader reader;
        final DisplayWakeLock wakeLock;
        final Map<Integer,Integer> protectedTasks=new LinkedHashMap<>();
        Image latestFrame;
        boolean closed;
        OwnedDisplay(VirtualDisplay display,ImageReader reader,DisplayTaskSnapshot before,DisplayWakeLock wakeLock) {
            this.display=display;this.reader=reader;this.wakeLock=wakeLock;protectedTasks.putAll(before.taskDisplays);
        }
        synchronized void updateFrame(Image frame){if(closed){frame.close();return;}if(latestFrame!=null)latestFrame.close();latestFrame=frame;}
        synchronized String preview() {
            if(latestFrame==null)return "";
            Image.Plane plane=latestFrame.getPlanes()[0];int width=latestFrame.getWidth(),height=latestFrame.getHeight();
            android.graphics.Bitmap padded=android.graphics.Bitmap.createBitmap(plane.getRowStride()/plane.getPixelStride(),height,android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Bitmap cropped=null,scaled=null;
            try {
                java.nio.ByteBuffer pixels=plane.getBuffer();pixels.rewind();java.nio.ByteBuffer packed=java.nio.ByteBuffer.allocate(padded.getByteCount());packed.put(pixels);packed.rewind();padded.copyPixelsFromBuffer(packed);
                cropped=android.graphics.Bitmap.createBitmap(padded,0,0,width,height);
                int targetWidth=Math.min(480,width);scaled=android.graphics.Bitmap.createScaledBitmap(cropped,targetWidth,Math.max(1,height*targetWidth/width),true);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG,65,bytes);
                return android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP);
            } finally {if(scaled!=null&&scaled!=cropped&&scaled!=padded)scaled.recycle();if(cropped!=null&&cropped!=padded)cropped.recycle();padded.recycle();}
        }
        synchronized void close() {closed=true;try {display.release();}finally {wakeLock.close();if(latestFrame!=null){latestFrame.close();latestFrame=null;}reader.close();}}
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
    static int[] displayFlagChoices(int apiLevel) {
        int required=PUBLIC|PRESENTATION|OWN_CONTENT|TOUCH|ROTATE|DESTROY_CONTENT|TRUSTED;
        int grouped=required|OWN_GROUP|ALWAYS_UNLOCKED;
        if(apiLevel>=34){int isolated=grouped|OWN_FOCUS|NO_STEAL_TOP_FOCUS;return new int[]{isolated|DEVICE_GROUP,isolated};}
        return new int[]{grouped,required|OWN_GROUP};
    }
    static boolean hasFocusIsolation(int apiLevel,int flags){int protection=DISPLAY_OWN_FOCUS|DISPLAY_NO_STEAL_TOP_FOCUS;return apiLevel<34||(flags&protection)==protection;}
    @Override public synchronized int createDisplay(int width,int height,int dpi) {
        if(Process.myUid()!=2000 && Process.myUid()!=0) throw new SecurityException("Requires Shizuku shell/root user service");
        if(width<320 || width>1920 || height<320 || height>2560 || dpi<120 || dpi>640) throw new IllegalArgumentException("Invalid display dimensions");
        if(displays.size()>=3) throw new IllegalStateException("Three virtual displays already allocated");
        DisplayTaskSnapshot before=taskSnapshot();
        ImageReader reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3);
        int[] choices=displayFlagChoices(Build.VERSION.SDK_INT);
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
                    if(!hasFocusIsolation(Build.VERSION.SDK_INT,display.getDisplay().getFlags())){
                        display.release();last=new IllegalStateException("Android did not retain private display focus isolation");continue;
                    }
                    DisplayWakeLock wakeLock;
                    try {hideIme(id);wakeLock=new DisplayWakeLock(display.getDisplay());}
                    catch(Exception imeFailure) {
                        display.release();
                        last=imeFailure;
                        continue;
                    }
                    OwnedDisplay owned=new OwnedDisplay(display,reader,before,wakeLock);
                    try {
                    reader.setOnImageAvailableListener(r->{try {Image frame=r.acquireLatestImage();if(frame!=null)owned.updateFrame(frame);}catch(IllegalStateException closed){android.util.Log.d("PocketDisplayShizuku","Frame reader closed");}},frameHandler());
                    } catch(Exception setupFailure) {owned.close();throw setupFailure;}
                    displays.put(id,owned);return id;
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
    @Override public void removeDisplay(int id) {
        // Only reservation/finalization hold the service monitor. Task inspection,
        // migration, and shell retries can each take seconds, but must not block
        // createDisplay or launch on an unrelated worker display.
        DisplayRemovalSequence.run(this,
            () -> {
                owned(id);
                reservations.beginRemoval(id);
                return displays.get(id);
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
        return launchTrackedForPackage(id,null,command);
    }
    private boolean launchTrackedForPackage(int id,String expectedPackage,String... command) {
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
            if(expectedPackage!=null&&snapshot.packageOn(expectedPackage,id))return true;
            try {Thread.sleep(75);} catch(InterruptedException e) {Thread.currentThread().interrupt();return false;}
        } while(android.os.SystemClock.elapsedRealtime()<deadline);
        return false;
    }
    private static final String WORKER_ACTIVITY_FLAGS="0x18800000";
    @Override public synchronized String previewDisplay(int id) {owned(id);return displays.get(id).preview();}
    private boolean acquireBackgroundStore(int id) {
        OwnedDisplay owned=displays.get(id);
        DisplayTaskSnapshot before=taskSnapshot();
        if(!before.packageOutside("com.android.vending",id))return true;
        Set<Integer> tasks=before.backgroundStoreTasks(id);
        if(tasks.size()!=1)return false;
        int task=tasks.iterator().next();
        DisplayTaskSnapshot current=taskSnapshot();
        if(!current.backgroundStoreTasks(id).equals(tasks))return false;
        boolean moved=exec("/system/bin/am","display","move-stack",String.valueOf(task),String.valueOf(id));
        DisplayTaskSnapshot after=taskSnapshot();
        if(after.onDisplay(id).contains(task)) {
            owned.protectedTasks.remove(task);
            return moved;
        }
        return false;
    }
    private boolean canLaunchSeparately(int id,android.content.Intent intent) {
        // MULTIPLE_TASK does not override singleTask/singleInstance. Refuse to
        // deliver a new intent to an existing personal task (which can clear its
        // back stack) before the post-launch migration check gets a chance to run.
        android.content.pm.ResolveInfo resolved=asShell().getPackageManager().resolveActivity(intent,0);
        if(resolved==null||resolved.activityInfo==null)return false;
        // A chooser is only an intermediate activity. Check its possible targets
        // before dispatch, since a later handoff can reuse a personal task.
        DisplayTaskSnapshot snapshot=taskSnapshot();
        for(android.content.pm.ResolveInfo candidate:asShell().getPackageManager().queryIntentActivities(intent,0)) {
            android.content.pm.ActivityInfo target=candidate.activityInfo;
            if(target!=null && !canLaunchTargetSeparately(target.launchMode,
                snapshot.hasPackageRecords(),snapshot.packageOutside(target.packageName,id)))return false;
        }
        android.content.pm.ActivityInfo activity=resolved.activityInfo;
        return canLaunchTargetSeparately(activity.launchMode,snapshot.hasPackageRecords(),snapshot.packageOutside(activity.packageName,id));
    }
    static boolean canLaunchTargetSeparately(int launchMode,boolean packageRecords,boolean outsideTask) {
        return launchMode!=android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK
            &&launchMode!=android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE
            || packageRecords&&!outsideTask;
    }
    @Override public synchronized boolean launchActivity(int id,String componentName) {
        owned(id);
        android.content.ComponentName component=android.content.ComponentName.unflattenFromString(componentName);
        if(component==null || component.getPackageName().trim().isEmpty()) throw new IllegalArgumentException("Expected component name");
        if(component.getPackageName().equals("com.android.vending")) {
            if(!acquireBackgroundStore(id))return false;
            if(taskSnapshot().packageOn("com.android.vending",id))return true;
        }
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
        if(isPlayStoreDetails(uri)) {
            if(!acquireBackgroundStore(id))return false;
            android.content.Intent storeIntent=new android.content.Intent(android.content.Intent.ACTION_VIEW,uri).setPackage("com.android.vending");
            if(!canLaunchSeparately(id,storeIntent))return false;
            return launchTrackedForPackage(id,"com.android.vending","/system/bin/am","start","--display",String.valueOf(id),"-f",WORKER_ACTIVITY_FLAGS,
                "-a",android.content.Intent.ACTION_VIEW,"-d",uri.toString(),"-p","com.android.vending");
        }
        if(!canLaunchSeparately(id,new android.content.Intent(android.content.Intent.ACTION_VIEW,uri)))return false;
        return launchTracked(id,"/system/bin/am","start","--display",String.valueOf(id),"-f",WORKER_ACTIVITY_FLAGS,
            "-a",android.content.Intent.ACTION_VIEW,"-d",uri.toString());
    }
    static boolean isPlayStoreDetails(android.net.Uri uri) {
        return "https".equalsIgnoreCase(uri.getScheme())&&"play.google.com".equalsIgnoreCase(uri.getHost())
            &&uri.getPort()==-1&&"/store/apps/details".equals(uri.getPath())
            &&uri.getQueryParameter("id")!=null&&uri.getQueryParameter("id").matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
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
    @Override public synchronized boolean injectText(int id,String text) {
        owned(id);
        if(text==null||text.isEmpty()||text.length()>2000)throw new IllegalArgumentException("Keyboard text requires 1..2000 characters");
        android.view.KeyEvent[] events=android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if(events==null)throw new IllegalArgumentException("Text is not representable by the Android virtual keyboard");
        try {
            Class<?> input=Class.forName("android.hardware.input.InputManager");
            Object manager=input.getMethod("getInstance").invoke(null);
            java.lang.reflect.Method inject=input.getMethod("injectInputEvent",android.view.InputEvent.class,int.class);
            java.lang.reflect.Method display=android.view.InputEvent.class.getMethod("setDisplayId",int.class);
            for(android.view.KeyEvent event:events) {
                display.invoke(event,id);
                if(!Boolean.TRUE.equals(inject.invoke(manager,event,2)))return false;
            }
            return true;
        } catch(ReflectiveOperationException failure) {return false;}
    }
    @Override public synchronized String health() {
        return "uid="+Process.myUid()+";display_count="+displays.size()+";android="+Build.VERSION.SDK_INT;
    }
    @Override public String terminal(String request,String owner,int displayId) {
        try {
            org.json.JSONObject args=new org.json.JSONObject(request),result;
            String action=args.getString("action");
            if(action.equals("context"))result=terminal.context(owner);
            else if(action.equals("exec"))result=terminal.execute(args,owner,displayId<0?null:displayId);
            else if(action.equals("poll"))result=terminal.poll(args.getString("session_id"),args.optLong("offset",0));
            else if(action.equals("stop"))result=terminal.stop(args.getString("session_id"),args.optLong("offset",0));
            else if(action.equals("image"))result=ShellImages.read(args.getString("path"),args.optInt("max_image_dimension",1280));
            else if(action.equals("capture")) {
                int target=args.optInt("display_id",displayId);if(target!=displayId)throw new SecurityException("Capture must use the assigned display");OwnedDisplay active;
                synchronized(this){active=displays.get(target);}
                if(active==null)throw new IllegalArgumentException("No frame reader for this display. Acquire a private display or use shell screencap plus action=image for the physical screen.");
                synchronized(active) {
                    if(active.latestFrame==null)throw new IllegalStateException("No display frame yet; wait for app rendering and capture again");
                    result=ShellImages.frame(active.latestFrame,args.optInt("max_image_dimension",1280));
                }
                result.put("display_id",target);
            } else throw new IllegalArgumentException("Unsupported shell action");
            result.put("active_sessions",terminal.activeSessions());result.put("uid",Process.myUid());result.put("assigned_display_id",displayId<0?org.json.JSONObject.NULL:displayId);
            return result.toString();
        } catch(Exception error) {
            return Json.failure(error instanceof SecurityException?"PERMISSION_REQUIRED":error instanceof IllegalArgumentException||error instanceof org.json.JSONException?"INVALID_ARGUMENT":"SHELL_ERROR",
                error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()).toString();
        }
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
