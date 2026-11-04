package net.peercraft.client.gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.TranslatableComponent;
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
        }, new TranslatableComponent("peercraft.telemetry.title"), new TranslatableComponent("peercraft.telemetry.body"), new TranslatableComponent("peercraft.telemetry.allow"), new TranslatableComponent("peercraft.telemetry.decline")));
        return true;
    }
}
