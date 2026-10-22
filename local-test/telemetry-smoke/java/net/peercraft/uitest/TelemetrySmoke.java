package net.peercraft.uitest;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.peercraft.client.gui.PeerCraftSettingsScreen;
import net.peercraft.config.*;
import net.peercraft.network.telemetry.Telemetry;

/** Isolated real-screen callback test; never contacts an external service or opens a world. */
public final class TelemetrySmoke implements ClientModInitializer {
    private int phase, ticks;
    private void check(boolean condition, String label) {
        if (!condition)
            throw new AssertionError(label);
        System.out.println("TELEMETRY_UI_PASS=" + label);
    }
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.getOverlay() != null)
                return;
            try {
                if (phase == 0 && mc.screen instanceof TitleScreen) {
                    check(!Telemetry.isEnabled(), "disabled_before_notice");
                    System.setProperty("peercraft.mode", "auto");
                    phase = 1;
                    ticks = 0;
                } else if (phase == 1 && mc.screen != null
                    && mc.screen.getTitle().getString().equals(
                        net.minecraft.client.resources.language.I18n.get("peercraft.telemetry.title"))) {
                    if (++ticks < 15)
                        return;
                    check(!Telemetry.isEnabled(), "disabled_while_notice_visible");
                    Screenshot.grab(mc.gameDirectory, "telemetry-notice.png", mc.getMainRenderTarget(),
                        message -> System.out.println("TELEMETRY_SCREENSHOT=" + message.getString()));
                    mc.screen.onClose();
                    check(!Telemetry.isEnabled() && !TelemetryConsent.needsNotice(),
                        "escape_is_persisted_refusal");
                    TelemetryConsent.initialize();
                    check(
                        !TelemetryConsent.needsNotice() && !Telemetry.isEnabled(), "refusal_survives_reload");
                    // Fresh choice in this isolated fixture, to exercise the real Allow handler next.
                    PeerCraftSettingsStore.save(new PeerCraftSettings());
                    TelemetryConsent.initialize();
                    mc.setScreen(new TitleScreen());
                    phase = 2;
                    ticks = 0;
                } else if (phase == 2 && mc.screen != null
                    && mc.screen.getTitle().getString().equals(
                        net.minecraft.client.resources.language.I18n.get("peercraft.telemetry.title"))) {
                    var button = mc.screen.children()
                                     .stream()
                                     .filter(x -> x instanceof Button)
                                     .map(x -> (Button) x)
                                     .filter(b
                                         -> b.getMessage().getString().equals(
                                             net.minecraft.client.resources.language.I18n.get(
                                                 "peercraft.telemetry.allow")))
                                     .findFirst()
                                     .orElseThrow();
                    button.onPress();
                    check(Telemetry.isEnabled(), "allow_handler_enables");
                    mc.setScreen(new PeerCraftSettingsScreen(new TitleScreen()));
                    phase = 3;
                } else if (phase == 3 && mc.screen instanceof PeerCraftSettingsScreen screen) {
                    var field = PeerCraftSettingsScreen.class.getDeclaredField("rows");
                    field.setAccessible(true);
                    for (Object row : (java.util.List<?>) field.get(screen)) {
                        var key = row.getClass().getDeclaredField("key");
                        key.setAccessible(true);
                        if (!key.get(row).equals("telemetry.enabled"))
                            continue;
                        var widget = row.getClass().getDeclaredField("widget");
                        widget.setAccessible(true);
                        Object toggle = widget.get(row);
                        var press = toggle.getClass().getMethod("onPress");
                        press.setAccessible(true);
                        press.invoke(toggle);
                    }
                    check(!Telemetry.isEnabled(), "one_click_off_without_save");
                    check("false".equals(PeerCraftSettingsStore.load().telemetryEnabled),
                        "off_is_persisted_without_save");
                    var reset = PeerCraftSettingsScreen.class.getDeclaredMethod("onReset");
                    reset.setAccessible(true);
                    reset.invoke(screen);
                    TelemetryConsent.initialize();
                    check(!Telemetry.isEnabled() && !TelemetryConsent.needsNotice(), "reset_keeps_refusal");
                    System.out.println("TELEMETRY_UI_COMPLETE");
                    phase = 4;
                    ticks = 0;
                } else if (phase == 4 && ++ticks > 10) {
                    mc.stop();
                }
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.out.println("TELEMETRY_UI_FAILED");
                phase = 4;
            }
        });
    }
}
