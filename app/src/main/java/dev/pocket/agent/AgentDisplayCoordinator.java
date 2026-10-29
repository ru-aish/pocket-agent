package dev.pocket.agent;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.view.Display;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import dev.pocket.control.ControlException;
import org.json.JSONObject;
import static dev.pocket.control.ControlException.Code.*;

/** Maps each conversation to one independent shell-owned display, with explicit opt-in access to display zero. */
final class AgentDisplayCoordinator implements AutoCloseable {
    static final int MAX_DISPLAYS=3;
    // The privileged UserService can outlive a Node isolate or even its RuntimeHost.
    // All NativeTools instances in this process must share its lease ownership.
    private static AgentDisplayCoordinator shared;
    static synchronized AgentDisplayCoordinator shared(Context context) {
        if(shared==null)shared=new AgentDisplayCoordinator(context);
        return shared;
    }
    private final Context context;
    private final DisplayLeases leases=new DisplayLeases();
    private final Set<String> retiring=new LinkedHashSet<>();
    private final Set<String> removing=new LinkedHashSet<>();
    private final ScheduledExecutorService cleanup=Executors.newSingleThreadScheduledExecutor(task->{
        Thread worker=new Thread(task,"PocketDisplayCleanup");worker.setDaemon(true);return worker;
    });
    private boolean retryScheduled,closeWhenIdle;
    private int retryAttempt;
    private ShizukuDisplayClient client;

    AgentDisplayCoordinator(Context context) {this.context=context.getApplicationContext();}

    private synchronized ShizukuDisplayClient client() {
        if(client==null) client=new ShizukuDisplayClient(context,this::invalidate);
        return client;
    }

    synchronized JSONObject acquire(String owner,Object generation) throws ControlException {
        // Retirement runs off-thread. A stalled shell cleanup of some other
        // owner's task must not delay an acquisition with available capacity.
        if(retiring.contains(owner)||removing.contains(owner))
            throw new ControlException(NOT_READY,"Previous display cleanup remains pending");
        closeWhenIdle=false;
        Integer existing=leases.get(owner);
        if(existing!=null) {
            if(!leases.ownedBy(owner,generation))
                throw new ControlException(NOT_READY,"Virtual display is still owned by another runtime");
            if(leases.provisional(owner))
                throw new ControlException(NOT_READY,"Virtual display bootstrap failed; cleanup remains pending");
            return Json.obj("status","acquired","display_id",existing,"owner",owner);
        }
        if(leases.size()>=MAX_DISPLAYS)
            throw new ControlException(NOT_READY,"All three displays are allocated");
        ShizukuDisplayClient active=client();
        int id=active.createDisplay();
        if(id<=0||leases.containsValue(id))
            throw new ControlException(NOT_READY,"Virtual display backend supplied invalid or duplicate display ID");
        // A failed bootstrap must still have a named owner for later cleanup.
        leases.provision(owner,id,generation);
        try {
            active.launch(id,new ComponentName(context,MainActivity.class).flattenToString());
            leases.activate(owner,id);
        } catch(ControlException error) {
            if(!leases.release(owner,generation,active::removeDisplay))
                throw new ControlException(RESULT_UNKNOWN,"Display bootstrap failed; native cleanup unconfirmed and lease retained");
            throw error;
        } catch(RuntimeException error) {
            if(!leases.release(owner,generation,active::removeDisplay))
                throw new ControlException(RESULT_UNKNOWN,"Display bootstrap failed; native cleanup unconfirmed and lease retained");
            throw new ControlException(NOT_READY,"Virtual display backend failed while launching Pocket Agent");
        }
        return Json.obj("status","acquired","display_id",id,"owner",owner);
    }

    synchronized int display(String owner,Object generation) throws ControlException {
        Integer id=leases.get(owner);
        if(id==null||id<=0||leases.provisional(owner)||!leases.ownedBy(owner,generation))
            throw new ControlException(NOT_READY,"No ready virtual display for this runtime and conversation");
        if(client==null||!"ready".equals(client.status()))
            throw new ControlException(NOT_READY,"Shizuku backend disconnected; observe after recovery");
        return id;
    }

    synchronized int display(String owner,boolean physical,Object generation) throws ControlException {
        return physical?Display.DEFAULT_DISPLAY:display(owner,generation);
    }

