package net.peercraft.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.peercraft.PeerCraftCommon;
import net.peercraft.client.PeerCraftClientCommon;

@Mod(PeerCraftCommon.MOD_ID)
public final class PeerCraftModNeoForge {

    public PeerCraftModNeoForge(IEventBus modEventBus) {
        PeerCraftCommon.init();
        // FMLClientSetupEvent only ever fires in a client physical environment — no separate
        // Dist check needed, this mod is entirely client-side (see AccountStorage/P2PBridge).
        modEventBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.register(this);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        PeerCraftClientCommon.initClient();
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        PeerCraftCommon.onServerStarted();
    }
}
