package net.peercraft.forge1122;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.peercraft.PeerCraftCommon;

/**
 * Forge 1.12.2 entry point for the PeerCraft backport. The real work lives in the shared,
 * loader-agnostic {@code PeerCraftCommon} / {@code PeerCraftClientCommon}; this class only
 * bridges Forge's {@code @Mod} lifecycle to them and picks the client/server proxy.
 */
@Mod(modid = PeerCraftForge.MOD_ID, name = PeerCraftForge.MOD_NAME, version = PeerCraftForge.VERSION,
        acceptedMinecraftVersions = "[1.12.2]")
public class PeerCraftForge {

    public static final String MOD_ID = "peercraft";
    public static final String MOD_NAME = "PeerCraft";
    public static final String VERSION = "2.0.0";

    @SidedProxy(
            clientSide = "net.peercraft.forge1122.ClientProxy",
            serverSide = "net.peercraft.forge1122.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        PeerCraftCommon.init();
        proxy.preInit();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        PeerCraftCommon.onServerStarted();
    }
}
