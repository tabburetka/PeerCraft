package net.peercraft.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
//? if >=1.12 {
import net.minecraft.client.gui.GuiWorldSelection;
//?} else {
//~ import net.minecraft.client.gui.GuiSelectWorld;
//?}
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
 //? if >=1.12 {
 * Forge 1.12.2 backport of {@code src/main/.../client/mixin/WorldOpenFlowsMixin.java}. 1.12.2
 * predates the {@code WorldOpenFlows} abstraction (added 1.20.2) and has no per-screen GUI
 * mixins (see {@code PeerCraftScreenEvents}'s doc for why) — but the actual "load this
 * singleplayer world" entry point, {@code Minecraft.launchIntegratedServer(String, String,
 * WorldSettings)}, is NOT a {@code GuiScreen}, so it's mixined directly here just like the
 * 1.16.5 twin mixins {@code Minecraft.loadLevel}. A {@code null} {@code WorldSettings} arg means
 * "load the existing world" (vanilla's own {@code GuiWorldSelection}); a non-null one is world
 * creation, never stale — {@code meta == null} for a brand-new folder makes that case a no-op
 * below regardless.
 //?} else {
//~  * Forge 1.7.10 backport of {@code src/main/.../client/mixin/WorldOpenFlowsMixin.java} — mirrors
//~  * the 1.12.2 twin exactly (same mixin target, {@code Minecraft.launchIntegratedServer(String,
//~  * String, WorldSettings)}, readable-named on 1.7.10 too), except the world-list screen is
//~  * {@code GuiSelectWorld} (single-arg constructor) rather than {@code GuiWorldSelection}, and the
//~  * saves root is {@code Minecraft.mcDataDir} rather than {@code gameDir}.
 //?}
 */
@Mixin(Minecraft.class)
public abstract class WorldOpenFlowsMixin {

    @Unique
    private static final ThreadLocal<Boolean> peercraft$bypass = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Unique
    private void peercraft$showRecoveryFailure(Minecraft client, String world, Runnable open) {
        net.peercraft.client.gui.HandoffStatusScreen error = new net.peercraft.client.gui.HandoffStatusScreen(new GuiMainMenu(), "", true);
        error.onRecoveryFailed(() -> {
            Path selected = net.peercraft.client.handoff.SuccessorLauncher.savesDirectory().resolve(world);
            try {
                // As with the stale-world warning, do not reuse an old hosting grant.
                net.peercraft.client.handoff.SafeHandoffPlatform.INSTANCE.forkStaleWorld(selected);
            } catch (java.io.IOException failure) {
                org.apache.logging.log4j.LogManager.getLogger("peercraft").warn("[Handoff] Could not clear the local hosting grant", failure);
                peercraft$showRecoveryFailure(client, world, open);
                return;
            }
            net.peercraft.network.handoff.RecoveryOpenOverride.open(selected, open);
        });
        net.peercraft.network.handoff.WorldInstallRecovery.start(net.peercraft.client.handoff.SuccessorLauncher.savesDirectory())
                //? if >=1.12 {
                .whenComplete((ok, failure) -> client.addScheduledTask(() -> PeerCraftUi.setScreen(client, error)));
                //?} else {
//~                 .whenComplete((ok, failure) -> client.func_152344_a(() -> PeerCraftUi.setScreen(client, error)));
                //?}
    }

    @Inject(method = "launchIntegratedServer", at = @At("HEAD"), cancellable = true)
    private void peercraft$warnStaleHandoff(String folderName, String worldName, WorldSettings worldSettingsIn, CallbackInfo ci) {
        if (net.peercraft.network.handoff.RecoveryOpenOverride.allows(
                net.peercraft.client.handoff.SuccessorLauncher.savesDirectory().resolve(folderName))) { peercraft$bypass.set(Boolean.FALSE); return; }
        Minecraft peercraft$client = (Minecraft) (Object) this;
        if (net.peercraft.client.handoff.HandoffNetworkRecovery.deferOpen(
                //? if >=1.12 {
                net.peercraft.client.handoff.SuccessorLauncher.savesDirectory().resolve(folderName), peercraft$client::addScheduledTask,
                //?} else {
//~                 net.peercraft.client.handoff.SuccessorLauncher.savesDirectory().resolve(folderName), peercraft$client::func_152344_a,
                //?}
                () -> peercraft$client.launchIntegratedServer(folderName, worldName, worldSettingsIn), failure -> {
                    peercraft$showRecoveryFailure(peercraft$client, folderName, () -> peercraft$client.launchIntegratedServer(folderName, worldName, worldSettingsIn));
                })) { ci.cancel(); return; }
        if (net.peercraft.network.handoff.WorldInstallRecovery.deferOpen(
                //? if >=1.12 {
                net.peercraft.client.handoff.SuccessorLauncher.savesDirectory(), peercraft$client::addScheduledTask,
                //?} else {
//~                 net.peercraft.client.handoff.SuccessorLauncher.savesDirectory(), peercraft$client::func_152344_a,
                //?}
                () -> peercraft$client.launchIntegratedServer(folderName, worldName, worldSettingsIn), failure -> {
                    peercraft$showRecoveryFailure(peercraft$client, folderName, () -> peercraft$client.launchIntegratedServer(folderName, worldName, worldSettingsIn));
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
            //? if >=1.12 {
            Path worldDir = mc.gameDir.toPath().resolve("saves").resolve(folderName);
            //?} else {
//~             Path worldDir = mc.mcDataDir.toPath().resolve("saves").resolve(folderName);
            //?}
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
                            net.peercraft.client.handoff.SuccessorLauncher.savesDirectory().resolve(folderName)); }
                    catch (java.io.IOException failure) { net.peercraft.client.handoff.SafeHandoffPlatform.INSTANCE.error("peercraft.handoff.abort.recovery_failed"); return; }
                    peercraft$bypass.set(Boolean.TRUE);
                    mc.launchIntegratedServer(folderName, worldName, worldSettingsIn);
                },
                //? if >=1.12 {
                () -> PeerCraftUi.setScreen(mc, new GuiWorldSelection(new GuiMainMenu()))));
                //?} else {
//~                 () -> PeerCraftUi.setScreen(mc, new GuiSelectWorld(new GuiMainMenu()))));
                //?}
    }
    @Inject(method = "launchIntegratedServer", at = @At("RETURN"))
    private void peercraft$bindCreatedServer(String folderName, String worldName, WorldSettings worldSettingsIn, CallbackInfo ci) {
        net.minecraft.server.MinecraftServer server = ((Minecraft) (Object) this).getIntegratedServer();
        if (server != null && !net.peercraft.network.handoff.HandoffLaunchContext.bind(new Object(), server,
                net.peercraft.client.handoff.WorldArchiver.worldDir(server))) server.initiateShutdown();
    }
}
