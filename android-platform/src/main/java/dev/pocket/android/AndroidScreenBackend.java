package dev.pocket.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityWindowInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import dev.pocket.control.ControlBackend;
import dev.pocket.control.ControlException;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static dev.pocket.control.ControlException.Code.*;

/** Fresh Android-only driver. Invoke public methods on a worker, never the main thread. */
public final class AndroidScreenBackend implements ControlBackend {
    private final AccessibilityService service;
    private final int displayId;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Observation> snapshots = new LinkedHashMap<>();
    private final Map<String, Long> snapshotRevisions = new LinkedHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong interactionTime = new java.util.concurrent.atomic.AtomicLong();
    private static final long MAX_SNAPSHOT_AGE_MS = 15_000;
    private static final long SCREENSHOT_THROTTLE_RETRY_MS = 1_100;
    private static final int SCREENSHOT_THROTTLE_RETRIES = 1;
    private static final long TEXT_VERIFICATION_TIMEOUT_MS = 1_500;
    private static final long TEXT_VERIFICATION_POLL_MS = 75;
    private static final int MAX_BACKGROUND_PRUNE_HITS = 32;

    public AndroidScreenBackend(AccessibilityService service) {this(service,Display.DEFAULT_DISPLAY);}
    public AndroidScreenBackend(AccessibilityService service,int displayId) {if(displayId<0)throw new IllegalArgumentException("displayId");this.service=service;this.displayId=displayId;}
    /** May be called from the accessibility callback without waiting on the worker lock. */
    public void noteInteraction(long eventTime) {
        long time=eventTime>0?eventTime:android.os.SystemClock.uptimeMillis();
        interactionTime.accumulateAndGet(time,Math::max);
    }

