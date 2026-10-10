package net.peercraft.forge1710;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import net.peercraft.PeerCraftCommon;
import net.peercraft.network.p2p.P2PBridge;

/**
 * Forge 1.7.10 entry point for the PeerCraft backport (twin of {@code PeerCraftForge} in
 * peercraft-forge-1122). The real work lives in the shared, loader-agnostic
 * {@code PeerCraftCommon} / {@code PeerCraftClientCommon}; this class only bridges Forge's
 * {@code @Mod} lifecycle to them and picks the client/server proxy.
 *
 * <p>1.7.10 delta vs the 1.12.2 backport: FML lives under {@code cpw.mods.fml.*}, not
 * {@code net.minecraftforge.fml.*} (the package was renamed in 1.8).
 *
 * <p>{@code dependencies} declares the hard requirement on the Mixin loader: PeerCraft's
 * coremod ({@link PeerCraftCoreMod}) implements {@code io.github.tox1cozz.mixinbooterlegacy}'s
 * {@code IEarlyMixinLoader}, which is supplied by <b>UniMixins</b> (it bundles MixinBooterLegacy
 * and registers the {@code mixinbooterlegacy} mod id for compatibility). Without it FML would
 * either skip the coremod silently (mixins never apply — "Open to LAN" does nothing) or crash
 * with a bare {@code NoClassDefFoundError}; with this line the player instead gets FML's
 * standard "missing mod" screen naming what to install.
 */
@Mod(modid = PeerCraftForge.MOD_ID, name = PeerCraftForge.MOD_NAME, version = PeerCraftForge.VERSION,
        acceptedMinecraftVersions = "[1.7.10]",
        dependencies = "required-after:mixinbooterlegacy")
public class PeerCraftForge {

    public static final String MOD_ID = "peercraft";
    public static final String MOD_NAME = "PeerCraft";
    public static final String VERSION = "3.4.2";

    @SidedProxy(
            clientSide = "net.peercraft.forge1710.ClientProxy",
            serverSide = "net.peercraft.forge1710.CommonProxy")
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

    // The 1.12.2 backport cancels the rendezvous keepalive from a HEAD inject on
    // IntegratedServer.stopServer; on 1.7.10 that method isn't overridden by IntegratedServer
    // (Mixin won't touch inherited methods), so this Forge lifecycle event stands in.
    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        P2PBridge.INSTANCE.cancelRendezvous();
    }
}
