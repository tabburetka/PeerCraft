package net.peercraft.forge1710;

import net.minecraftforge.common.MinecraftForge;
import net.peercraft.client.PeerCraftClientCommon;
import net.peercraft.client.gui.PeerCraftScreenEvents;

/**
 * Client proxy: starts PeerCraft's client-side networking (rendezvous socket + P2P proxy) and
 * registers the GUI hooks (title-screen redirect, Open-to-LAN options, and — since 1.7.10 has
 * no shareToLAN Forge event — the client-tick watcher that starts the host bridge). The
 * {@code net.minecraftforge.common.MinecraftForge.EVENT_BUS} handle is unchanged from 1.12.2;
 * only {@code cpw.mods.fml.*} moved.
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void init() {
        super.init();
        PeerCraftClientCommon.initClient();
        PeerCraftScreenEvents screenEvents = new PeerCraftScreenEvents();
        MinecraftForge.EVENT_BUS.register(screenEvents);
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(screenEvents);
    }
}
