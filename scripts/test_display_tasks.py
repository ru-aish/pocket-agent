"""Run the Android-independent cleanup membership policy on the host JVM."""
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/dev/pocket/agent/DisplayTaskSnapshot.java"
LEASE_SOURCE = ROOT / "app/src/main/java/dev/pocket/agent/DisplayLeases.java"
COORDINATOR_SOURCE = ROOT / "app/src/main/java/dev/pocket/agent/AgentDisplayCoordinator.java"
SERVICE_SOURCE = ROOT / "app/src/main/java/dev/pocket/agent/ShellDisplayService.java"
REMOVAL_SOURCE = ROOT / "app/src/main/java/dev/pocket/agent/DisplayRemovalSequence.java"
RESERVATIONS_SOURCE = ROOT / "app/src/main/java/dev/pocket/agent/DisplayRestorationReservations.java"


class DisplayTaskPolicyTest(unittest.TestCase):
    def test_retiring_cleanup_does_not_block_unrelated_acquisitions(self):
        source = COORDINATOR_SOURCE.read_text()
        acquire = source.split("synchronized JSONObject acquire(", 1)[1].split("synchronized int display(", 1)[0]
        release = source.split("    void release(String owner,Object generation)", 1)[1].split(
            "    synchronized void retireOwners(", 1
        )[0]
        retry = source.split("    private void retryRetiring()", 1)[1].split(
            "    private synchronized void scheduleRetry(", 1
        )[0]
        # Slow shell task cleanup must not execute from acquire or while
        # holding the coordinator monitor. Other owners retain free slots.
        self.assertNotIn("retryRetiring(", acquire)
        self.assertIn("scheduleRetry(true)", source)
        self.assertIn("removed=active!=null&&active.removeDisplay(id);", release)
        self.assertLess(release.index("synchronized(this) {"), release.index("removed=active"))
        self.assertIn("synchronized(this) {", retry)
        self.assertIn("release(owner,generation);", retry)
        self.assertNotIn("synchronized(AgentDisplayCoordinator.this)", retry)

    def test_privileged_slow_removal_allows_unrelated_creation(self):
        service = SERVICE_SOURCE.read_text()
        remove = service.split("@Override public void removeDisplay(int id)", 1)[1].split(
            "    static void hideIme(", 1
        )[0]
        # This is an executable check of the actual concurrency primitive used
        # by the service, paired with checks that the service invokes it without
        # taking its old method-wide lock.
        self.assertIn("DisplayRemovalSequence.run(this,", remove)
        self.assertIn("active -> reconcileAndRemoveTasks(id,active)", remove)
        self.assertIn("reservations.beginRemoval(id)", remove)
        self.assertIn("active -> active.close()", remove)
        self.assertIn("if(reservations.isRemoving(id))", service)
        self.assertIn("if(reservations.hasReservations())", service)
        restore = service.split("private boolean restoreBorrowedTasks(", 1)[1].split(
            "private void reconcileAndRemoveTasks(", 1
        )[0]
        self.assertIn("destination=reservations.pinOrigin(origin,displays.containsKey(origin));", restore)
        self.assertIn("reservations.unpinOrigin(destination)", restore)

        harness = r'''
package dev.pocket.agent;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class DisplayRemovalSequenceTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(4, TimeUnit.SECONDS)) throw new AssertionError("Latch timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
    public static void main(String[] args) throws Exception {
        Object monitor = new Object();
        Set<Integer> removing = new HashSet<>();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger removed = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch unblockCleanup = new CountDownLatch(1);
        CountDownLatch unrelatedCreated = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch unblockClose = new CountDownLatch(1);
        CountDownLatch duringCloseCreated = new CountDownLatch(1);
        Thread cleanup = new Thread(() -> {
            try {
                DisplayRemovalSequence.run(monitor,
                    () -> {
                        check(removing.add(7), "A may be reserved only once");
                        return 7;
                    },
                    id -> {
                        cleanupStarted.countDown();
                        await(unblockCleanup);  // Simulates multi-second shell reconciliation.
                    },
                    id -> {
                        closeStarted.countDown();
                        await(unblockClose);  // Simulates slow VirtualDisplay.release().
                    },
                    id -> {
                        check(removing.contains(id), "A must be reserved through finish");
                        removed.incrementAndGet();
                    },
                    () -> removing.remove(7));
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "slow-display-removal");
        cleanup.setDaemon(true);
        cleanup.start();
        await(cleanupStarted);
        Thread allocation = new Thread(() -> {
            try {
                synchronized(monitor) {
                    check(removing.contains(7), "Slow cleanup reservation must still exist");
                    check(!removing.contains(8), "Unrelated display must not be reserved");
                    created.incrementAndGet();
                }
                unrelatedCreated.countDown();
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "unrelated-display-creation");
        allocation.setDaemon(true);
        allocation.start();
        try {
            check(unrelatedCreated.await(2, TimeUnit.SECONDS),
                "Unrelated creation blocked by slow task reconciliation");
            check(cleanup.isAlive(), "Creation must complete before cleanup is unblocked");
        } finally {
            unblockCleanup.countDown();
        }
        await(closeStarted);
        Thread whileClosing = new Thread(() -> {
            try {
                synchronized(monitor) {
                    check(removing.contains(7), "Closing must retain the display reservation");
                    created.incrementAndGet();
                }
                duringCloseCreated.countDown();
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "creation-during-display-close");
        whileClosing.setDaemon(true);
        whileClosing.start();
        try {
            check(duringCloseCreated.await(2, TimeUnit.SECONDS),
                "Unrelated creation blocked by slow VirtualDisplay.release");
        } finally {
            unblockClose.countDown();
        }
        cleanup.join(4000);
        whileClosing.join(4000);
        allocation.join(4000);
        check(!cleanup.isAlive() && !allocation.isAlive() && !whileClosing.isAlive(), "Threads must terminate");
        if (failure.get() != null) throw new AssertionError(failure.get());
        check(created.get() == 2 && removed.get() == 1, "Both creations and cleanup must complete");
        check(removing.isEmpty(), "Successful cleanup must release reservation");

        // Cleanup failure must release its reservation but never finalize removal.
        try {
            DisplayRemovalSequence.run(monitor,
                () -> { removing.add(9); return 9; },
                id -> { throw new IllegalStateException("Cannot restore borrowed task"); },
                id -> { throw new AssertionError("Must not close display on failed cleanup"); },
                id -> { throw new AssertionError("Must preserve display on failed cleanup"); },
                () -> removing.remove(9));
            throw new AssertionError("Failure must propagate");
        } catch (IllegalStateException expected) {
            check(removing.isEmpty(), "Failed cleanup must be retryable");
        }
    }
}
'''
        with tempfile.TemporaryDirectory(prefix="display-removal-concurrency-") as temporary:
            directory = pathlib.Path(temporary)
            test = directory / "DisplayRemovalSequenceTest.java"
            test.write_text(harness)
            subprocess.run(["javac", "--release", "17", "-d", temporary,
                            str(REMOVAL_SOURCE), str(test)], check=True)
            subprocess.run(["java", "-cp", temporary,
                            "dev.pocket.agent.DisplayRemovalSequenceTest"], check=True)

    def test_concurrent_borrowed_task_restore_cannot_race_destination_removal(self):
        service = SERVICE_SOURCE.read_text()
        restore = service.split("private boolean restoreBorrowedTasks(", 1)[1].split(
            "private void reconcileAndRemoveTasks(", 1
        )[0]
        self.assertIn("reservations.pinOrigin(origin,displays.containsKey(origin))", restore)
        self.assertIn("reservations.unpinOrigin(destination)", restore)
        self.assertIn("reservations.beginRemoval(id)", service)

        harness = r'''
package dev.pocket.agent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class DisplayRestorationReservationsTest {
    static void check(boolean value,String description) {
        if(!value)throw new AssertionError(description);
    }
    static void await(CountDownLatch latch) {
        try {
            if(!latch.await(4,TimeUnit.SECONDS))throw new AssertionError("Timed out");
        } catch(InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
    public static void main(String[] args) throws Exception {
        DisplayRestorationReservations reservations=new DisplayRestorationReservations();
        CountDownLatch pinAcquired=new CountDownLatch(1),finishMove=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread move=new Thread(()->{
            int destination=reservations.pinOrigin(8,true);
            try {
                check(destination==8,"A must reserve B as destination");
                pinAcquired.countDown();
                await(finishMove);  // Represents move-stack and verification.
            } catch(Throwable error) {
                failure.set(error);
            } finally {
                reservations.unpinOrigin(destination);
            }
        },"borrowed-task-migration");
        move.setDaemon(true);
        move.start();
        await(pinAcquired);
        Thread retire=new Thread(()->{
            try {
                try {
                    reservations.beginRemoval(8);
                    throw new AssertionError("B removed while A was restoring to it");
                } catch(IllegalStateException expected) {
                    check(!reservations.isRemoving(8),"B must not be marked removing");
                }
            } catch(Throwable error) {
                failure.set(error);
            }
        },"destination-retirement");
        retire.start();
        retire.join(4000);
        check(!retire.isAlive(),"B removal attempt must return without waiting for shell move");
        finishMove.countDown();
        move.join(4000);
        check(!move.isAlive(),"Restoration must finish");
        if(failure.get()!=null)throw new AssertionError(failure.get());

        reservations.beginRemoval(8);
        check(reservations.isRemoving(8),"B should now be removable");
        check(reservations.pinOrigin(8,true)==0,
            "A restoring after B retirement must target the physical display");
        reservations.finishRemoval(8);
        check(reservations.pinOrigin(8,true)==8,"B can be pinned again after retirement");
        reservations.unpinOrigin(8);

        // Two simultaneous migrations must both finish before B can retire.
        check(reservations.pinOrigin(10,true)==10,"First pin");
        check(reservations.pinOrigin(10,true)==10,"Second pin");
        reservations.unpinOrigin(10);
        try {
            reservations.beginRemoval(10);
            throw new AssertionError("Second pin must still exclude removal");
        } catch(IllegalStateException expected) {}
        reservations.unpinOrigin(10);
        reservations.beginRemoval(10);
        check(reservations.hasReservations(),"Removal is reserved");
        reservations.finishRemoval(10);
        check(!reservations.hasReservations(),"No leaked reservations");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix="display-restoration-pins-") as temporary:
            directory = pathlib.Path(temporary)
            test = directory / "DisplayRestorationReservationsTest.java"
            test.write_text(harness)
            subprocess.run(["javac", "--release", "17", "-d", temporary,
                            str(RESERVATIONS_SOURCE), str(test)], check=True)
            subprocess.run(["java", "-cp", temporary,
                            "dev.pocket.agent.DisplayRestorationReservationsTest"], check=True)

    def test_cleanup_membership_and_ownership(self):
        harness = r'''
