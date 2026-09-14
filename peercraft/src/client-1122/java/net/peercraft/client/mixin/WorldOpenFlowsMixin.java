package net.peercraft.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiWorldSelection;
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
 * Forge 1.12.2 backport of {@code src/main/.../client/mixin/WorldOpenFlowsMixin.java}. 1.12.2
 * predates the {@code WorldOpenFlows} abstraction (added 1.20.2) and has no per-screen GUI
 * mixins (see {@code PeerCraftScreenEvents}'s doc for why) — but the actual "load this
 * singleplayer world" entry point, {@code Minecraft.launchIntegratedServer(String, String,
 * WorldSettings)}, is NOT a {@code GuiScreen}, so it's mixined directly here just like the
 * 1.16.5 twin mixins {@code Minecraft.loadLevel}. A {@code null} {@code WorldSettings} arg means
 * "load the existing world" (vanilla's own {@code GuiWorldSelection}); a non-null one is world
 * creation, never stale — {@code meta == null} for a brand-new folder makes that case a no-op
 * below regardless.
 */
@Mixin(Minecraft.class)
public abstract class WorldOpenFlowsMixin {

    @Unique
    private static final ThreadLocal<Boolean> peercraft$bypass = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "launchIntegratedServer", at = @At("HEAD"), cancellable = true)
    private void peercraft$warnStaleHandoff(String folderName, String worldName, WorldSettings worldSettingsIn, CallbackInfo ci) {
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
            Path worldDir = mc.gameDir.toPath().resolve("saves").resolve(folderName);
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
                () -> PeerCraftUi.setScreen(mc, new GuiWorldSelection(new GuiMainMenu()))));
    }
}
