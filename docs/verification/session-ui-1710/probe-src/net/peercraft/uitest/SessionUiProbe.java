package net.peercraft.uitest;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.peercraft.client.gui.*;
import net.minecraft.util.ScreenShotHelper;

@Mod(modid="peercraft_session_ui_probe", name="PeerCraft session verification", version="1")
public final class SessionUiProbe {
    private int ticks;
    private boolean creating, started, done;
    private final SessionHostFixture host = new SessionHostFixture();
    @Mod.EventHandler public void init(FMLInitializationEvent event) { cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(this); }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (!creating) {
                if (!(mc.currentScreen instanceof GuiMainMenu)) return;
                creating = true;
                mc.launchIntegratedServer("session-ui-" + System.currentTimeMillis(), "Session UI",
                        new net.minecraft.world.WorldSettings(123, net.minecraft.world.WorldSettings.GameType.CREATIVE,
                                false, false, net.minecraft.world.WorldType.FLAT).enableCommands());
                return;
            }
            if (!started) {
                if (mc.theWorld == null || mc.thePlayer == null) return;
                started = true;
                net.minecraft.scoreboard.Scoreboard board = mc.theWorld.getScoreboard();
                board.func_96530_a(0, board.addScoreObjective("session-ui", net.minecraft.scoreboard.IScoreObjectiveCriteria.field_96641_b));
            }
            ticks++;
            if (ticks <= 30) net.minecraft.client.settings.KeyBinding.setKeyBindState(mc.gameSettings.keyBindPlayerList.getKeyCode(), true);
            if (ticks == 20) capture(mc, "tab");
            if (ticks == 31) {
                net.minecraft.client.settings.KeyBinding.setKeyBindState(mc.gameSettings.keyBindPlayerList.getKeyCode(), false);
                host.enable(); mc.displayGuiScreen(new GuiIngameMenu());
            }
            if (ticks == 50 || ticks == 110) { verify(mc); capture(mc, ticks == 50 ? "pause" : "pause-small"); }
            if (ticks == 60 || ticks == 120) {
                GuiButton button = sessionButton(mc);
                java.lang.reflect.Field action = button.getClass().getDeclaredField("onPress"); action.setAccessible(true);
                ((Runnable)action.get(button)).run();
            }
            if (ticks == 70 || ticks == 130) {
                if (!(mc.currentScreen instanceof PeerCraftSessionScreen)) throw new IllegalStateException("Session navigation failed");
                capture(mc, ticks == 70 ? "session" : "session-small");
            }
            if (ticks == 90) {
                org.lwjgl.opengl.Display.setDisplayMode(new org.lwjgl.opengl.DisplayMode(640, 480));
                mc.resize(640, 480);
                mc.displayGuiScreen(new GuiIngameMenu());
            }
            if (ticks == 145) {
                host.restore(); done = true;
                System.out.println("SESSION_UI_1710_PASSED"); mc.shutdown();
            }
        } catch (Throwable failure) {
            failure.printStackTrace(); done = true;
            try { host.restore(); } catch (Exception ignored) { }
            System.out.println("SESSION_UI_1710_FAILED"); mc.shutdown();
        }
    }
    @SuppressWarnings("unchecked") private java.util.List<GuiButton> buttons(Minecraft mc) throws Exception {
        java.lang.reflect.Field field = GuiScreen.class.getDeclaredField("buttonList"); field.setAccessible(true);
        return (java.util.List<GuiButton>)field.get(mc.currentScreen);
    }
    private GuiButton sessionButton(Minecraft mc) throws Exception {
        for (GuiButton button : buttons(mc)) if (button.displayString.equals(PeerCraftLang.tr("peercraft.session.title"))) return button;
        throw new IllegalStateException("Missing session button");
    }
    private void verify(Minecraft mc) throws Exception {
        GuiButton session = sessionButton(mc), handoff = null;
        for (GuiButton button : buttons(mc)) {
            if (button.yPosition < 0 || button.yPosition + button.height > mc.currentScreen.height) throw new IllegalStateException("Offscreen button");
            if (button.displayString.equals(PeerCraftLang.tr("peercraft.handoff.menu.button"))) handoff = button;
        }
        if (handoff == null || handoff.yPosition != session.yPosition || session.xPosition + session.width > handoff.xPosition)
            throw new IllegalStateException("Handoff missing or overlapping");
    }
    private void capture(Minecraft mc, String name) {
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
    }
}