    private <T> T onMain(Callable<T> action) throws ControlException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new ControlException(INVALID_ARGUMENT, "Run the backend on a worker thread");
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        main.post(() -> {
            if (future.isDone()) return;
            try { future.complete(action.call()); }
            catch (Throwable e) { future.completeExceptionally(e); }
        });
        return await(future, 5_000);
    }

    private static <T> T await(CompletableFuture<T> future, long millis) throws ControlException {
        try { return future.get(millis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); future.cancel(false);
            throw new ControlException(RESULT_UNKNOWN, "Interrupted; inspect before repeating the action");
        } catch (TimeoutException e) {
            future.cancel(false);
            throw new ControlException(RESULT_UNKNOWN, "Timed out; inspect before repeating the action");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ControlException c) throw c;
            if (cause instanceof SecurityException) throw new ControlException(PERMISSION_REQUIRED, "Android denied the operation");
            throw new ControlException(ACTION_REJECTED, cause.getClass().getSimpleName());
        }
    }

    private boolean interactive() {
        if(displayId!=Display.DEFAULT_DISPLAY)return service.getSystemService(DisplayManager.class).getDisplay(displayId)!=null;
        return ((PowerManager) service.getSystemService(Context.POWER_SERVICE)).isInteractive();
    }
    private boolean locked() {
        if(displayId!=Display.DEFAULT_DISPLAY)return false;
        return ((KeyguardManager) service.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked();
    }
    private Rect dimensions() {
        if(displayId!=Display.DEFAULT_DISPLAY){
            Display display=service.getSystemService(DisplayManager.class).getDisplay(displayId);
            if(display==null)return new Rect();
            Point size=new Point();display.getRealSize(size);return new Rect(0,0,size.x,size.y);
        }
        return ((WindowManager) service.getSystemService(Context.WINDOW_SERVICE)).getMaximumWindowMetrics().getBounds();
    }
    private static boolean controllableWindow(AccessibilityWindowInfo window) {
        int type=window.getType();
        return type==AccessibilityWindowInfo.TYPE_APPLICATION||type==AccessibilityWindowInfo.TYPE_SYSTEM;
    }
    private AccessibilityNodeInfo windowRoot(List<AccessibilityWindowInfo> windows,boolean active,boolean focused,int type) {
        if(windows==null)return null;
        for(AccessibilityWindowInfo window:windows) {
            if(!controllableWindow(window))continue;
            if(active&&!window.isActive())continue;
            if(focused&&!window.isFocused())continue;
            if(type!=0&&window.getType()!=type)continue;
            AccessibilityNodeInfo candidate=window.getRoot();
            if(candidate!=null)return candidate;
        }
        return null;
    }
    private AccessibilityNodeInfo freshRoot() {
        if (android.os.Build.VERSION.SDK_INT>=33) service.clearCache();
        SparseArray<List<AccessibilityWindowInfo>> all=service.getWindowsOnAllDisplays();
        List<AccessibilityWindowInfo> windows=all==null?null:all.get(displayId);
        AccessibilityNodeInfo root=windowRoot(windows,true,false,0);
        if(root==null)root=windowRoot(windows,false,true,0);
        if(root==null)root=windowRoot(windows,false,false,AccessibilityWindowInfo.TYPE_APPLICATION);
        if(root==null)root=windowRoot(windows,false,false,AccessibilityWindowInfo.TYPE_SYSTEM);
        if(root!=null && android.os.Build.VERSION.SDK_INT<33 && !root.refresh()) {root.recycle();return null;}
        return root;
    }

    @Override public synchronized Observation observe(boolean image, int maxDimension, int maxNodes) throws ControlException {
        long[] capturedUptime = {0};
        if (maxDimension < 256 || maxDimension > 2048 || maxNodes < 0 || maxNodes > 500) {
            throw new ControlException(INVALID_ARGUMENT, "Image dimension 256..2048; nodes 0..500");
        }
        Observation base = onMain(() -> {
            capturedUptime[0]=android.os.SystemClock.uptimeMillis();
            Rect bounds = dimensions();
            AccessibilityNodeInfo root = freshRoot();
            if(root==null) throw new ControlException(NOT_READY,"No active accessibility window yet; wait for the screen transition and observe again");
            List<Node> nodes = new ArrayList<>();
            String pkg = ""; int window = -1; boolean[] truncated = {false};
            if (root != null) {
                try {
                    pkg = String.valueOf(root.getPackageName()); window = root.getWindowId();
                    if (maxNodes > 0) dump(root, nodes, maxNodes, 0, truncated);
                } finally { root.recycle(); }
            }
            return new Observation(UUID.randomUUID().toString(), System.currentTimeMillis(), pkg, window,
                bounds.width(), bounds.height(), interactive(), locked(), nodes, truncated[0], null, null, 0, 0);
        });
        if (image) {
            if (!base.interactive()) throw new ControlException(SCREEN_OFF, "The display is asleep; no usable screenshot");
            Capture captured = screenshot(maxDimension);
            base = new Observation(base.snapshotId(), base.capturedAtMs(), base.packageName(), base.windowId(),
                base.coordinateWidth(), base.coordinateHeight(), base.interactive(), base.locked(), base.nodes(),
                base.nodesTruncated(), captured.bytes, "image/jpeg", captured.width, captured.height);
            Observation expected = base;
            onMain(() -> { validateContext(expected); return null; });
        }
        // Retain only target metadata, never screenshots or captured app text.
        if (capturedUptime[0] < interactionTime.get()) throw new ControlException(STALE_OBSERVATION,"Screen changed during observation; observe again");
        snapshotRevisions.put(base.snapshotId(), capturedUptime[0]);
        snapshots.put(base.snapshotId(), new Observation(base.snapshotId(), base.capturedAtMs(), base.packageName(),
            base.windowId(), base.coordinateWidth(), base.coordinateHeight(), base.interactive(), base.locked(),
            List.of(), false, null, null, 0, 0));
        while (snapshots.size() > 8) {String oldest=snapshots.keySet().iterator().next();snapshots.remove(oldest);snapshotRevisions.remove(oldest);}
        return base;
    }

    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }
    private static String clipped(String text) { return text.length() <= 1000 ? text : text.substring(0,1000); }
    private static void dump(AccessibilityNodeInfo node, List<Node> nodes, int limit, int depth, boolean[] truncated) {
        if (nodes.size() >= limit || depth > 30) { truncated[0] = true; return; }
        if(android.os.Build.VERSION.SDK_INT<33 && !node.refresh()) {truncated[0]=true;return;}
        if (node.isVisibleToUser()) {
            Rect b = new Rect(); node.getBoundsInScreen(b);
            boolean password = node.isPassword();
            nodes.add(new Node(nodes.size(), text(node.getViewIdResourceName()),
                password ? "[password]" : clipped(text(node.getText())),
                password ? "" : clipped(text(node.getContentDescription())), text(node.getClassName()),
                b.left,b.top,b.right,b.bottom,node.isEnabled(),node.isFocused(),node.isEditable(),
                node.isClickable(),node.isLongClickable(),node.isScrollable()));
        }
        for (int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) try { dump(child,nodes,limit,depth+1,truncated); } finally { child.recycle(); }
            if (nodes.size() >= limit) { if (i + 1 < node.getChildCount()) truncated[0] = true; break; }
        }
    }

    private record Capture(byte[] bytes, int width, int height) {}
    private Capture screenshot(int maxDimension) throws ControlException {
        CompletableFuture<Capture> future = new CompletableFuture<>();
        onMain(() -> {
            takeScreenshotAttempt(maxDimension,future,SCREENSHOT_THROTTLE_RETRIES);
            return null;
        });
        return await(future,7_000);
    }
    private void takeScreenshotAttempt(int maxDimension,CompletableFuture<Capture> future,int throttleRetriesRemaining) {
        service.takeScreenshot(displayId,service.getMainExecutor(),new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                Bitmap hardware=null,software=null,scaled=null;
                try {
                    if(future.isDone())return;
                    hardware=Bitmap.wrapHardwareBuffer(screenshot.getHardwareBuffer(),screenshot.getColorSpace());
                    if(hardware==null)throw new IllegalStateException("Empty screenshot buffer");
                    software=hardware.copy(Bitmap.Config.ARGB_8888,false);
                    double scale=Math.min(1.0,(double)maxDimension/Math.max(software.getWidth(),software.getHeight()));
                    scaled=Bitmap.createScaledBitmap(software,Math.max(1,(int)Math.round(software.getWidth()*scale)),Math.max(1,(int)Math.round(software.getHeight()*scale)),true);
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    if(!scaled.compress(Bitmap.CompressFormat.JPEG,75,bytes))throw new IllegalStateException("JPEG failed");
                    future.complete(new Capture(bytes.toByteArray(),scaled.getWidth(),scaled.getHeight()));
                } catch(Throwable error) {future.completeExceptionally(error);}
                finally {
                    if(scaled!=null&&scaled!=software)scaled.recycle();
                    if(software!=null)software.recycle();
                    if(hardware!=null)hardware.recycle();
                    screenshot.getHardwareBuffer().close();
                }
            }
            @Override public void onFailure(int errorCode) {
                if(errorCode==AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT&&throttleRetriesRemaining>0&&!future.isDone()) {
                    main.postDelayed(()->{
                        if(future.isDone())return;
                        try {takeScreenshotAttempt(maxDimension,future,throttleRetriesRemaining-1);}
                        catch(Throwable error) {future.completeExceptionally(error);}
                    },SCREENSHOT_THROTTLE_RETRY_MS);
                    return;
                }
                future.completeExceptionally(new ControlException(ACTION_REJECTED,"Screenshot rejected by Android: "+errorCode));
            }
        });
    }

    private void validateContext(Observation expected) throws ControlException {
        Rect d = dimensions();
        AccessibilityNodeInfo root = freshRoot();
        if (root == null) throw new ControlException(NOT_READY,"No active accessibility window");
        try {
            if (!expected.packageName().equals(text(root.getPackageName())) || expected.windowId()!=root.getWindowId()
                || d.width()!=expected.coordinateWidth() || d.height()!=expected.coordinateHeight()) {
                throw new ControlException(STALE_OBSERVATION,"Window, package, or display geometry changed; observe again");
            }
        } finally { root.recycle(); }
    }

    private void validateFreshness(Observation observed) throws ControlException {
        if (System.currentTimeMillis()-observed.capturedAtMs()>MAX_SNAPSHOT_AGE_MS
            || snapshotRevisions.getOrDefault(observed.snapshotId(),0L) < interactionTime.get())
            throw new ControlException(STALE_OBSERVATION,"Screen interaction occurred; observe again");
    }

    public synchronized void validateAction(Action a) throws ControlException {prevalidateAction(a);}
    public synchronized void prepareKeyboardText(Action a) throws ControlException {
        Observation observed=prevalidateAction(a);
        onMain(()->{
            validateContext(observed);validateFreshness(observed);
            AccessibilityNodeInfo root=freshRoot(),node=null;
            try {
                if(root==null)throw new ControlException(NOT_READY,"No active window");
                node=targetNode(root,a);
                if(!node.isEditable()||!node.isFocused())throw new ControlException(ACTION_REJECTED,"Click the intended editable field and observe before keyboard typing");
                if(a.replace()) {
                    int length=node.isShowingHintText()?0:text(node.getText()).length();
                    Bundle selection=new Bundle();selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,0);selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,length);
                    boolean alreadySelected=node.getTextSelectionStart()==0&&node.getTextSelectionEnd()==length;
                    if(!alreadySelected&&(!node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,selection)||!node.refresh()
                        ||node.getTextSelectionStart()!=0||node.getTextSelectionEnd()!=length))
                        throw new ControlException(ACTION_REJECTED,"Could not confirm text selection; no keyboard text was sent");
                }
                return null;
            } finally {if(node!=null)node.recycle();if(root!=null)root.recycle();}
        });
    }

    private Observation prevalidateAction(Action a) throws ControlException {
        if (a == null || a.kind() == null) throw new ControlException(INVALID_ARGUMENT,"Action kind required");
        Observation observed = snapshots.get(a.snapshotId());
        if (observed == null || System.currentTimeMillis()-observed.capturedAtMs()>MAX_SNAPSHOT_AGE_MS) {
            throw new ControlException(STALE_OBSERVATION,"A recent snapshot ID is required");
        }
        validateFreshness(observed);
        onMain(() -> {
            if (!interactive()) throw new ControlException(SCREEN_OFF,"Display asleep");
            if (locked()) throw new ControlException(LOCKED,"Unlock the device before controlling apps");
            validateContext(observed); return null;
        });
        return observed;
    }

    @Override public synchronized Result execute(Action a) throws ControlException {
        Observation observed=prevalidateAction(a);
        if (a.kind()==Kind.TAP || a.kind()==Kind.LONG_PRESS || a.kind()==Kind.SWIPE) {
            point(a.x(),a.y(),observed);
            if (a.kind()==Kind.SWIPE) point(a.endX(),a.endY(),observed);
            int duration = a.kind()==Kind.TAP ? 100 : a.durationMs();
            if (duration<100 || duration>4000) throw new ControlException(INVALID_ARGUMENT,"Duration 100..4000ms");
            return gesture(a,duration);
        }
        if (a.selector()==null && (a.x()!=null || a.y()!=null)) point(a.x(),a.y(),observed);
        if(a.kind()==Kind.TYPE)return setText(a,observed);
        return onMain(() -> { validateContext(observed); validateFreshness(observed); return nativeAction(a); });
    }

    private static void point(Integer x,Integer y,Observation o) throws ControlException {
        if (x==null || y==null || x<0 || y<0 || x>=o.coordinateWidth() || y>=o.coordinateHeight()) {
            throw new ControlException(INVALID_ARGUMENT,"Point outside full device coordinates");
        }
    }

    private Result gesture(Action a,int duration) throws ControlException {
        CompletableFuture<Result> future = new CompletableFuture<>();
        onMain(() -> {
            Observation expected = snapshots.get(a.snapshotId());
            validateContext(expected);
            validateFreshness(expected);
            Path path = new Path(); path.moveTo(a.x(),a.y());
            if (a.kind()==Kind.SWIPE) path.lineTo(a.endX(),a.endY());
            GestureDescription g = new GestureDescription.Builder().setDisplayId(displayId).addStroke(new GestureDescription.StrokeDescription(path,0,duration)).build();
            boolean dispatched = service.dispatchGesture(g,new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription ignored) { future.complete(new Result("dispatched","Gesture completed; task outcome not yet verified")); }
                @Override public void onCancelled(GestureDescription ignored) { future.completeExceptionally(new ControlException(RESULT_UNKNOWN,"Gesture cancelled; observe before repeating")); }
            },main);
            if (!dispatched) future.completeExceptionally(new ControlException(ACTION_REJECTED,"Gesture was not dispatched"));
            return null;
        });
        return await(future,duration+2000L);
    }

    private Result nativeAction(Action a) throws ControlException {
        if (a.kind()==Kind.KEY && !"enter".equals(a.key())) {
            if(displayId!=Display.DEFAULT_DISPLAY)throw new ControlException(UNSUPPORTED,"Virtual screen navigation requires display-targeted key injection");
            int key = switch (a.key()==null ? "" : a.key()) {
                case "home" -> AccessibilityService.GLOBAL_ACTION_HOME;
                case "back" -> AccessibilityService.GLOBAL_ACTION_BACK;
                case "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS;
                default -> throw new ControlException(UNSUPPORTED,"Android key supports home/back/recents/enter");
            };
            if (!service.performGlobalAction(key)) throw new ControlException(ACTION_REJECTED,"System navigation rejected");
            return new Result("dispatched","Android navigation accepted");
        }
        AccessibilityNodeInfo root = freshRoot();
        if (root==null) throw new ControlException(NOT_READY,"No active window");
        AccessibilityNodeInfo node = null;
        try {
            node=targetNode(root,a);
            int action = switch(a.kind()) {
                case CLICK -> AccessibilityNodeInfo.ACTION_CLICK;
                case LONG_CLICK -> AccessibilityNodeInfo.ACTION_LONG_CLICK;
                case KEY -> AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId();
                case SCROLL -> switch(a.direction()==null?"":a.direction()) {
                    case "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
                    case "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId();
                    case "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId();
                    case "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId();
                    default -> throw new ControlException(INVALID_ARGUMENT,"Invalid scroll direction");
                };
                default -> throw new ControlException(UNSUPPORTED,"Unsupported native action");
            };
            if(a.kind()==Kind.SCROLL && !supports(node,action)) {
                // Native vertical widgets commonly advertise forward/backward rather than directional actions.
                if("down".equals(a.direction())) action=AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                else if("up".equals(a.direction())) action=AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                else throw new ControlException(UNSUPPORTED,"Horizontal native scrolling not advertised; use an observed swipe");
                if(!supports(node,action)) throw new ControlException(UNSUPPORTED,"No native scroll action in this direction");
            }
            if (!node.performAction(action)) throw new ControlException(ACTION_REJECTED,"Native action rejected; no fake Enter or automatic input retry");
            return new Result("dispatched","Native Android action accepted");
        } finally { if (node!=null) node.recycle(); root.recycle(); }
    }
    private static AccessibilityNodeInfo targetNode(AccessibilityNodeInfo root,Action a) throws ControlException {
        AccessibilityNodeInfo node;
        if(a.kind()==Kind.KEY||(a.kind()==Kind.TYPE&&a.selector()==null&&a.x()==null))node=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        else {
            List<AccessibilityNodeInfo> matches=new ArrayList<>();
            try {
                find(root,a,matches,0,new int[]{0});
                if(a.selector()==null&&clickKind(a)&&matches.size()>1)pruneObscuredBackgroundHits(matches);
                if(matches.size()!=1) throw new ControlException(matches.isEmpty()?ACTION_REJECTED:AMBIGUOUS_TARGET,
                    "Target must match exactly one node");
                node=AccessibilityNodeInfo.obtain(matches.get(0));
                if(a.selector()!=null&&clickKind(a)) {
                    AccessibilityNodeInfo resolved=clickAncestor(node,a);
                    node.recycle();node=resolved;
                }
            } finally {for(AccessibilityNodeInfo item:matches)item.recycle();}
        }
        if(node==null||!node.isEnabled()) {
            if(node!=null)node.recycle();
            throw new ControlException(ACTION_REJECTED,"No enabled target");
        }
        return node;
    }
    private static boolean supports(AccessibilityNodeInfo node,int id) {
        for(AccessibilityNodeInfo.AccessibilityAction action:node.getActionList()) if(action.getId()==id) return true;
        return false;
    }

    private static boolean clickKind(Action a) {return a.kind()==Kind.CLICK || a.kind()==Kind.LONG_CLICK;}
    private static boolean actionable(AccessibilityNodeInfo node,Action a) {
        return switch(a.kind()) {
            case CLICK -> node.isClickable(); case LONG_CLICK -> node.isLongClickable();
            case TYPE -> node.isEditable(); case SCROLL -> node.isScrollable(); default -> false;
        };
    }

    /** Resolve only after exact visible selector uniqueness has been established. */
    private static AccessibilityNodeInfo clickAncestor(AccessibilityNodeInfo label,Action a) throws ControlException {
        Rect labelBounds=new Rect();label.getBoundsInScreen(labelBounds);
        AccessibilityNodeInfo current=AccessibilityNodeInfo.obtain(label);
        try {
            for(int depth=0;current!=null && depth<=60;depth++) {
                if(!current.isEnabled() || !current.isVisibleToUser() || current.getWindowId()!=label.getWindowId()) break;
                Rect bounds=new Rect();current.getBoundsInScreen(bounds);
                if(!bounds.contains(labelBounds)) break;
                if(actionable(current,a)) return AccessibilityNodeInfo.obtain(current);
                AccessibilityNodeInfo parent=current.getParent();current.recycle();current=parent;
            }
            throw new ControlException(ACTION_REJECTED,"No enabled visible actionable target or containing ancestor");
        } finally {if(current!=null)current.recycle();}
    }

    static AccessibilityNodeInfo requireTraversalChild(AccessibilityNodeInfo child) throws ControlException {
        if(child==null) throw new ControlException(ACTION_REJECTED,"Target tree contains an unavailable child; observe again");
        return child;
    }

    private record CoordinateHit(AccessibilityNodeInfo node,Rect bounds,int windowId,boolean leaf,int drawingOrder,AccessibilityNodeInfo parent) {}

    private static void pruneObscuredBackgroundHits(List<AccessibilityNodeInfo> matches) {
        // Above this bound, preserve ambiguity rather than spending quadratic work on the main thread.
        if(matches.size()>MAX_BACKGROUND_PRUNE_HITS)return;
        List<CoordinateHit> hits=new ArrayList<>(matches.size());
        try {
            for(AccessibilityNodeInfo node:matches) {
                Rect bounds=new Rect();node.getBoundsInScreen(bounds);
                hits.add(new CoordinateHit(node,bounds,node.getWindowId(),node.getChildCount()==0,node.getDrawingOrder(),node.getParent()));
            }
            boolean[] remove=new boolean[matches.size()];
            for(int i=0;i<hits.size();i++)for(int j=0;j<hits.size();j++) {
                if(i!=j&&provablyObscuredBackground(hits.get(i),hits.get(j))) {remove[i]=true;break;}
            }
            for(int i=matches.size()-1;i>=0;i--)if(remove[i])matches.remove(i).recycle();
        } finally {
            for(CoordinateHit hit:hits)if(hit.parent()!=null)hit.parent().recycle();
        }
    }

    /**
     * Ignore only a leaf hit that is provably a covered background sibling: its bounds contain
     * a later-drawn sibling branch, while the competing actionable hit is inside that branch.
     * Direct sibling hits and equal/unknown drawing order remain ambiguous.
     */
    private static boolean provablyObscuredBackground(CoordinateHit background,CoordinateHit foreground) {
        if(background.windowId()!=foreground.windowId()||!background.leaf()||background.parent()==null
            ||!background.bounds().contains(foreground.bounds()))return false;
        AccessibilityNodeInfo current=AccessibilityNodeInfo.obtain(foreground.node());
        try {
            for(int depth=0;current!=null&&depth<=60;depth++) {
                AccessibilityNodeInfo next=current.getParent();
                if(next==null)return false;
                if(next.equals(background.parent())) {
                    try {
                        if(depth==0||current.equals(background.node()))return false;
                        Rect branchBounds=new Rect();current.getBoundsInScreen(branchBounds);
                        return background.bounds().contains(branchBounds)
                            &&background.drawingOrder()<current.getDrawingOrder();
                    } finally {next.recycle();}
                }
                current.recycle();current=next;
            }
            return false;
        } finally {if(current!=null)current.recycle();}
    }

    private static void find(AccessibilityNodeInfo node,Action a,List<AccessibilityNodeInfo> out,int depth,int[] visited) throws ControlException {
        // An incomplete search cannot establish uniqueness. Fail closed rather than silently truncating it.
        if(depth>60 || ++visited[0]>5000) throw new ControlException(ACTION_REJECTED,"Target tree exceeds safe search limits");
        boolean matches=node.isVisibleToUser();
        Selector s=a.selector();
        if(s!=null) {
            if(s.empty()) throw new ControlException(INVALID_ARGUMENT,"Selector must contain resource ID, text, or description");
            matches &= (s.resourceId()==null || s.resourceId().equals(text(node.getViewIdResourceName())))
                && (s.text()==null || s.text().equals(text(node.getText())))
                && (s.description()==null || s.description().equals(text(node.getContentDescription())));
            // Click labels need not themselves advertise the action. Disabled matches still count
            // towards selector ambiguity and are rejected during ancestor resolution.
            if(!clickKind(a)) matches &= actionable(node,a) && node.isEnabled();
        } else {
            if(a.x()==null || a.y()==null) throw new ControlException(INVALID_ARGUMENT,"Selector or point required");
            Rect bounds=new Rect();node.getBoundsInScreen(bounds);
            matches &= actionable(node,a) && node.isEnabled() && bounds.contains(a.x(),a.y());
        }
        int descendants=out.size();
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=requireTraversalChild(node.getChild(i));
            try {find(child,a,out,depth+1,visited);} finally {child.recycle();}
        }
        // A matching descendant in this node's own subtree is a more specific hit,
        // even when Android reports child bounds extending beyond the parent. Matches
        // from sibling branches predate descendants and therefore remain ambiguous.
        if(matches && s==null && clickKind(a) && out.size()>descendants)return;
        if(matches)out.add(AccessibilityNodeInfo.obtain(node));
    }

    private record PendingText(AccessibilityNodeInfo node,String expected) {}
    private Result setText(Action a,Observation observed) throws ControlException {
        if(a.text()==null || a.text().length()>20000) throw new ControlException(INVALID_ARGUMENT,"Text required, maximum 20000 characters; empty is valid");
        PendingText pending=onMain(()->{
            validateContext(observed);validateFreshness(observed);
            AccessibilityNodeInfo root=freshRoot();
            if(root==null)throw new ControlException(NOT_READY,"No active window");
            AccessibilityNodeInfo node=null,verifier=null;
            try {
                node=targetNode(root,a);
                if(!node.isFocused()) {
                    if(!node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)||!node.refresh()||!node.isFocused())
                        throw new ControlException(ACTION_REJECTED,"Editing focus was not confirmed; focus the field and observe before typing");
                    validateContext(observed);
                }
                String existing=node.isShowingHintText()?"":text(node.getText());
                int start=node.getTextSelectionStart(),end=node.getTextSelectionEnd();
                start=start<0?existing.length():Math.min(start,existing.length());
                end=end<0?start:Math.min(end,existing.length());
                int left=Math.min(start,end),right=Math.max(start,end);
                String expected=a.replace()?a.text():existing.substring(0,left)+a.text()+existing.substring(right);
                Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,expected);
                verifier=AccessibilityNodeInfo.obtain(node);
                boolean accepted;
                try {accepted=node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);}
                catch(RuntimeException dispatchFailure) {
                    verifier.recycle();verifier=null;
                    throw new ControlException(RESULT_UNKNOWN,"Text dispatch outcome unknown; observe before repeating");
                }
                if(!accepted) {
                    verifier.recycle();verifier=null;
                    throw new ControlException(RESULT_UNKNOWN,"Text dispatch was not confirmed; observe before repeating");
                }
                return new PendingText(verifier,expected);
            } finally {
                if(node!=null)node.recycle();
                root.recycle();
            }
        });
        long deadline=android.os.SystemClock.uptimeMillis()+TEXT_VERIFICATION_TIMEOUT_MS;
        try {
            for(;;) {
                boolean verified;
                try {
                    verified=onMain(()->{
                        validateContext(observed);
                        return pending.node().refresh()&&pending.expected().equals(pending.node().isShowingHintText()?"":text(pending.node().getText()));
                    });
                } catch(ControlException postDispatch) {
                    throw new ControlException(RESULT_UNKNOWN,"Text was dispatched but verification became unavailable; observe before repeating");
                }
                if(verified)return new Result("verified","Exact field text verified");
                long remaining=deadline-android.os.SystemClock.uptimeMillis();
                if(remaining<=0)throw new ControlException(RESULT_UNKNOWN,"Text was dispatched but exact result was not confirmed; observe before repeating");
                try {Thread.sleep(Math.min(TEXT_VERIFICATION_POLL_MS,remaining));}
                catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ControlException(RESULT_UNKNOWN,"Text was dispatched but verification was interrupted; observe before repeating");
                }
            }
        } finally {pending.node().recycle();}
    }
}
