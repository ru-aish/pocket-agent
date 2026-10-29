package dev.pocket.agent;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import dev.pocket.control.ControlException;
import rikka.shizuku.Shizuku;

/** Single Shizuku session shared by all agent-owned virtual displays. */
final class ShizukuDisplayClient implements AutoCloseable {
    private static final String TAG="PocketDisplayShizuku";
    private final Shizuku.UserServiceArgs args;
    private final Object readyLock=new Object();
    private final Runnable disconnected;
    private final boolean removeServiceOnClose;
    private volatile IDisplayShell remote;
    private volatile boolean binding;
    private volatile long bindingStartedMs;
    private volatile boolean closed;
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            if(closed) {
                try {Shizuku.unbindUserService(args,connection,removeServiceOnClose);}catch(Exception ignored){}
                return;
            }
            IDisplayShell shell=IDisplayShell.Stub.asInterface(binder);
            try {binder.linkToDeath(ShizukuDisplayClient.this::loseService,0);}
            catch(RemoteException e) {loseService();return;}
            remote=shell;binding=false;
            Log.i(TAG,"Shizuku UserService connected");
            synchronized(readyLock) {readyLock.notifyAll();}
        }
        @Override public void onServiceDisconnected(ComponentName name) {loseService();}
    };
    private final Shizuku.OnBinderReceivedListener received=this::tryBind;
    private final Shizuku.OnBinderDeadListener died=this::loseService;

    ShizukuDisplayClient(Context context,Runnable disconnected) {this(context,disconnected,true);}
    ShizukuDisplayClient(Context context,Runnable disconnected,boolean removeServiceOnClose) {
        this.disconnected=disconnected;
        this.removeServiceOnClose=removeServiceOnClose;
        args=new Shizuku.UserServiceArgs(new ComponentName(context,ShellDisplayService.class))
            .daemon(false).tag("pocket-agent-displays").processNameSuffix("display").version(BuildConfig.VERSION_CODE);
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(died);
    }
    private void loseService() {
        Log.w(TAG,"Shizuku UserService disconnected");
        remote=null;binding=false;bindingStartedMs=0;
        synchronized(readyLock) {readyLock.notifyAll();}
        if(!closed) disconnected.run();
    }
    private boolean available() {
        try {return Shizuku.pingBinder();}catch(Throwable e){return false;}
    }
    static String prerequisites() {
        try {
            if(!Shizuku.pingBinder()) return "shizuku_unavailable";
            return Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED?"idle":"permission_required";
        } catch(Throwable e) {return "shizuku_unavailable";}
    }
    String status() {
        if(closed) return "stopped";
        String pre=prerequisites();
        if(!"idle".equals(pre)) return pre;
        if(remote!=null && remote.asBinder().isBinderAlive()) return "ready";
        if(binding && SystemClock.elapsedRealtime()-bindingStartedMs>=35_000) return "service_start_timeout";
        return binding?"binding":"disconnected";
    }
    synchronized void tryBind() {
        if(closed || binding || remote!=null || !available()) return;
        try {
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) return;
            bindingStartedMs=SystemClock.elapsedRealtime();
            binding=true;
            Log.i(TAG,"Shizuku UserService bind requested");
            Shizuku.bindUserService(args,connection);
        } catch(Exception e) {
            binding=false;bindingStartedMs=0;
            Log.e(TAG,"Shizuku UserService binding call failed",e);
        }
    }
    boolean awaitReady(long timeoutMs) throws ControlException {
        if(timeoutMs<0)throw new IllegalArgumentException("timeoutMs");
        if(!"ready".equals(status()))tryBind();
        long end=SystemClock.elapsedRealtime()+timeoutMs;
        synchronized(readyLock) {
            while(!"ready".equals(status())&&!closed&&SystemClock.elapsedRealtime()<end) {
                String state=status();
                if(!"binding".equals(state))break;
                try {readyLock.wait(Math.max(1,end-SystemClock.elapsedRealtime()));}
                catch(InterruptedException e) {Thread.currentThread().interrupt();throw new ControlException(ControlException.Code.RESULT_UNKNOWN,"Interrupted binding Shizuku service");}
            }
        }
        return "ready".equals(status());
    }
    private IDisplayShell require() throws ControlException {
        if(!"ready".equals(status())) {
            tryBind();
            long end=SystemClock.elapsedRealtime()+4000;
            synchronized(readyLock) {
                while(!"ready".equals(status()) && !closed && SystemClock.elapsedRealtime()<end) {
                    if(!"binding".equals(status())) break;
                    try {readyLock.wait(Math.max(1,end-SystemClock.elapsedRealtime()));}
                    catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new ControlException(ControlException.Code.RESULT_UNKNOWN,"Interrupted binding Shizuku service");
                    }
                }
            }
        }
        if(!"ready".equals(status())) Log.w(TAG,"Shizuku bind did not reach ready state: "+status());
        String state=status();
        if(!"ready".equals(state)) throw new ControlException(ControlException.Code.NOT_READY,
            "service_start_timeout".equals(state)
                ? "Shizuku is running, but its privileged UserService failed to start within 35 seconds. Check device and MediaTek compatibility."
                : "binding".equals(state)
                    ? "Shizuku display UserService startup is still pending; retry after startup completes."
                    : "Virtual display backend "+state+"; start Shizuku and authorize Pocket Agent in its UI");
        return remote;
    }
    int createDisplay() throws ControlException {
        try {return require().createDisplay(720,1280,320);}
        catch(RemoteException e) {loseService();throw new ControlException(ControlException.Code.NOT_READY,"Shizuku display service disconnected");}
        catch(RuntimeException e) {throw new ControlException(ControlException.Code.NOT_READY,"Android rejected trusted display creation: "+e.getClass().getSimpleName());}
    }
    org.json.JSONObject terminal(org.json.JSONObject args,String owner,int displayId) throws Exception {
        try {return new org.json.JSONObject(require().terminal(args.toString(),owner,displayId));}
        catch(RemoteException e) {loseService();throw new ControlException(ControlException.Code.RESULT_UNKNOWN,"Shell service disconnected; inspect session output before repeating commands");}
    }
    boolean removeDisplay(int displayId) {
        IDisplayShell service=remote;
        if(service!=null) try {service.removeDisplay(displayId);return true;}
        catch(Exception failure) {Log.w(TAG,"Display retained after cleanup failed",failure);}
        return false;
    }
    void launch(int displayId,String componentName) throws ControlException {
        try {if(!require().launchActivity(displayId,componentName))
            throw new ControlException(ControlException.Code.ACTION_REJECTED,"No private app session opened. Play Store can transfer an idle background task automatically, but a visible Store or another worker's task cannot be taken over. Other personal tasks are preserved.");}
        catch(RemoteException e) {loseService();throw new ControlException(ControlException.Code.NOT_READY,"Virtual display backend disconnected");}
    }
    void openUrl(int displayId,String url) throws ControlException {
        try {if(!require().openUrl(displayId,url))
            throw new ControlException(ControlException.Code.ACTION_REJECTED,"No private URL session opened. Google Play app-detail links automatically use an idle background Store session; a visible Store or another worker's task cannot be taken over. Do not fall back to the physical screen without user authorization.");}
        catch(RemoteException e) {loseService();throw new ControlException(ControlException.Code.NOT_READY,"Virtual display backend disconnected");}
    }
    String preview(int displayId) throws ControlException {
        try {return require().previewDisplay(displayId);}
        catch(RemoteException e){loseService();throw new ControlException(ControlException.Code.NOT_READY,"Private screen is no longer available");}
    }
    void showClock(int displayId,String screen) throws ControlException {
        try {if(!require().showClock(displayId,screen))
            throw new ControlException(ControlException.Code.UNSUPPORTED,"No clock app accepted this screen intent");}
        catch(RemoteException e) {loseService();throw new ControlException(ControlException.Code.NOT_READY,"Virtual display backend disconnected");}
    }
    void key(int displayId,int code) throws ControlException {
        try {if(!require().injectKey(displayId,code))
            throw new ControlException(ControlException.Code.ACTION_REJECTED,"Android rejected the virtual display key event");}
        catch(RemoteException e) {loseService();throw new ControlException(ControlException.Code.NOT_READY,"Virtual display backend disconnected");}
    }
    void text(int displayId,String text) throws ControlException {
        try {if(!require().injectText(displayId,text))throw new ControlException(ControlException.Code.RESULT_UNKNOWN,"Keyboard input was not fully confirmed; observe before retrying");}
        catch(RemoteException e) {loseService();throw new ControlException(ControlException.Code.RESULT_UNKNOWN,"Keyboard input connection lost; observe before retrying");}
    }
    boolean repairAccessibility(String componentName,boolean restoreMissing) throws ControlException {
        try {return require().repairAccessibility(componentName,restoreMissing);}
        catch(RemoteException e) {loseService();throw new ControlException(ControlException.Code.NOT_READY,"Virtual display backend disconnected");}
    }
    @Override public synchronized void close() {
        closed=true;remote=null;
        synchronized(readyLock) {readyLock.notifyAll();}
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(died);
        try {if(available()) Shizuku.unbindUserService(args,connection,removeServiceOnClose);}catch(Exception ignored){}
    }
}