package dev.pocket.agent;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
public class DisplayTaskPolicyTest {
    static void equal(Object expected,Object actual) {
        if(!expected.equals(actual))throw new AssertionError("Expected "+expected+", got "+actual);
    }
    public static void main(String[] args) {
        // Redacted shape of the Android 14 / MIUI dump captured on the phone.
        // The last activities header belongs to the worker, but later dump sections
        // contain the user's tasks again. The old parser deleted all of these.
        String dump="""
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):
  * Task{aaa #101 type=standard A=12345:example.personal}
    * Task{bbb #102 type=standard rootTaskId=101}
      rootOfTask=true task=Task{aaa #101 type=standard}
Display #4 (activities from top to bottom):
  * Task{ccc #201 type=standard A=12346:example.worker}

ActivityTaskSupervisor state:
  topDisplayFocusedRootTask=Task{aaa #101 type=standard}
  Display: mDisplayId=0 (organized)
      * Task{aaa #101 type=standard}
        * Task{bbb #102 type=standard rootTaskId=101}
  Display: mDisplayId=4 (organized)
      * Task{ccc #201 type=standard}
  mUserRootTaskInFront={}
        (fullscreen) Task{aaa #101 type=standard}
""";
        equal(Set.of(201),DisplayTaskSnapshot.activities(dump).onDisplay(4));
        equal(Set.of(101),DisplayTaskSnapshot.activities(dump).onDisplay(0));
        equal(0,DisplayTaskSnapshot.activities(dump).taskDisplays.get(102));
        equal(Set.of(42),DisplayTaskSnapshot.activities(
            "Display #7 (activities from top to bottom):\n  RootTask #42: type=standard\n").onDisplay(7));
        DisplayTaskSnapshot fallback=DisplayTaskSnapshot.activities(dump.replace(
            "  * Task{ccc #201 type=standard A=12346:example.worker}",
            "  * Task{ccc #201 type=standard A=12346:example.worker}\n    * Hist #0: ActivityRecord{abc u0 com.android.vending/.Main t201}\n      mVisibleRequested=true"));
        equal(true,fallback.packageOn("com.android.vending",4));
        equal(false,fallback.packageOn("com.android.vending",0));
        equal(true,fallback.packageOutside("com.android.vending",0));
        String empty=dump.replace("  * Task{ccc #201 type=standard A=12346:example.worker}\n", "");
        equal(Set.of(),DisplayTaskSnapshot.activities(empty).onDisplay(4));
        // Changing display order, nested tasks and task references cannot change ownership.
        equal(Set.of(201),DisplayTaskSnapshot.activities(dump.replace(
            "Display #4 (activities from top to bottom):", "Display #7 (activities from top to bottom):")).onDisplay(7));
        equal(Set.of(),DisplayTaskSnapshot.activities("  topDisplayFocusedRootTask=Task{aaa #101}\n").onDisplay(4));

        String stacks="""
RootTask id=101 bounds=[0,0][1080,2460] displayId=0 userId=0
  taskId=101: example.personal/.Main bounds=[0,0][1080,2460]
  taskId=102: example.child/.Main bounds=[0,0][1080,2460]
RootTask id=201 bounds=[0,0][720,1280] displayId=4 userId=0
  taskId=201: example.worker/.Main bounds=[0,0][720,1280]
RootTask id=301 bounds=[0,0][720,1280] displayId=5 userId=0
  taskId=301: example.otherworker/.Main bounds=[0,0][720,1280]
""";
        DisplayTaskSnapshot before=DisplayTaskSnapshot.stacks(stacks);
        equal(Set.of(201),before.onDisplay(4));
        equal(Set.of(),before.onDisplay(6));
        equal(true,before.packageOutside("example.personal",4));
        equal(true,before.packageOutside("example.otherworker",4));
        equal(false,before.packageOutside("example.worker",4));
        equal(0,before.taskDisplays.get(102));
        DisplayTaskSnapshot childMoved=DisplayTaskSnapshot.stacks(stacks+
            "RootTask id=102 bounds=[0,0][720,1280] displayId=4 userId=0\n");
        equal(Set.of(),childMoved.removable(4,before.taskDisplays));
        // A reused personal task now on the worker display must be restored, never removed.
        DisplayTaskSnapshot borrowed=DisplayTaskSnapshot.stacks(stacks.replace("displayId=0", "displayId=4"));
        equal(Set.of(201),borrowed.removable(4,Map.of(101,0,301,5)));
        // A formerly worker-owned task moved to the physical display is no longer deletable.
        DisplayTaskSnapshot moved=DisplayTaskSnapshot.stacks(stacks.replace("displayId=4", "displayId=0"));
        equal(Set.of(),moved.removable(4,Map.of(101,0,301,5)));
        // An outside task may move through physical display 0 before arriving on B.
        Map<Integer,Integer> origins=new LinkedHashMap<>();
        String root="RootTask id=401 bounds=[0,0][720,1280] displayId=";
        DisplayTaskSnapshot.stacks(root+"4 userId=0\n").protectOutside(7,origins);
        equal(4,origins.get(401));
        DisplayTaskSnapshot.stacks(root+"7 userId=0\n").protectOutside(7,origins);
        equal(4,origins.get(401));
        DisplayTaskSnapshot.stacks(root+"0 userId=0\n").protectOutside(7,origins);
        equal(0,origins.get(401));
        DisplayTaskSnapshot.stacks(root+"5 userId=0\n").protectOutside(7,origins);
        equal(5,origins.get(401));
        DisplayTaskSnapshot returned=DisplayTaskSnapshot.stacks(root+"7 userId=0\n");
        returned.protectOutside(7,origins);
        equal(5,origins.get(401));
        equal(Set.of(),returned.removable(7,origins));
        // Provisional lease survives failed native cleanup; other owners are untouched.
        DisplayLeases leases=new DisplayLeases();
        Object generationA=new Object(),generationB=new Object();
        leases.provision("worker-a",7,generationA);
        leases.provision("worker-b",8,generationA);
        equal(true,leases.provisional("worker-a"));
        equal(false,leases.release("worker-a",generationA,id->false));
        equal(2,leases.size());
        equal(7,leases.get("worker-a"));
        equal(true,leases.provisional("worker-a"));
        equal(true,leases.release("worker-a",generationA,id->id==7));
        equal(1,leases.size());
        equal(8,leases.get("worker-b"));
        leases.activate("worker-b",8);
        equal(false,leases.provisional("worker-b"));
        // Runtime shutdown must try every owner's cleanup after the first fails.
        DisplayLeases shutdown=new DisplayLeases();
        shutdown.provision("stuck",10,generationA);
        shutdown.provision("safe",11,generationA);
        shutdown.provision("also-safe",12,generationA);
        List<String> attempts=new ArrayList<>();
        DisplayLeases.releaseIndependently(shutdown.owners(),owner->{
            attempts.add(owner);
            if(owner.equals("stuck"))throw new IllegalStateException("Cannot restore borrowed task");
            return shutdown.release(owner,generationA,id->true);
        });
        equal(List.of("stuck","safe","also-safe"),attempts);
        equal(Set.of("stuck"),shutdown.owners());
        equal(10,shutdown.get("stuck"));
        DisplayLeases.releaseIndependently(shutdown.owners(),owner->shutdown.release(owner,generationA,id->true));
        equal(0,shutdown.size());
        // An earlier runtime cannot retire a replacement's lease for the same owner.
        leases.provision("main",18,generationA);
        equal(true,leases.release("main",generationA,id->true));
        leases.provision("main",19,generationB);
        equal(false,leases.ownedBy("main",generationA));
        equal(true,leases.ownedBy("main",generationB));
        DisplayLeases.releaseIndependently(Set.of("main"),owner->{
            if(leases.ownedBy(owner,generationA))
                return leases.release(owner,generationA,id->true);
            return false;
        });
        equal(19,leases.get("main"));
        try {
            leases.release("main",generationA,id->true);
            throw new AssertionError("A stale generation must not release another runtime");
        } catch(IllegalStateException expected) {}
        leases.clear(); // Binder invalidation also discards the stale generation.
        leases.provision("main",20,generationB);
        equal(false,leases.ownedBy("main",generationA));
        equal(20,leases.get("main"));
        equal(true,leases.release("main",generationB,id->true));
        // Unrecognized records fail closed rather than guessing a display from references.
        equal(Set.of(),DisplayTaskSnapshot.stacks("Task{aaa #101 displayId=4}\n").onDisplay(4));
    }
}
'''
        with tempfile.TemporaryDirectory(prefix="display-task-policy-") as temporary:
            directory = pathlib.Path(temporary)
            test = directory / "DisplayTaskPolicyTest.java"
            test.write_text(harness)
            subprocess.run(["javac", "--release", "17", "-d", temporary, str(SOURCE), str(LEASE_SOURCE), str(test)], check=True)
            subprocess.run(["java", "-cp", temporary, "dev.pocket.agent.DisplayTaskPolicyTest"], check=True)


if __name__ == "__main__":
    unittest.main()
