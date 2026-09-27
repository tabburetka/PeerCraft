package net.peercraft.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
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
 * Gate opening a singleplayer world that was handed off to someone else and hasn't been
 * reclaimed since — see {@code PeercraftWorldMeta.isStaleAfterHandoff()}. Cancels the vanilla
 * load and shows {@link HandoffStaleWorldWarningScreen}; "proceed" re-enters this same method
 * with a thread-local bypass set, so the second call runs vanilla untouched.
 */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {

    @Unique
    private static final ThreadLocal<Boolean> peercraft$bypass = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "openWorld(Ljava/lang/String;Ljava/lang/Runnable;)V", at = @At("HEAD"), cancellable = true)
    private void peercraft$warnStaleHandoff(String levelId, Runnable onFail, CallbackInfo ci) {
        Minecraft peercraft$client = Minecraft.getInstance();
        if (net.peercraft.client.handoff.HandoffNetworkRecovery.deferOpen(
                net.peercraft.client.handoff.SuccessorLauncher.savesDirectory().resolve(levelId), peercraft$client::execute,
                () -> peercraft$client.createWorldOpenFlows().openWorld(levelId, onFail), failure -> {
                    net.peercraft.client.handoff.SafeHandoffPlatform.INSTANCE.error("peercraft.handoff.abort.recovery_failed");
                })) { ci.cancel(); return; }
        if (net.peercraft.network.handoff.WorldInstallRecovery.deferOpen(
                net.peercraft.client.handoff.SuccessorLauncher.savesDirectory(), peercraft$client::execute,
                () -> peercraft$client.createWorldOpenFlows().openWorld(levelId, onFail), failure -> {
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

        Minecraft mc = Minecraft.getInstance();
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
                    mc.createWorldOpenFlows().openWorld(levelId, onFail);
                },
                () -> PeerCraftUi.setScreen(mc, new SelectWorldScreen(new TitleScreen()))));
    }
    @Inject(method = "openWorldLoadLevelData", at = @At("HEAD"))
    private void peercraft$captureNativeLoad(net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess access,
            Runnable onFail, CallbackInfo ci) {
        net.peercraft.network.handoff.HandoffLaunchContext.capture(access,
                access.getLevelPath(net.minecraft.world.level.storage.LevelResource.ROOT));
    }

    @Inject(method = "openWorldDoLoad", at = @At("HEAD"), cancellable = true)
    private void peercraft$rejectCancelledNativeLoad(net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess access,
            net.minecraft.server.WorldStem stem, net.minecraft.server.packs.repository.PackRepository packs, CallbackInfo ci) {
        if (!net.peercraft.network.handoff.HandoffLaunchContext.permits(access)) {
            try { access.close(); } catch (java.io.IOException failure) {
                org.slf4j.LoggerFactory.getLogger("peercraft").warn("[Handoff] Cancelled native access failed to close", failure);
            }
            stem.close(); ci.cancel();
        }
    }
    @Inject(method = "openWorldDoLoad", at = @At("RETURN"))
    private void peercraft$bindCreatedServer(net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess access,
            net.minecraft.server.WorldStem stem, net.minecraft.server.packs.repository.PackRepository packs, CallbackInfo ci) {
        net.minecraft.server.MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null && !net.peercraft.network.handoff.HandoffLaunchContext.bind(access, server,
                net.peercraft.client.handoff.WorldArchiver.worldDir(server))) server.halt(false);
    }
}
