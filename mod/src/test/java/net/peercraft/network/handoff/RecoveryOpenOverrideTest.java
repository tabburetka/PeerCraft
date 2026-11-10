package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryOpenOverrideTest {
    @TempDir Path root;

    @Test void consentAppliesOnlyToSelectedWorldAndExpiresEvenWhenLoadingFails() throws Exception {
        Path selected = root.resolve("selected"), other = root.resolve("other");
        assertThrows(IllegalStateException.class, () -> RecoveryOpenOverride.open(selected, () -> {
            assertTrue(RecoveryOpenOverride.allows(selected.resolve(".")));
            assertFalse(RecoveryOpenOverride.allows(other));
            java.util.concurrent.atomic.AtomicBoolean workerAllowed = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread worker = new Thread(() -> workerAllowed.set(RecoveryOpenOverride.allows(selected)));
            worker.start();
            try { worker.join(); } catch (InterruptedException e) { throw new AssertionError(e); }
            assertFalse(workerAllowed.get());
            throw new IllegalStateException("Native load failed");
        }));
        assertFalse(RecoveryOpenOverride.allows(selected));
        assertFalse(RecoveryOpenOverride.allows(other));
    }
}
