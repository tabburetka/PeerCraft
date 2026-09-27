package net.peercraft.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.peercraft.client.gui.HandoffStaleWorldWarningScreen;
import net.peercraft.client.gui.PeerCraftUi;
import net.peercraft.client.handoff.PeercraftWorldMeta;
import net.peercraft.config.PeerCraftConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../client/mixin/WorldOpenFlowsMixin.java}.
 * 1.16.5 predates the {@code WorldOpenFlows} abstraction (added 1.20.2) — every "open this
 * singleplayer world" path goes straight through {@code Minecraft.loadLevel(String)}, so that's
 * the mixin target here instead. No {@code onFail} callback to pass through (1.16.5's
 * {@code loadLevel} doesn't take one).
 */
@Mixin(Minecraft.class)
public abstract class WorldOpenFlowsMixin {

    @Unique
    private static final ThreadLocal<Boolean> peercraft$bypass = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "loadLevel", at = @At("HEAD"), cancellable = true)
    private void peercraft$warnStaleHandoff(String levelId, CallbackInfo ci) {
        Minecraft peercraft$client = (Minecraft) (Object) this;
        if (net.peercraft.client.handoff.HandoffNetworkRecovery.deferOpen(
                net.peercraft.client.handoff.SuccessorLauncher.savesDirectory().resolve(levelId), peercraft$client::execute,
                () -> peercraft$client.loadLevel(levelId), failure -> {
                    net.peercraft.client.handoff.SafeHandoffPlatform.INSTANCE.error("peercraft.handoff.abort.recovery_failed");
                })) { ci.cancel(); return; }
        if (net.peercraft.network.handoff.WorldInstallRecovery.deferOpen(
                net.peercraft.client.handoff.SuccessorLauncher.savesDirectory(), peercraft$client::execute,
                () -> peercraft$client.loadLevel(levelId), failure -> {
                    net.peercraft.client.gui.HandoffStatusScreen error = new net.peercraft.client.gui.HandoffStatusScreen(new TitleScreen(), "");
                    error.onAborted("peercraft.handoff.abort.recovery_failed");
                    PeerCraftUi.setScreen(peercraft$client, error);
                })) {
            ci.cancel(); return;
        }
        if (Boolean.TRUE.equals(peercraft$bypass.get())) {
            peercraft$bypass.set(Boolean.FALSE);
            return;
        }
        if (PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode()) || !PeerCraftConfig.handoff()) {
            return;
        }

        Minecraft mc = (Minecraft) (Object) this;
        PeercraftWorldMeta meta;
        try {
            Path worldDir = mc.getLevelSource().getBaseDir().resolve(levelId);
            meta = PeercraftWorldMeta.loadOrNull(worldDir);
        } catch (RuntimeException e) {
            return;
        }
        if (meta == null || !meta.isStaleAfterHandoff()) {
            return;
        }

        ci.cancel();
        PeerCraftUi.setScreen(mc, new HandoffStaleWorldWarningScreen(meta,
                () -> {
                    try { net.peercraft.client.handoff.SafeHandoffPlatform.INSTANCE.forkStaleWorld(
                            net.peercraft.client.handoff.SuccessorLauncher.savesDirectory().resolve(levelId)); }
                    catch (java.io.IOException failure) { net.peercraft.client.handoff.SafeHandoffPlatform.INSTANCE.error("peercraft.handoff.abort.recovery_failed"); return; }
                    peercraft$bypass.set(Boolean.TRUE);
                    mc.loadLevel(levelId);
                },
                () -> PeerCraftUi.setScreen(mc, new SelectWorldScreen(new TitleScreen()))));
    }
    @Inject(method = "doLoadLevel", at = @At("HEAD"), cancellable = true)
    private void peercraft$cancelDeferredNativeLoad(String levelId, net.minecraft.core.RegistryAccess.RegistryHolder registries,
            java.util.function.Function<?, ?> dataPacks, com.mojang.datafixers.util.Function4<?, ?, ?, ?, ?> worldData,
            boolean safeMode, @org.spongepowered.asm.mixin.injection.Coerce Object dialogType, CallbackInfo ci) {
        net.peercraft.network.handoff.HandoffLaunchContext.capture(registries,
                net.peercraft.client.handoff.SuccessorLauncher.savesDirectory().resolve(levelId));
        if (!net.peercraft.network.handoff.HandoffLaunchContext.permits(registries)) ci.cancel();
    }
    @Inject(method = "doLoadLevel", at = @At("RETURN"))
    private void peercraft$bindCreatedServer(String levelId, net.minecraft.core.RegistryAccess.RegistryHolder registries,
            java.util.function.Function<?, ?> dataPacks, com.mojang.datafixers.util.Function4<?, ?, ?, ?, ?> worldData,
            boolean safeMode, @org.spongepowered.asm.mixin.injection.Coerce Object dialogType, CallbackInfo ci) {
        net.minecraft.server.MinecraftServer server = ((Minecraft) (Object) this).getSingleplayerServer();
        if (server != null && !net.peercraft.network.handoff.HandoffLaunchContext.bind(registries, server,
                net.peercraft.client.handoff.WorldArchiver.worldDir(server))) server.halt(false);
    }
}
