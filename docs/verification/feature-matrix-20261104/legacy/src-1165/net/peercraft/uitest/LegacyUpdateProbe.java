package net.peercraft.uitest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.client.update.UpdateChecker;

/** 1.16.5: first-run telemetry notice (declined), then the title-screen update notice. */
public final class LegacyUpdateProbe implements ClientModInitializer {
    private int stage, ticks, noticeTicks;
    private boolean done;
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));
    }
    private void tick(Minecraft mc) {
        if (done) return;
        try {
            Screen s = mc.screen;
            if (mc.getOverlay() != null) return;
            if (stage == 0) {
                if (s != null && s.getClass().getSimpleName().equals("PeerCraftConfirmScreen")) {
                    if (++noticeTicks < 20) return;
                    capture(mc, "legacy-telemetry-notice");
                    button(s, new TranslatableComponent("peercraft.telemetry.decline").getString()).onPress();
                    System.out.println("LEGACY_TELEMETRY_DECLINED enabled=" + net.peercraft.network.telemetry.Telemetry.isEnabled());
                    if (net.peercraft.network.telemetry.Telemetry.isEnabled()) throw new IllegalStateException("Telemetry enabled after decline");
                    stage = 1;
                } else if (s instanceof TitleScreen && ++ticks > 200) {
                    throw new IllegalStateException("Telemetry first-run notice was not shown");
                }
                return;
            }
            if (!(s instanceof TitleScreen)) return;
            ticks++;
            boolean available = UpdateChecker.available() != null;
            if (!available && ticks < 300) return;
            if (ticks < 40) return;
            boolean widget = false;
            for (GuiEventListener c : s.children())
                if (c.getClass().getSimpleName().equals("UpdateNoticeWidget") && ((Button) c).visible) widget = true;
            capture(mc, "legacy-title-update");
            UpdateChecker.UpdateInfo info = UpdateChecker.available();
            System.out.println("LEGACY_UPDATE_NOTICE available=" + available + " widget=" + widget
                    + (info == null ? "" : " current=" + info.current + " latest=" + info.latest));
            if (!available || !widget) throw new IllegalStateException("Update notice missing");
            System.out.println("LEGACY_UPDATE_PASSED");
            done = true; mc.stop();
        } catch (Throwable failure) {
            failure.printStackTrace(); done = true;
            System.out.println("LEGACY_UPDATE_FAILED " + failure); mc.stop();
        }
    }
    private static Button button(Screen s, String label) {
        for (GuiEventListener c : s.children())
            if (c instanceof Button && ((Button) c).getMessage().getString().equals(label)) return (Button) c;
        throw new IllegalStateException("Missing button " + label);
    }
    private void capture(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.getMainRenderTarget(), message -> { });
    }
}
