package net.peercraft.network.handoff;

import java.nio.file.Path;

/** Explicit user consent scoped to one synchronous native world-open entry. */
public final class RecoveryOpenOverride {
    private static final ThreadLocal<Path> opening = new ThreadLocal<>();
    private RecoveryOpenOverride() { }

    public static boolean allows(Path world) {
        return world.toAbsolutePath().normalize().equals(opening.get());
    }

    public static void open(Path world, Runnable nativeOpen) {
        Path previous = opening.get();
        opening.set(world.toAbsolutePath().normalize());
        try { nativeOpen.run(); }
        finally {
            if (previous == null) opening.remove(); else opening.set(previous);
        }
    }
}
