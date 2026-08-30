package net.peercraft.forge1122;

import net.minecraftforge.common.MinecraftForge;
import net.peercraft.client.PeerCraftClientCommon;
import net.peercraft.client.gui.PeerCraftScreenEvents;

/**
 * Client proxy: starts PeerCraft's client-side networking (rendezvous socket + P2P proxy) and
 * registers the GUI hooks (title-screen redirect, Open-to-LAN options).
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void init() {
        super.init();
        PeerCraftClientCommon.initClient();
        MinecraftForge.EVENT_BUS.register(new PeerCraftScreenEvents());
    }
}
