package net.peercraft.client.gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiMainMenu;
import net.peercraft.config.TelemetryConsent;
import net.peercraft.config.PeerCraftConfig;

/** One neutral first-run choice. Escape is a refusal; never gate gameplay on consent. */
final class TelemetryNoticeController {
    private TelemetryNoticeController() {}
    static boolean maybeShow(GuiScreen current) {
        if (!(current instanceof GuiMainMenu) || !TelemetryConsent.needsNotice()
                || PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())) return false;
        Minecraft mc = Minecraft.getMinecraft();
        PeerCraftUi.setScreen(mc, new PeerCraftConfirmScreen(allowed -> {
            TelemetryConsent.choose(allowed);
            PeerCraftUi.setScreen(mc, current);
        }, PeerCraftLang.tr("peercraft.telemetry.title"), PeerCraftLang.tr("peercraft.telemetry.body"), PeerCraftLang.tr("peercraft.telemetry.allow"), PeerCraftLang.tr("peercraft.telemetry.decline")));
        return true;
    }
}
