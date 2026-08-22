package net.peercraft.platform.services;

import java.nio.file.Path;

/**
 * Seam between common code and whatever mod loader it's actually running under — implemented
 * once per loader (see {@code net.peercraft.fabric.platform.FabricPlatform} and
 * {@code net.peercraft.neoforge.platform.NeoForgePlatform}) and wired up via
 * {@link net.peercraft.platform.Services} through {@link java.util.ServiceLoader}.
 */
public interface PeercraftPlatform {
    /** The game's config directory (e.g. {@code .minecraft/config}). */
    Path getConfigDir();
}