    synchronized AccessibilityProxyBackend screen(String owner,boolean physical,Object generation) throws ControlException {
        int id=display(owner,physical,generation);
        AccessibilityProxyBackend backend=new AccessibilityProxyBackend(context,id);
        AccessibilityProxyBackend.ConnectionState state=AccessibilityProxyBackend.connectionState(context);
        if(state==AccessibilityProxyBackend.ConnectionState.UNKNOWN)
            throw new ControlException(NOT_READY,"Accessibility bridge is busy; retry after the in-flight operation completes");
        if(state==AccessibilityProxyBackend.ConnectionState.DISCONNECTED)recoverAccessibility();
        state=AccessibilityProxyBackend.connectionState(context);
        if(state!=AccessibilityProxyBackend.ConnectionState.CONNECTED)
            throw new ControlException(NOT_READY,"Pocket Agent accessibility has not rebound yet; retry after recovery");
        return backend;
    }

    private boolean accessibilityEnabledByUser(ComponentName target) {
        try {
            if(Settings.Secure.getInt(context.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0)!=1)return false;
            String raw=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if(raw==null)return false;
            for(String item:raw.split(":"))if(target.equals(ComponentName.unflattenFromString(item.trim())))return true;
        } catch(RuntimeException ignored) {}
        return false;
    }

