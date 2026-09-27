package net.peercraft.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.world.WorldSettings;
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
 * Forge 1.7.10 backport of {@code src/main/.../client/mixin/WorldOpenFlowsMixin.java} — mirrors
 * the 1.12.2 twin exactly (same mixin target, {@code Minecraft.launchIntegratedServer(String,
 * String, WorldSettings)}, readable-named on 1.7.10 too), except the world-list screen is
 * {@code GuiSelectWorld} (single-arg constructor) rather than {@code GuiWorldSelection}, and the
 * saves root is {@code Minecraft.mcDataDir} rather than {@code gameDir}.
 */
@Mixin(Minecraft.class)
public abstract class WorldOpenFlowsMixin {

    @Unique
    private static final ThreadLocal<Boolean> peercraft$bypass = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "launchIntegratedServer", at = @At("HEAD"), cancellable = true)
    private void peercraft$warnStaleHandoff(String folderName, String worldName, WorldSettings worldSettingsIn, CallbackInfo ci) {
        Minecraft peercraft$client = (Minecraft) (Object) this;
        if (net.peercraft.network.handoff.WorldInstallRecovery.deferOpen(
                net.peercraft.client.handoff.SuccessorLauncher.savesDirectory(), peercraft$client::func_152344_a,
                () -> peercraft$client.launchIntegratedServer(folderName, worldName, worldSettingsIn), failure -> {
                    net.peercraft.client.gui.HandoffStatusScreen error = new net.peercraft.client.gui.HandoffStatusScreen(new GuiMainMenu(), "");
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
            Path worldDir = mc.mcDataDir.toPath().resolve("saves").resolve(folderName);
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
                    mc.launchIntegratedServer(folderName, worldName, worldSettingsIn);
                },
                () -> PeerCraftUi.setScreen(mc, new GuiSelectWorld(new GuiMainMenu()))));
    }
}
