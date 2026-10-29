package dev.pocket.agent;

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.Binder;
import android.view.Display;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.pocket.control.ControlBackend.*;
import dev.pocket.control.ControlException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic chooser on a trusted virtual display; no personal apps or Shizuku setup required. */
@RunWith(AndroidJUnit4.class)
public class PhoneTargetDeviceTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final UiAutomation automation=InstrumentationRegistry.getInstrumentation()
        .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);

    private Observation observed(AccessibilityProxyBackend screen,String expected) throws Exception {
        long end=System.currentTimeMillis()+6000;
        while(System.currentTimeMillis()<end) {
            try {
                Observation o=screen.observe(false,720,200);
                if(o.nodes().stream().anyMatch(n->n.text().equals(expected)))return o;
            } catch(ControlException e) {
                if(e.code!=ControlException.Code.NOT_READY&&e.code!=ControlException.Code.STALE_OBSERVATION)throw e;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Missing display state: "+expected);
    }
    private final class Chooser implements AutoCloseable {
        ImageReader reader;
        VirtualDisplay display;
        AccessibilityProxyBackend screen;
        final java.util.concurrent.atomic.AtomicInteger frameCount=new java.util.concurrent.atomic.AtomicInteger();
        Chooser(String mode) throws Exception {
            try {
                DeviceTestAccess.ensureAccessibility();
                automation.adoptShellPermissionIdentity("android.permission.ADD_TRUSTED_DISPLAY",
                    "android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY");
                try {
                    reader=ImageReader.newInstance(720,1280,PixelFormat.RGBA_8888,2);
                    reader.setOnImageAvailableListener(r->{try(Image frame=r.acquireLatestImage()) {if(frame!=null)frameCount.incrementAndGet();}},new Handler(Looper.getMainLooper()));
                    display=context.getSystemService(DisplayManager.class).createVirtualDisplay("PocketChooserTest",720,1280,320,reader.getSurface(),
                        1|8|64|1024|2048|4096|16384|32768);
                } finally {automation.dropShellPermissionIdentity();}
                assertNotNull(display);
                automation.adoptShellPermissionIdentity();
                try {ShellDisplayService.hideIme(display.getDisplay().getDisplayId());}
                finally {automation.dropShellPermissionIdentity();}
                screen=new AccessibilityProxyBackend(context,display.getDisplay().getDisplayId());
                String fixtureClass="launch_failure".equals(mode)
                    ?"dev.pocket.agent.fixture.MissingActivity":"dev.pocket.agent.fixture.FixtureActivity";
                int activityFlags=Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
                if(!"recents_regression".equals(mode))activityFlags|=Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
                Intent launch=new Intent().setClassName("dev.pocket.agent.fixture",fixtureClass).addFlags(activityFlags);
                if(!"editable".equals(mode))launch.putExtra("chooser_case",mode);
                context.startActivity(launch,ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplay().getDisplayId()).toBundle());
                observed("editable".equals(mode)?"POCKET_AGENT_FIXTURE":"CHOOSER_READY");
            } catch(Exception|AssertionError error) {
                close();
                throw error;
            }
        }
        Observation observed(String expected) throws Exception {
            long end=System.currentTimeMillis()+6000;
            while(System.currentTimeMillis()<end) {
                try {
                    Observation o=screen.observe(false,720,200);
                    if(o.packageName().equals("dev.pocket.agent.fixture") && o.nodes().stream().anyMatch(n->n.text().equals(expected))) {
                        // Require a settled, fresh snapshot; never replay a dispatched action.
                        Thread.sleep(100);
                        o=screen.observe(false,720,200);
                        if(o.nodes().stream().anyMatch(n->n.text().equals(expected)))return o;
                    }
                } catch(ControlException e) {
                    if(e.code!=ControlException.Code.NOT_READY && e.code!=ControlException.Code.STALE_OBSERVATION)throw e;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("Missing chooser state: "+expected);
        }
        Action action(Kind kind,Selector selector,boolean point) throws Exception {
            Observation o=observed("CHOOSER_READY");
            Node row=o.nodes().stream().filter(n->n.description().equals("CHOOSER_ROW")).findFirst().orElse(null);
            Node panel=o.nodes().stream().filter(n->n.description().equals("CHOOSER_PANEL")).findFirst().orElseThrow();
            int x=row==null?panel.left()+100:(row.left()+row.right())/2;
            int y=row==null?panel.top()+200:(row.top()+row.bottom())/2;
            return new Action(kind,o.snapshotId(),point?x:null,point?y:null,
                null,null,800,null,false,null,null,selector);
        }
        void rejected(Action action,ControlException.Code code) throws Exception {
            try {screen.execute(action);fail("Unexpected dispatch");}
            catch(ControlException e) {assertEquals(e.getMessage(),code,e.code);}
            observed("CHOOSER_READY");
        }
        void forceStopFixture() {
            try {shell("am force-stop dev.pocket.agent.fixture");} catch(Exception ignored) {}
        }
        void releaseDisplayOnly() {
            if(display!=null) {
                if(screen!=null)screen.release();
                display.release();display=null;screen=null;
            }
            if(reader!=null) {reader.close();reader=null;}
        }
        @Override public void close() {forceStopFixture();releaseDisplayOnly();}
    }
    private String shell(String command) throws Exception {
        try(android.os.ParcelFileDescriptor.AutoCloseInputStream input=
                new android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))) {
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[4096];int count;
            while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
            return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static Boolean booleanField(String text,String name) {
        Matcher match=Pattern.compile(Pattern.quote(name)+"=(true|false)").matcher(text);
        return match.find()?Boolean.parseBoolean(match.group(1)):null;
    }
    private static boolean emulator() {
        return android.os.Build.FINGERPRINT.startsWith("generic")
            ||android.os.Build.FINGERPRINT.contains("emulator")
            ||android.os.Build.MODEL.contains("Emulator")
            ||android.os.Build.HARDWARE.contains("ranchu")
            ||android.os.Build.PRODUCT.contains("sdk");
    }

    private int chooserDisplayCount() {
        int count=0;
        for(android.view.Display item:context.getSystemService(DisplayManager.class).getDisplays())
            if("PocketChooserTest".equals(item.getName()))count++;
        return count;
    }

    @Test public void previewShowsFramesAndRestoresTheExistingDisplay() throws Exception {
        try(Chooser chooser=new Chooser("editable")) {
            int id=chooser.display.getDisplay().getDisplayId();
            DisplayManager manager=context.getSystemService(DisplayManager.class);
            int initialCount=chooserDisplayCount();
            android.graphics.Point initialSize=new android.graphics.Point();
            manager.getDisplay(id).getRealSize(initialSize);
            try(ImageReader viewer=ImageReader.newInstance(720,1280,PixelFormat.RGBA_8888,2);
                DisplayPreviewSurface preview=new DisplayPreviewSurface(chooser.display::setSurface,chooser.reader.getSurface())) {
                preview.show(viewer.getSurface(),new Binder());
                boolean received=false;
                long deadline=android.os.SystemClock.elapsedRealtime()+6000;
                while(android.os.SystemClock.elapsedRealtime()<deadline) {
                    try(Image frame=viewer.acquireLatestImage()) {
                        if(frame!=null) {assertEquals(720,frame.getWidth());assertEquals(1280,frame.getHeight());received=true;break;}
                    }
                    Thread.sleep(50);
                }
                assertTrue("Preview should receive compositor frames",received);
                assertEquals("Viewing must not allocate another display",initialCount,chooserDisplayCount());
                android.graphics.Point viewedSize=new android.graphics.Point();
                manager.getDisplay(id).getRealSize(viewedSize);
                assertEquals("Viewing must preserve display dimensions",initialSize,viewedSize);
                int backgroundFrames=chooser.frameCount.get();
                preview.detach();
                deadline=android.os.SystemClock.elapsedRealtime()+6000;
                while(chooser.frameCount.get()<=backgroundFrames&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
                assertTrue("Background surface must receive frames again",chooser.frameCount.get()>backgroundFrames);
                // The exact same worker activity and display remain usable afterward.
                assertNotNull(manager.getDisplay(id));
                chooser.observed("POCKET_AGENT_FIXTURE");
                assertEquals(initialCount,chooserDisplayCount());
            }
        }
    }

    @Test public void physicalBackendStaysScopedWhileVirtualDisplayExists() throws Exception {
        if(android.os.Build.VERSION.SDK_INT<33)return;
        DeviceTestAccess.ensureAccessibility();
        AccessibilityProxyBackend physical=new AccessibilityProxyBackend(context,Display.DEFAULT_DISPLAY);
        Intent launch=new Intent().setClassName("dev.pocket.agent.fixture","dev.pocket.agent.fixture.FixtureActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(launch,ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
        Observation physicalBefore=observed(physical,"POCKET_AGENT_FIXTURE");
        assertTrue(physicalBefore.nodes().stream().anyMatch(n->n.text().equals("STATUS_READY")));
        try(Chooser virtual=new Chooser("overlap")) {
            Observation virtualObservation=virtual.observed("CHOOSER_READY");
            Action interactVirtual=new Action(Kind.CLICK,virtualObservation.snapshotId(),null,null,null,null,800,null,false,null,null,
                new Selector(null,null,"CHOOSER_ROW"));
            Result receipt=virtual.screen.execute(interactVirtual);
            assertEquals(receipt.toString(),"dispatched",receipt.status());
            virtual.observed("CHOOSER_SELECTED");

            Observation physicalAfter=observed(physical,"POCKET_AGENT_FIXTURE");
            assertTrue(physicalAfter.nodes().stream().anyMatch(n->n.text().equals("STATUS_READY")));
            assertFalse(physicalAfter.nodes().stream().anyMatch(n->n.text().startsWith("CHOOSER_")));
            Observation virtualAfter=virtual.observed("CHOOSER_SELECTED");
            assertFalse(virtualAfter.nodes().stream().anyMatch(n->n.text().equals("STATUS_READY")));
        } finally {physical.release();}
    }

    @Test public void workerTaskDoesNotMigrateIntoPhysicalRecentsWhenDisplayIsReleased() throws Exception {
        if(emulator())return;
        shell("am force-stop dev.pocket.agent.fixture");
        Chooser c=new Chooser("recents_regression");
        try {
            int displayId=c.display.getDisplay().getDisplayId();
            java.util.Set<Integer> tasks=ShellDisplayService.parseRootTasks(shell("am stack list"),displayId);
            if(tasks.isEmpty())tasks=ShellDisplayService.parseRootTasks(shell("dumpsys activity activities"),displayId);
            assertFalse("Fixture must own a root task on the worker display before cleanup",tasks.isEmpty());
            for(int task:tasks)shell("am stack remove "+task);
            Thread.sleep(250);
            java.util.Set<Integer> remaining=ShellDisplayService.parseRootTasks(shell("dumpsys activity activities"),displayId);
            for(int task:remaining)shell("am task remove "+task);
            Thread.sleep(250);
            assertTrue("Worker root tasks must be gone before virtual-display teardown",
                ShellDisplayService.parseRootTasks(shell("dumpsys activity activities"),displayId).isEmpty());
            c.releaseDisplayOnly();
            Thread.sleep(700);
            String recents=shell("dumpsys activity recents");
            assertFalse("Worker fixture leaked into physical Recents after virtual-display teardown",recents.contains("dev.pocket.agent.fixture"));
        } finally {c.close();}
    }

    @Test public void focusingVirtualEditTextDoesNotShowImeOnPhysicalDisplay() throws Exception {
        if(emulator())return;
        try(Chooser c=new Chooser("editable")) {
            Observation o=c.observed("POCKET_AGENT_FIXTURE");
            Node input=o.nodes().stream().filter(n->n.description().equals("AGENT_INPUT")).findFirst().orElseThrow();
            int x=(input.left()+input.right())/2,y=(input.top()+input.bottom())/2;
            Result receipt=c.screen.execute(new Action(Kind.TAP,o.snapshotId(),x,y,null,null,100,null,false,null,null,null));
            assertEquals("dispatched",receipt.status());
            Thread.sleep(800);
            String ime=shell("dumpsys input_method");
            Boolean inputShown=booleanField(ime,"mInputShown");
            assertNotNull("Could not parse mInputShown from dumpsys input_method",inputShown);
            assertFalse("Virtual-display focus must not show the software IME",inputShown);
            automation.adoptShellPermissionIdentity();
            try {
                Class<?> serviceManager=Class.forName("android.os.ServiceManager");
                android.os.IBinder binder=(android.os.IBinder)serviceManager.getMethod("getService",String.class).invoke(null,"window");
                Class<?> stub=Class.forName("android.view.IWindowManager$Stub");
                Object wm=stub.getMethod("asInterface",android.os.IBinder.class).invoke(null,binder);
                int policy=(Integer)Class.forName("android.view.IWindowManager").getMethod("getDisplayImePolicy",int.class)
                    .invoke(wm,c.display.getDisplay().getDisplayId());
                assertEquals("Worker display must retain DISPLAY_IME_POLICY_HIDE",2,policy);
            } finally {automation.dropShellPermissionIdentity();}
        }
    }

    @Test public void failedConstructionReleasesVirtualDisplayAndReader() throws Exception {
        int before=chooserDisplayCount();
        try {new Chooser("launch_failure");fail("Expected fixture launch failure");}
        catch(ActivityNotFoundException expected) {}
        long deadline=System.currentTimeMillis()+2000;
        while(chooserDisplayCount()!=before && System.currentTimeMillis()<deadline)Thread.sleep(50);
        assertEquals(before,chooserDisplayCount());
    }
    @Test public void uniqueVisibleLabelResolvesNearestClickAndLongClickAncestor() throws Exception {
        for(Kind kind:new Kind[]{Kind.CLICK,Kind.LONG_CLICK})try(Chooser c=new Chooser("normal")) {
            Action action=c.action(kind,new Selector(null,"Chrome",null),false);
            assertEquals("dispatched",c.screen.execute(action).status());
            c.observed(kind==Kind.CLICK?"CHOOSER_SELECTED":"CHOOSER_LONG_SELECTED");
        }
    }
    @Test public void exactSelectorUniquenessPrecedesAncestorResolution() throws Exception {
        for(String mode:new String[]{"duplicate","duplicate_disabled"})
            for(Kind kind:new Kind[]{Kind.CLICK,Kind.LONG_CLICK})try(Chooser c=new Chooser(mode)) {
            c.rejected(c.action(kind,new Selector(null,"Chrome",null),false),ControlException.Code.AMBIGUOUS_TARGET);
        }
        try(Chooser c=new Chooser("normal")) {
            c.rejected(c.action(Kind.CLICK,new Selector(null,"Chrome","not-the-label"),false),ControlException.Code.ACTION_REJECTED);
        }
    }
    @Test public void disabledAndInvisibleLabelsCannotPromoteToOuterContainer() throws Exception {
        for(String mode:new String[]{"disabled","disabled_label","invisible"})
            for(Kind kind:new Kind[]{Kind.CLICK,Kind.LONG_CLICK})try(Chooser c=new Chooser(mode)) {
                c.rejected(c.action(kind,new Selector(null,"Chrome",null),false),ControlException.Code.ACTION_REJECTED);
            }
    }
    @Test public void coordinateHitsChooseDeepestInAncestorChain() throws Exception {
        for(String mode:new String[]{"normal","ineligible_siblings","overflow_descendant"})
            for(Kind kind:new Kind[]{Kind.CLICK,Kind.LONG_CLICK})try(Chooser c=new Chooser(mode)) {
            assertEquals("dispatched",c.screen.execute(c.action(kind,null,true)).status());
            c.observed(kind==Kind.CLICK?"CHOOSER_SELECTED":"CHOOSER_LONG_SELECTED");
        }
    }
    @Test public void coveredBackdropSiblingYieldsToForegroundOption() throws Exception {
        for(Kind kind:new Kind[]{Kind.CLICK,Kind.LONG_CLICK})try(Chooser c=new Chooser("backdrop_sibling")) {
            assertEquals("dispatched",c.screen.execute(c.action(kind,null,true)).status());
            c.observed(kind==Kind.CLICK?"CHOOSER_SELECTED":"CHOOSER_LONG_SELECTED");
        }
    }
    @Test public void overlappingSiblingTargetsRemainAmbiguousEvenWhenBoundsNest() throws Exception {
        for(String mode:new String[]{"overlap","contained_sibling"})
            for(Kind kind:new Kind[]{Kind.CLICK,Kind.LONG_CLICK})try(Chooser c=new Chooser(mode)) {
                Action a=c.action(kind,null,true);
                // Hit the smaller sibling too, rather than its right-exclusive boundary.
                a=new Action(a.kind(),a.snapshotId(),a.x()-50,a.y(),null,null,0,null,false,null,null,null);
                c.rejected(a,ControlException.Code.AMBIGUOUS_TARGET);
            }
    }
    @Test public void excessiveCoordinateHitsRemainAmbiguous() throws Exception {
        for(Kind kind:new Kind[]{Kind.CLICK,Kind.LONG_CLICK})try(Chooser c=new Chooser("many_overlaps")) {
            c.rejected(c.action(kind,null,true),ControlException.Code.AMBIGUOUS_TARGET);
        }
    }
    @Test public void rawGesturesReachChooserOnRequestedVirtualDisplayWithDispatchOnlyReceipts() throws Exception {
        for(Kind kind:new Kind[]{Kind.TAP,Kind.LONG_PRESS})try(Chooser c=new Chooser("normal")) {
            Result receipt=c.screen.execute(c.action(kind,null,true));
            assertEquals("dispatched",receipt.status());assertTrue(receipt.message().contains("not yet verified"));
            c.observed(kind==Kind.TAP?"CHOOSER_SELECTED":"CHOOSER_LONG_SELECTED");
        }
    }
    @Test public void coordinateHitsRejectDisabledOrInvisibleTargets() throws Exception {
        for(String mode:new String[]{"disabled_only","invisible_only"})
            for(Kind kind:new Kind[]{Kind.CLICK,Kind.LONG_CLICK})try(Chooser c=new Chooser(mode)) {
                c.rejected(c.action(kind,null,true),ControlException.Code.ACTION_REJECTED);
            }
    }
    @Test public void longClickSkipsClickOnlyContainerAndDirectSelectorsStillWork() throws Exception {
        try(Chooser c=new Chooser("long_parent")) {
            assertEquals("dispatched",c.screen.execute(c.action(Kind.LONG_CLICK,new Selector(null,"Chrome",null),false)).status());
            c.observed("PANEL_LONG_CLICKED");
        }
        try(Chooser c=new Chooser("normal")) {
            assertEquals("dispatched",c.screen.execute(c.action(Kind.CLICK,new Selector(null,null,"CHOOSER_ROW"),false)).status());
            c.observed("CHOOSER_SELECTED");
        }
    }
    @Test public void completedGestureOutsideOptionDoesNotVerifySelection() throws Exception {
        try(Chooser c=new Chooser("normal")) {
            Observation o=c.observed("CHOOSER_READY");
            Result receipt=c.screen.execute(new Action(Kind.TAP,o.snapshotId(),700,1000,null,null,100,null,false,null,null,null));
            assertEquals("dispatched",receipt.status());assertTrue(receipt.message().contains("not yet verified"));
            c.observed("OUTER_CLICKED");
        }
    }
    @Test public void changedContextStillRejectsOldSelectorAndCoordinateSnapshots() throws Exception {
        try(Chooser c=new Chooser("normal")) {
            Action stale=c.action(Kind.CLICK,new Selector(null,"Chrome",null),false);
            c.screen.noteInteraction(android.os.SystemClock.uptimeMillis()+1);
            c.rejected(stale,ControlException.Code.STALE_OBSERVATION);
            stale=c.action(Kind.CLICK,null,true);
            c.screen.noteInteraction(android.os.SystemClock.uptimeMillis()+1);
            c.rejected(stale,ControlException.Code.STALE_OBSERVATION);
        }
    }
}
