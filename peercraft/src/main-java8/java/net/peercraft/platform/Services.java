package net.peercraft.platform;

import net.peercraft.platform.services.PeercraftPlatform;

import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * Java 8 backport of {@code src/main/java/.../Services.java} for the Minecraft 1.16.5 target:
 * {@link ServiceLoader#findFirst()} is Java 9, so the first registered implementation is
 * pulled off the iterator instead. Behaviour is identical — exactly one {@code PeercraftPlatform}
 * is registered per loader module under {@code META-INF/services}.
 */
public final class Services {
    public static final PeercraftPlatform PLATFORM = loadPlatform();

    private Services() {
    }

    private static PeercraftPlatform loadPlatform() {
        Iterator<PeercraftPlatform> it = ServiceLoader.load(PeercraftPlatform.class).iterator();
        if (it.hasNext()) {
            return it.next();
        }
        throw new IllegalStateException(
                "No " + PeercraftPlatform.class.getName() + " implementation found — "
                        + "each loader module must register one under META-INF/services");
    }
}
