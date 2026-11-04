package net.peercraft.client.handoff;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/** Overrides only command ownership; vanilla lifecycle ownership stays with the local host. */
public final class HandoffCommandOwner {
    private static final Map<MinecraftServer, Optional<UUID>> OWNERS =
            Collections.synchronizedMap(new WeakHashMap<MinecraftServer, Optional<UUID>>());

    private HandoffCommandOwner() { }

    public static UUID originalOwner(MinecraftServer server) {
        if (!(server instanceof IntegratedServer)) return null;
        synchronized (OWNERS) {
            Optional<UUID> cached = OWNERS.get(server);
            if (cached == null) {
                try { cached = Optional.ofNullable(HandoffOwnerPolicy.read(WorldArchiver.worldDir(server))); }
                catch (IOException invalid) { throw new IllegalStateException("Invalid handoff owner record", invalid); }
                OWNERS.put(server, cached);
            }
            return cached.orElse(null);
        }
    }

    public static Boolean override(MinecraftServer server, UUID playerId) {
        UUID owner = originalOwner(server);
        return owner == null ? null : owner.equals(playerId);
    }
}
