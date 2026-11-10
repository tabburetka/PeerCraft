package net.peercraft.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.peercraft.PeerCraftCommon;

public final class PeerCraftModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        PeerCraftCommon.init();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> PeerCraftCommon.onServerStarted());
    }
}
