package net.peercraft.platform;

import net.peercraft.platform.services.PeercraftPlatform;

import java.util.ServiceLoader;

public final class Services {
    public static final PeercraftPlatform PLATFORM = ServiceLoader.load(PeercraftPlatform.class)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                    "No " + PeercraftPlatform.class.getName() + " implementation found — "
                            + "each loader module must register one under META-INF/services"));

    private Services() {
    }
}
