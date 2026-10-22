package net.peercraft.client.gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.peercraft.config.TelemetryConsent;
import net.peercraft.config.PeerCraftConfig;

/** One neutral first-run choice. Escape is a refusal; never gate gameplay on consent. */
final class TelemetryNoticeController {
    private TelemetryNoticeController() {}
    static boolean maybeShow(Screen current) {
        if (!(current instanceof TitleScreen) || !TelemetryConsent.needsNotice()
                || PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())) return false;
        Minecraft mc = Minecraft.getInstance();
        PeerCraftUi.setScreen(mc, new PeerCraftConfirmScreen(allowed -> {
            TelemetryConsent.choose(allowed);
            PeerCraftUi.setScreen(mc, current);
        }, Component.translatable("peercraft.telemetry.title"), Component.translatable("peercraft.telemetry.body"), Component.translatable("peercraft.telemetry.allow"), Component.translatable("peercraft.telemetry.decline")));
        return true;
    }
}
