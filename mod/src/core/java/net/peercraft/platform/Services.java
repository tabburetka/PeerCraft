package net.peercraft.platform;

import net.peercraft.platform.services.PeercraftPlatform;

import java.util.Iterator;
import java.util.ServiceLoader;

public final class Services {
    public static final PeercraftPlatform PLATFORM = load();

    private Services() {
    }

    private static PeercraftPlatform load() {
        Iterator<PeercraftPlatform> found = ServiceLoader.load(PeercraftPlatform.class).iterator();
        if (!found.hasNext()) {
            throw new IllegalStateException("No " + PeercraftPlatform.class.getName() + " implementation found — "
                    + "each loader module must register one under META-INF/services");
        }
        return found.next();
    }
}
