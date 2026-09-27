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
                    peercraft$bypass.set(Boolean.TRUE);
                    mc.createWorldOpenFlows().openWorld(levelId, onFail);
                },
                () -> PeerCraftUi.setScreen(mc, new SelectWorldScreen(new TitleScreen()))));
    }
}
