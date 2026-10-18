package dev.pocket.agent;

import android.content.Context;
import dev.pocket.android.AndroidScreenBackend;
import dev.pocket.control.ControlBackend;
import dev.pocket.control.ControlException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import static dev.pocket.control.ControlException.Code.*;

/** Maps each conversation to one independent shell-owned display, never display zero. */
final class AgentDisplayCoordinator implements AutoCloseable {
    static final int MAX_DISPLAYS=3;
    private final Context context;
    private final Map<String,Integer> displays=new LinkedHashMap<>();
    private final Map<String,String> pendingPackages=new LinkedHashMap<>();
    private final Map<String,Long> pendingUntil=new LinkedHashMap<>();
    private final Map<String,String> pendingScreens=new LinkedHashMap<>();
    private final Map<String,String> lastScreens=new LinkedHashMap<>();
    private ShizukuDisplayClient client;

    AgentDisplayCoordinator(Context context) {this.context=context.getApplicationContext();}

    private synchronized ShizukuDisplayClient client() {
        // A bind that never called back cannot be retried on the same client:
        // its binding flag stays set. Retire the whole connection on timeout.
        if(client!=null&&"service_start_timeout".equals(client.status())) {
            ShizukuDisplayClient stuck=client;client=null;stuck.close();
        }
        if(client==null) client=new ShizukuDisplayClient(context,this::invalidate);
        return client;
    }

    synchronized JSONObject acquire(String owner) throws ControlException {
        Integer existing=displays.get(owner);
        if(existing!=null) return Json.obj("status","acquired","display_id",existing,"owner",owner);
        if(displays.size()>=MAX_DISPLAYS)
            throw new ControlException(NOT_READY,"All three displays are allocated");
        try {
            int id=client().createDisplay();
            if(id<=0||displays.containsValue(id)) {
                if(id>0&&!displays.containsValue(id))client().removeDisplay(id);
                throw new ControlException(NOT_READY,"Virtual display backend supplied invalid or duplicate display ID");
            }
            displays.put(owner,id);
            // Virtual displays have no launcher window on some OEMs. A neutral
            // Settings activity seeds the screen before the first observation.
            try {
                client().launch(id,"com.android.settings/.Settings");
                pendingPackages.put(owner,"com.android.settings");
                pendingUntil.put(owner,android.os.SystemClock.uptimeMillis()+450);
            } catch(ControlException unavailable) {
                if(unavailable.code!=ACTION_REJECTED && unavailable.code!=UNSUPPORTED) {
                    // Backend loss must not be misreported as an acquired lease.
                    release(owner);
                    throw unavailable;
                }
                // Only an unavailable optional Settings screen is tolerable.
            }
            return Json.obj("status","acquired","display_id",id,"owner",owner);
        } catch(ControlException | RuntimeException error) {
            // Retain the in-flight Shizuku bind. Closing it after a 4-second
            // startup timeout causes every retry to begin from scratch.
            throw error;
        }
    }

    synchronized int display(String owner) throws ControlException {
        Integer id=displays.get(owner);
        if(id==null||id<=0) throw new ControlException(NOT_READY,"No virtual display for conversation");
        if(client==null||!"ready".equals(client.status()))
            throw new ControlException(NOT_READY,"Shizuku backend disconnected; observe after recovery");
        return id;
    }

    synchronized AndroidScreenBackend screen(String owner) throws ControlException {
        int id=display(owner);
        AgentAccessibility observer=AgentAccessibility.connected;
        if(observer==null) throw new ControlException(PERMISSION_REQUIRED,"Enable Pocket Agent accessibility to use virtual displays");
        return observer.backend(id);
    }

    synchronized void launch(String owner,String component) throws ControlException {
        client().launch(display(owner),component);
        android.content.ComponentName target=android.content.ComponentName.unflattenFromString(component);
        pendingUntil.put(owner,android.os.SystemClock.uptimeMillis()+450);
        pendingScreens.remove(owner);
        if(target!=null)pendingPackages.put(owner,target.getPackageName());
    }
    // A clock or browser can navigate within the *same* package. The source
    // window must actually change; a fixed post-launch delay alone is not proof.
    private static String signature(ControlBackend.Observation observation) {
        return observation.packageName()+"|"+observation.windowId()+"|"+observation.nodes();
    }
    private synchronized String beforeLaunch(String owner) {
        try {return signature(screen(owner).observe(false,1280,150));}
        catch(ControlException unavailable) {return lastScreens.get(owner);}
    }
    synchronized boolean observationReady(String owner,ControlBackend.Observation observation) {
        Long delay=pendingUntil.get(owner);
        if(delay!=null && android.os.SystemClock.uptimeMillis()<delay)return false;
        String expected=pendingPackages.get(owner);
        if(expected!=null&&!expected.equals(observation.packageName()))return false;
        String visible=signature(observation);
        String previous=pendingScreens.get(owner);
        if(previous!=null&&previous.equals(visible))return false;
        pendingUntil.remove(owner);
        pendingPackages.remove(owner);
        pendingScreens.remove(owner);
        lastScreens.put(owner,visible);
        return true;
    }
    synchronized void openUrl(String owner,String url) throws ControlException {
        String previous=beforeLaunch(owner);
        int id=display(owner);client().openUrl(id,url);
        markTransition(owner);
        if(previous!=null)pendingScreens.put(owner,previous);
    }
    synchronized void markTransition(String owner) {
        pendingPackages.remove(owner);
        pendingScreens.remove(owner);
        pendingUntil.put(owner,android.os.SystemClock.uptimeMillis()+1600);
    }
    synchronized void showClock(String owner,String screen) throws ControlException {
        String previous=beforeLaunch(owner);
        int id=display(owner);client().showClock(id,screen);
        markTransition(owner);
        if(previous!=null)pendingScreens.put(owner,previous);
    }
    synchronized void key(String owner,int code) throws ControlException {client().key(display(owner),code);}

    synchronized void release(String owner) {
        Integer id=displays.remove(owner);
        pendingPackages.remove(owner);
        pendingUntil.remove(owner);
        pendingScreens.remove(owner);
        lastScreens.remove(owner);
        if(id!=null) {
            AgentAccessibility observer=AgentAccessibility.connected;
            if(observer!=null) observer.release(id);
            if(client!=null) client.removeDisplay(id);
        }
        // Keep the Shizuku UserService bound for immediate reacquisition.
    }

    private synchronized void invalidate() {
        AgentAccessibility observer=AgentAccessibility.connected;
        if(observer!=null) for(int id:displays.values())observer.release(id);
        displays.clear();
        pendingPackages.clear();
        pendingUntil.clear();
        pendingScreens.clear();
        lastScreens.clear();
    }

    synchronized int activeCount() {return displays.size();}
    String backendStatus() {ShizukuDisplayClient present=client;return present==null?ShizukuDisplayClient.prerequisites():present.status();}

    @Override public synchronized void close() {
        for(String owner:java.util.List.copyOf(displays.keySet()))release(owner);
        if(client!=null) {ShizukuDisplayClient closing=client;client=null;closing.close();}
    }
}