package dev.pocket.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Dialog lifecycle checks without Shizuku, inference, or real display allocation. */
@RunWith(AndroidJUnit4.class)
public class DisplayViewerDeviceTest {
    @Test public void lateDialogCallbacksCannotCloseANewerPickerOrUseAClosedExecutor() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var context=instrumentation.getTargetContext();
        Activity activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        AtomicReference<DisplayViewer> reference=new AtomicReference<>();
        RuntimeHost before=RuntimeOwner.peekHost();
        int initialCount=AgentDisplayCoordinator.shared(context).activeCount();
        Field picker=DisplayViewer.class.getDeclaredField("picker");picker.setAccessible(true);
        Method open=DisplayViewer.class.getDeclaredMethod("open",AgentDisplayCoordinator.AvailableDisplay.class);open.setAccessible(true);
        try {
            instrumentation.runOnMainSync(()->{
                DisplayViewer viewer=new DisplayViewer(activity,JSONObject::new);reference.set(viewer);
                viewer.show();viewer.dismiss();viewer.show();
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{
                try {assertTrue("An old dismissal must not clear the new picker",((AlertDialog)picker.get(reference.get())).isShowing());}
                catch(IllegalAccessException failure) {throw new AssertionError(failure);}
            });
            instrumentation.runOnMainSync(()->{
                try {
                    // This nonexistent ID exercises late failed-attach callbacks,
                    // without creating a display or binding a privileged service.
                    open.invoke(reference.get(),new AgentDisplayCoordinator.AvailableDisplay(Integer.MAX_VALUE,"main","ready",new Object()));
                    reference.get().close();
                } catch(Exception failure) {throw new AssertionError(failure);}
            });
            instrumentation.waitForIdleSync();
            assertSame("Viewing must not start a runtime",before,RuntimeOwner.peekHost());
            assertEquals("Viewing must not acquire a display",initialCount,AgentDisplayCoordinator.shared(context).activeCount());
        } finally {
            instrumentation.runOnMainSync(()->{if(reference.get()!=null)reference.get().close();activity.finish();});
        }
    }
}
