package net.peercraft;

import net.minecraft.util.ResourceLocation;  // 1.12.2 package (net.minecraft.resources in 1.16.5+)
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-agnostic mod init — called from each loader's thin entrypoint.
 *
 * <p>Forge 1.12.2 backport of {@code src/main/java/.../PeerCraftCommon.java}: the only
 * difference is {@link #id(String)}, which on 1.16.5 uses the plain two-arg
 * {@link ResourceLocation} constructor — the static {@code fromNamespaceAndPath(...)} factory
 * only arrived in 1.21, and {@code ResourceLocation} was renamed to {@code Identifier} in
 * 1.21.11. Hand-forked (rather than gated) so the multi-way version split stays out of the
 * shared source.
 */
public final class PeerCraftCommon {
    public static final String MOD_ID = "peercraft";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private PeerCraftCommon() {
    }

    public static void init() {
        LOGGER.info("Hello PeerCraft world!");
    }

    public static void onServerStarted() {
        LOGGER.info("[PeerCraft] одиночная игра запущенна");
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
