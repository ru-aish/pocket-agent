package dev.pocket.agent;

import android.os.IBinder;
import android.os.RemoteException;
import android.view.Surface;
import java.util.function.Consumer;

/** Owns only the service's preview surface reference, never the display itself. */
final class DisplayPreviewSurface implements AutoCloseable {
    private final Consumer<Surface> target;
    private final Surface background;
    private Surface preview;
    private IBinder viewer;
    private IBinder.DeathRecipient death;
    private boolean closed;

    DisplayPreviewSurface(Consumer<Surface> target,Surface background) {
        this.target=target;this.background=background;
    }

    synchronized void show(Surface surface,IBinder session) {
        boolean attached=false;
        try {
            if(closed)throw new IllegalStateException("Display is closing");
            if(surface==null||!surface.isValid()||session==null)throw new IllegalArgumentException("Live surface and viewer session required");
            if(viewer!=null)throw new IllegalStateException("Display is already being viewed");
            IBinder.DeathRecipient recipient=()->hide(session);
            try {session.linkToDeath(recipient,0);}
            catch(RemoteException dead) {throw new IllegalStateException("Viewer disconnected",dead);}
            try {target.accept(surface);}
            catch(RuntimeException failure) {
                try {target.accept(background);}
                catch(RuntimeException restoreFailure) {failure.addSuppressed(restoreFailure);}
                finally {session.unlinkToDeath(recipient,0);}
                throw failure;
            }
            preview=surface;viewer=session;death=recipient;
            attached=true;
        } finally {
            // AIDL gives the service its own reference to the caller's surface.
            if(!attached&&surface!=null)surface.release();
        }
    }

    synchronized void hide(IBinder session) {
        // A late close/death callback must not detach a newer viewer.
        if(viewer==null||!viewer.equals(session))return;
        try {target.accept(background);}
        finally {
            viewer.unlinkToDeath(death,0);viewer=null;death=null;
            preview.release();preview=null;
        }
    }

    synchronized void detach() {if(viewer!=null)hide(viewer);}

    @Override public synchronized void close() {
        try {detach();}
        finally {closed=true;}
    }
}
