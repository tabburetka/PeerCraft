package net.peercraft.uitest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.util.ScreenShotHelper;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.peercraft.client.gui.PeerCraftLang;
import net.peercraft.client.update.UpdateChecker;

/** 1.7.10: first-run telemetry notice (declined), then the title-screen update notice. */
@Mod(modid = "peercraft_legacy_update_probe", name = "PeerCraft legacy update probe", version = "1")
public final class LegacyUpdateProbe {
    private int stage, ticks, noticeTicks;
    private boolean done;
    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(this);
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            GuiScreen s = mc.currentScreen;
            if (stage == 0) {
                if (s != null && s.getClass().getSimpleName().equals("PeerCraftConfirmScreen")) {
                    if (++noticeTicks < 20) return;
                    capture(mc, "legacy-telemetry-notice");
                    press(button(mc, PeerCraftLang.tr("peercraft.telemetry.decline")));
                    System.out.println("LEGACY_TELEMETRY_DECLINED enabled=" + net.peercraft.network.telemetry.Telemetry.isEnabled());
                    if (net.peercraft.network.telemetry.Telemetry.isEnabled()) throw new IllegalStateException("Telemetry enabled after decline");
                    stage = 1;
                } else if (s instanceof GuiMainMenu && ++ticks > 200) {
                    throw new IllegalStateException("Telemetry first-run notice was not shown");
                }
                return;
            }
            if (!(s instanceof GuiMainMenu)) return;
            ticks++;
            boolean available = UpdateChecker.available() != null;
            if (!available && ticks < 300) return;
            if (ticks < 40) return;
            boolean widget = false;
            for (GuiButton b : buttons(mc)) if (b.getClass().getSimpleName().equals("UpdateNoticeButton") && b.visible) widget = true;
            capture(mc, "legacy-title-update");
            UpdateChecker.UpdateInfo info = UpdateChecker.available();
            System.out.println("LEGACY_UPDATE_NOTICE available=" + available + " widget=" + widget
                    + (info == null ? "" : " current=" + info.current + " latest=" + info.latest));
            if (!available || !widget) throw new IllegalStateException("Update notice missing");
            System.out.println("LEGACY_UPDATE_PASSED");
            done = true; mc.shutdown();
        } catch (Throwable failure) {
            failure.printStackTrace(); done = true;
            System.out.println("LEGACY_UPDATE_FAILED " + failure); mc.shutdown();
        }
    }
    @SuppressWarnings("unchecked") private java.util.List<GuiButton> buttons(Minecraft mc) throws Exception {
        java.lang.reflect.Field field = GuiScreen.class.getDeclaredField("buttonList"); field.setAccessible(true);
        return (java.util.List<GuiButton>) field.get(mc.currentScreen);
    }
    private GuiButton button(Minecraft mc, String label) throws Exception {
        for (GuiButton button : buttons(mc)) if (button.displayString.equals(label)) return button;
        throw new IllegalStateException("Missing button " + label);
    }
    private static void press(GuiButton button) throws Exception {
        java.lang.reflect.Field action = button.getClass().getDeclaredField("onPress"); action.setAccessible(true);
        ((Runnable) action.get(button)).run();
    }
    private void capture(Minecraft mc, String name) {
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
    }
}
