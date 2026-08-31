package net.peercraft.platform.services;

import java.nio.file.Path;
import java.util.List;

/**
 * Seam between common code and whatever mod loader it's actually running under — implemented
 * once per loader (see {@code net.peercraft.fabric.platform.FabricPlatform} and
 * {@code net.peercraft.neoforge.platform.NeoForgePlatform}) and wired up via
 * {@link net.peercraft.platform.Services} through {@link java.util.ServiceLoader}.
 */
public interface PeercraftPlatform {
    /** The game's config directory (e.g. {@code .minecraft/config}). */
    Path getConfigDir();

    /**
     * The game's {@code mods} directory — where mod-sync writes downloaded jars. Both Fabric
     * and NeoForge scan this on every launch.
     */
    Path getModsDir();

    /**
     * Every currently-installed mod as {@link PlatformMod} (id, version, jar path, environment,
     * nested flag) — the raw input to mod-sync's scan/diff. Order is unspecified.
     */
    List<PlatformMod> getInstalledMods();
}
