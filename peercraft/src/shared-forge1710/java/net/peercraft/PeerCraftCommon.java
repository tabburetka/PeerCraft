package net.peercraft;

import net.minecraft.util.ResourceLocation;  // same package on 1.7.10 as on 1.12.2 (net.minecraft.resources only from 1.16.5+)
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-agnostic mod init — called from each loader's thin entrypoint.
 *
 * <p>Forge 1.7.10 backport of {@code src/main/java/.../PeerCraftCommon.java}: the only
 * difference from the shared source is {@link #id(String)} — the plain two-arg
 * {@link ResourceLocation} constructor (the static {@code fromNamespaceAndPath(...)} factory
 * only arrived in 1.21, and {@code ResourceLocation} was renamed to {@code Identifier} in
 * 1.21.11). Byte-identical to the {@code src/shared-forge1122} twin; hand-forked (rather than
 * gated) so the multi-way version split stays out of the shared source.
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