    private void recoverAccessibility() throws ControlException {
        ComponentName component=new ComponentName(context,AgentAccessibility.class);
        boolean enabled=accessibilityEnabledByUser(component);
        boolean pending=AccessibilityRepairState.pending(context);
        if(!enabled&&!pending)
            throw new ControlException(PERMISSION_REQUIRED,"Enable Pocket Agent accessibility to control Android displays");
        try {
            context.startForegroundService(new Intent(context,AccessibilityRepairService.class));
        } catch(RuntimeException failure) {
            throw new ControlException(NOT_READY,"Pocket Agent accessibility recovery could not be started");
        }
        long deadline=android.os.SystemClock.elapsedRealtime()+4500;
        while(android.os.SystemClock.elapsedRealtime()<deadline) {
            if(AccessibilityProxyBackend.connected(context))return;
            try {Thread.sleep(100);} catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ControlException(RESULT_UNKNOWN,"Interrupted while waiting for accessibility recovery");
            }
        }
        if(!accessibilityEnabledByUser(component)&&!AccessibilityRepairState.pending(context))
            throw new ControlException(PERMISSION_REQUIRED,"Pocket Agent accessibility is disabled");
    }

    private void startPhysical(String owner,Intent source) throws ControlException {
        screen(owner,true,null).startActivity(source);
    }

    synchronized void launch(String owner,Intent launch,boolean physical,Object generation) throws ControlException {
        if(physical){startPhysical(owner,launch);return;}
        if(launch.getComponent()==null)throw new ControlException(UNSUPPORTED,"No launcher component visible for this package");
        int id=display(owner,generation);client().launch(id,launch.getComponent().flattenToString());
    }

    synchronized void openUrl(String owner,String url,boolean physical,Object generation) throws ControlException {
        if(physical){startPhysical(owner,new Intent(Intent.ACTION_VIEW,Uri.parse(url)));return;}
        int id=display(owner,generation);client().openUrl(id,url);
    }

    synchronized void showClock(String owner,String screen,boolean physical,Object generation) throws ControlException {
        if(physical) {
            String action=switch(screen) {
                case "alarms"->AlarmClock.ACTION_SHOW_ALARMS;
                case "timers"->AlarmClock.ACTION_SHOW_TIMERS;
                default->throw new ControlException(INVALID_ARGUMENT,"Expected alarms or timers");
            };
            startPhysical(owner,new Intent(action));return;
        }
        int id=display(owner,generation);client().showClock(id,screen);
    }

    synchronized void key(String owner,int code,Object generation) throws ControlException {int id=display(owner,generation);client().key(id,code);}

    void release(String owner,Object generation) {
        final Integer id;
        final ShizukuDisplayClient active;
        synchronized(this) {
            id=leases.get(owner);
            if(id==null) {
                retiring.remove(owner);
                if(retiring.isEmpty())retryAttempt=0;
                closeIfUnused();
                return;
            }
            if(!leases.ownedBy(owner,generation))
                throw new IllegalStateException("Display lease belongs to another runtime");
            if(!removing.add(owner))
                throw new IllegalStateException("Display cleanup is already in progress");
            active=client;
        }
        // Do not hold the coordinator monitor across privileged task cleanup:
        // removing a borrowed task can stall or time out in the shell service.
        boolean removed=false,stillOurs=false;
        try {
            removed=active!=null&&active.removeDisplay(id);
            if(removed) {
                boolean valid;
                synchronized(this) {valid=id.equals(leases.get(owner))&&leases.ownedBy(owner,generation);}
                if(valid)new AccessibilityProxyBackend(context,id).release();
            }
        } finally {
            synchronized(this) {
                removing.remove(owner);
                stillOurs=id.equals(leases.get(owner))&&leases.ownedBy(owner,generation);
                if(removed&&stillOurs) {
                    leases.release(owner,generation,ignored->true);
                    retiring.remove(owner);
                }
                if(retiring.isEmpty())retryAttempt=0;
                closeIfUnused();
            }
        }
        if(!removed&&stillOurs)
            throw new IllegalStateException("Virtual display cleanup did not complete; lease retained to protect existing tasks");
    }

    // A Node-runtime replacement must not lose the privileged service's lease map.
    // Retire only the old NativeTools instance's owners; another runtime may
    // already have acquired a different owner on this process-wide coordinator.
    synchronized void retireOwners(Set<String> owners,Object generation) {
        for(String owner:owners)if(leases.ownedBy(owner,generation))retiring.add(owner);
        closeWhenIdle=true;
        scheduleRetry(true);
        closeIfUnused();
    }

    private void retryRetiring() {
        Set<String> pending;
        synchronized(this) {pending=new LinkedHashSet<>(retiring);}
        try {
            DisplayLeases.releaseIndependently(pending,owner->{
                Object generation;
                synchronized(this) {
                    if(!retiring.contains(owner))return true;
                    generation=leases.generation(owner);
                }
                release(owner,generation);
                return true;
            });
        } finally {
            synchronized(this) {
                retryScheduled=false;
                if(retiring.isEmpty()) {retryAttempt=0;closeIfUnused();}
                else scheduleRetry(false);
            }
        }
    }

    private synchronized void scheduleRetry(boolean immediately) {
        if(retryScheduled||retiring.isEmpty())return;
        retryScheduled=true;
        long delay=immediately?0:Math.min(200L*(1L<<Math.min(retryAttempt++,5)),5000L);
        cleanup.schedule(this::retryRetiring,delay,TimeUnit.MILLISECONDS);
    }

    private void closeIfUnused() {
        if(!closeWhenIdle||!retiring.isEmpty()||leases.size()!=0)return;
        closeWhenIdle=false;
        if(client!=null) {ShizukuDisplayClient closing=client;client=null;closing.close();}
    }

    private synchronized void invalidate() {
        // Binder death confirms that the old UserService no longer owns displays.
        for(int id:leases.ids())new AccessibilityProxyBackend(context,id).release();
        leases.clear();
        retiring.clear();
        closeIfUnused();
    }

    synchronized int activeCount() {return leases.size();}
    static final class AvailableDisplay {
        final int id;
        final String owner,state;
        final Object generation;
        AvailableDisplay(int id,String owner,String state,Object generation) {
            this.id=id;this.owner=owner;this.state=state;this.generation=generation;
        }
        boolean viewable() {return "ready".equals(state);}
    }
    synchronized List<AvailableDisplay> availableDisplays() {
        List<AvailableDisplay> result=new ArrayList<>();
        for(String owner:leases.owners()) {
            String state=retiring.contains(owner)||removing.contains(owner)?"cleanup pending"
                :leases.provisional(owner)?"starting"
                :client==null||!"ready".equals(client.status())?"disconnected":"ready";
            result.add(new AvailableDisplay(leases.get(owner),owner,state,leases.generation(owner)));
        }
        return result;
    }
    synchronized ShizukuDisplayClient previewClient(AvailableDisplay display) {
        if(!Integer.valueOf(display.id).equals(leases.get(display.owner))
            ||!leases.ownedBy(display.owner,display.generation)||leases.provisional(display.owner)
            ||retiring.contains(display.owner)||removing.contains(display.owner)
            ||client==null||!"ready".equals(client.status()))
            throw new IllegalStateException("This display is no longer available");
        return client;
    }
    boolean accessibilityConnected() {return AccessibilityProxyBackend.connected(context);}
    String backendStatus() {ShizukuDisplayClient present=client;return present==null?ShizukuDisplayClient.prerequisites():present.status();}

    @Override public synchronized void close() {
        retiring.addAll(leases.owners());
        closeWhenIdle=true;
        scheduleRetry(true);
        closeIfUnused();
    }
}
