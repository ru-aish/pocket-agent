"""Exercise the real preview lifecycle with small host-JVM Android test doubles."""
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/dev/pocket/agent/DisplayPreviewSurface.java"


class DisplayPreviewTest(unittest.TestCase):
    def test_preview_lifecycle_and_failed_attach(self):
        sources = {
            "android/os/RemoteException.java": "package android.os; public class RemoteException extends Exception {}",
            "android/os/IBinder.java": """
package android.os;
public interface IBinder {
    interface DeathRecipient { void binderDied(); }
    void linkToDeath(DeathRecipient recipient,int flags) throws RemoteException;
    boolean unlinkToDeath(DeathRecipient recipient,int flags);
}
""",
            "android/view/Surface.java": """
package android.view;
public final class Surface {
    public boolean valid=true;
    public int releases;
    public boolean isValid() {return valid;}
    public void release() {releases++;valid=false;}
}
""",
            "dev/pocket/agent/DisplayPreviewTest.java": """
package dev.pocket.agent;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Surface;
import java.util.ArrayList;
import java.util.List;

public final class DisplayPreviewTest {
    static final class Token implements IBinder {
        DeathRecipient death;
        boolean dead;
        int unlinks;
        public void linkToDeath(DeathRecipient callback,int flags) throws RemoteException {
            if(dead)throw new RemoteException();
            death=callback;
        }
        public boolean unlinkToDeath(DeathRecipient callback,int flags) {
            if(death!=callback)throw new AssertionError("Wrong death recipient");
            death=null;unlinks++;return true;
        }
    }
    static void check(boolean condition) {if(!condition)throw new AssertionError();}
    static void rejected(Runnable action) {
        try {action.run();throw new AssertionError("Expected rejection");}
        catch(IllegalStateException|IllegalArgumentException expected) {}
    }
    public static void main(String[] args) {
        Surface background=new Surface(),first=new Surface(),second=new Surface();
        List<Surface> frames=new ArrayList<>();
        DisplayPreviewSurface preview=new DisplayPreviewSurface(frames::add,background);
        Token a=new Token(),b=new Token();
        // Closed UI restores the original sink without releasing that sink.
        preview.show(first,a);
        IBinder.DeathRecipient lateDeath=a.death;
        preview.hide(a);
        check(frames.equals(List.of(first,background))&&first.releases==1&&background.releases==0);
        check(a.unlinks==1);
        preview.show(second,b);
        // An obsolete detach or death must not blank the new window.
        preview.hide(a);lateDeath.binderDied();
        check(frames.equals(List.of(first,background,second))&&second.releases==0);
        // A second window cannot steal an existing preview.
        Surface competing=new Surface();
        rejected(()->preview.show(competing,new Token()));
        check(competing.releases==1&&second.releases==0);
        // Viewer process death restores background operation.
        b.death.binderDied();
        check(frames.get(frames.size()-1)==background&&second.releases==1&&b.unlinks==1);
        preview.hide(b);preview.close();preview.close();
        Surface afterClose=new Surface();
        rejected(()->preview.show(afterClose,new Token()));
        check(afterClose.releases==1&&background.releases==0);

        // Binder death before attachment must not change the display's sink.
        DisplayPreviewSurface fresh=new DisplayPreviewSurface(frames::add,background);
        Token dead=new Token();dead.dead=true;
        Surface missing=new Surface();int before=frames.size();
        rejected(()->fresh.show(missing,dead));
        check(missing.releases==1&&frames.size()==before);
        Surface invalid=new Surface();invalid.valid=false;
        rejected(()->fresh.show(invalid,new Token()));
        check(invalid.releases==1&&frames.size()==before);

        // Failed native attach unlinks death and frees only the preview reference.
        Token failureToken=new Token();Surface failureSurface=new Surface();
        DisplayPreviewSurface broken=new DisplayPreviewSurface(s->{throw new IllegalStateException();},background);
        rejected(()->broken.show(failureSurface,failureToken));
        check(failureSurface.releases==1&&failureToken.unlinks==1&&background.releases==0);

        // A failed first attach restores the original sink and allows a retry.
        List<Surface> recovered=new ArrayList<>();boolean[] rejectAttach={true};
        DisplayPreviewSurface retry=new DisplayPreviewSurface(s->{
            if(s!=background&&rejectAttach[0])throw new IllegalStateException();
            recovered.add(s);
        },background);
        rejected(()->retry.show(new Surface(),new Token()));
        check(recovered.equals(List.of(background)));
        rejectAttach[0]=false;Token retryToken=new Token();Surface retrySurface=new Surface();
        retry.show(retrySurface,retryToken);retry.detach();
        Token reopenedToken=new Token();Surface reopenedSurface=new Surface();
        retry.show(reopenedSurface,reopenedToken);retry.close();
        check(retrySurface.releases==1&&reopenedSurface.releases==1);

        // Removing a display detaches once and refuses future viewers.
        Token removingToken=new Token();Surface removingSurface=new Surface();
        fresh.show(removingSurface,removingToken);fresh.close();
        check(removingSurface.releases==1&&removingToken.unlinks==1&&frames.get(frames.size()-1)==background);
        fresh.hide(removingToken);fresh.close();
        check(removingSurface.releases==1&&background.releases==0);

        // Even a failed restore frees the surface and retires its death callback.
        boolean[] rejectRestore={false};
        DisplayPreviewSurface failedRestore=new DisplayPreviewSurface(s->{
            if(rejectRestore[0])throw new IllegalStateException();
        },background);
        Surface restoreSurface=new Surface();Token restoreToken=new Token();
        failedRestore.show(restoreSurface,restoreToken);rejectRestore[0]=true;
        rejected(failedRestore::close);
        check(restoreSurface.releases==1&&restoreToken.unlinks==1&&background.releases==0);
        failedRestore.close();
    }
}
""",
        }
        with tempfile.TemporaryDirectory(prefix="display-preview-test-") as temporary:
            directory = pathlib.Path(temporary)
            paths = []
            for name, content in sources.items():
                path = directory / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
                paths.append(str(path))
            subprocess.run(["javac", "--release", "17", "-d", temporary, str(SOURCE), *paths], check=True)
            subprocess.run(["java", "-cp", temporary, "dev.pocket.agent.DisplayPreviewTest"], check=True)


if __name__ == "__main__":
    unittest.main()
