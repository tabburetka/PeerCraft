package net.peercraft.forge189;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.peercraft.PeerCraftCommon;

/**
 * Forge 1.8.9 entry point for the PeerCraft backport. The real work lives in the shared,
 * loader-agnostic {@code PeerCraftCommon} / {@code PeerCraftClientCommon}; this class only
 * bridges Forge's {@code @Mod} lifecycle to them and picks the client/server proxy.
 *
 * <p>Unlike the 1.7.10 / 1.12.2 backports there is no Mixin loader mod to depend on: Mixin is
 * shaded into the jar and started by its tweaker (see the jar manifest in build.gradle.kts).
 */
@Mod(modid = PeerCraftForge.MOD_ID, name = PeerCraftForge.MOD_NAME, version = PeerCraftForge.VERSION,
        acceptedMinecraftVersions = "[1.8.9]")
public class PeerCraftForge {

    public static final String MOD_ID = "peercraft";
    public static final String MOD_NAME = "PeerCraft";
    public static final String VERSION = "3.5.0";

    @SidedProxy(
            clientSide = "net.peercraft.forge189.ClientProxy",
            serverSide = "net.peercraft.forge189.CommonProxy")
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
