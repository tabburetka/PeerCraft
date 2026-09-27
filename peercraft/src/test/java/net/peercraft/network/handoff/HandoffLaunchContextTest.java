package net.peercraft.network.handoff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
class HandoffLaunchContextTest {
    @TempDir Path root;
    @Test void cancelledLoadCannotBorrowNewAttemptOrPublishAnotherWorld() {
        AtomicBoolean active = new AtomicBoolean(true); Path a = root.resolve("A"), b = root.resolve("B");
        HandoffLaunchContext old = HandoffLaunchContext.begin(a, active::get); Object access = new Object();
        HandoffLaunchContext.capture(access, a); assertTrue(HandoffLaunchContext.permits(access));
        assertFalse(old.owns(new Object(), b)); Object server = new Object(); assertTrue(old.owns(server, a));
        assertFalse(old.owns(new Object(), a)); active.set(false);
        HandoffLaunchContext.begin(a, () -> true); HandoffLaunchContext.capture(access, a);
        assertFalse(HandoffLaunchContext.permits(access)); assertFalse(old.owns(server, a));
        Object manual = new Object(); assertTrue(HandoffLaunchContext.permits(manual));
    }
}
