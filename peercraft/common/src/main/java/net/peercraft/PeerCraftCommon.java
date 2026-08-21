package net.peercraft;

import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loader-agnostic mod init — called from each loader's thin entrypoint (see the {@code fabric}/{@code neoforge} modules). */
public final class PeerCraftCommon {
    public static final String MOD_ID = "peercraft";

    // This logger is used to write text to the console and the log file.
    // It is considered best practice to use your mod id as the logger's name.
    // That way, it's clear which mod wrote info, warnings, and errors.
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private PeerCraftCommon() {
    }

    public static void init() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.
        LOGGER.info("Hello PeerCraft world!");
    }

    public static void onServerStarted() {
        LOGGER.info("[PeerCraft] одиночная игра запущенна");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
