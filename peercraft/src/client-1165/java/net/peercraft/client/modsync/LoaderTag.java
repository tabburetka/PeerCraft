package net.peercraft.client.modsync;

// Minecraft 1.16.5 Fabric backport of src/main/.../client/modsync/LoaderTag.java — verbatim
// (only Class.forName probes). On a pure Fabric 1.16.5 client
// net.fabricmc.loader.api.FabricLoader is present, so current() resolves to Loader.FABRIC.
// Keep in sync with the original.

import net.peercraft.network.modsync.ModSyncProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which mod loader this client is running under, for mod-sync's loader-mismatch check.
 * Detected by class presence rather than a compile-time split.
 */
public final class LoaderTag {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final ModSyncProtocol.Loader DETECTED = detectAndLog();

    private static ModSyncProtocol.Loader detectAndLog() {
        ModSyncProtocol.Loader d = detect();
        LOGGER.info("[ModSync] Загрузчик определён как: {}", d);
        return d;
    }

    private LoaderTag() {
    }

    public static ModSyncProtocol.Loader current() {
        return DETECTED;
    }

    private static ModSyncProtocol.Loader detect() {
        // NeoForge / Forge FIRST: a NeoForge instance running Sinytra Connector ALSO has
        // net.fabricmc.loader.api.FabricLoader on the classpath (Connector's Fabric shim).
        if (classPresent("net.neoforged.fml.loading.FMLLoader") || classPresent("net.neoforged.fml.ModList")) {
            return ModSyncProtocol.Loader.NEOFORGE;
        }
        if (classPresent("net.minecraftforge.fml.common.Mod") || classPresent("cpw.mods.fml.common.Mod")) {
            return ModSyncProtocol.Loader.FORGE;
        }
        if (classPresent("net.fabricmc.loader.api.FabricLoader")) {
            return ModSyncProtocol.Loader.FABRIC;
        }
        return ModSyncProtocol.Loader.UNKNOWN;
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, LoaderTag.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
