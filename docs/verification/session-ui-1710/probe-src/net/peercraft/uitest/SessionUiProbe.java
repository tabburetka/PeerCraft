package net.peercraft.uitest;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.peercraft.client.gui.*;
import net.peercraft.network.account.AccountClient;
import net.minecraft.util.ScreenShotHelper;

/** Checks the pause-menu "PC" entry and captures every in-game PeerCraft screen on 1.7.10. */
@Mod(modid="peercraft_session_ui_probe", name="PeerCraft session verification", version="1")
public final class SessionUiProbe {
    private int ticks;
    private boolean creating, started, done;
    private final SessionHostFixture host = new SessionHostFixture();
    @Mod.EventHandler public void init(FMLInitializationEvent event) { net.peercraft.config.TelemetryConsent.choose(false); cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(this); }
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
            }
            ticks++;
            if (ticks == 20) { host.enable(); mc.displayGuiScreen(new GuiIngameMenu()); }
            if (ticks == 30 || ticks == 140) { verify(mc); capture(mc, ticks == 30 ? "ingame-pause" : "ingame-pause-small"); }
            if (ticks == 40) {
                press(button(mc, "PC"));
                if (!(mc.currentScreen instanceof PeerCraftInGameMenuScreen)) throw new IllegalStateException("PC did not open the hub");
            }
            if (ticks == 50) capture(mc, "ingame-hub-signed-out");
            if (ticks == 55) {
                setSession(new AccountClient.AccountSession(java.util.UUID.randomUUID(), new byte[16], new byte[16], false, "ABC123", "Developer"));
                mc.displayGuiScreen(new PeerCraftInGameMenuScreen(new GuiIngameMenu()));
            }
            if (ticks == 65) capture(mc, "ingame-hub");
            if (ticks == 70) {
                press(button(mc, PeerCraftLang.tr("peercraft.ingame.players")));
                if (!(mc.currentScreen instanceof PeerCraftServerPlayersScreen)) throw new IllegalStateException("Players entry broken");
            }
            if (ticks == 80) capture(mc, "ingame-players");
            if (ticks == 85) {
                press(button(mc, PeerCraftLang.tr("peercraft.gui.common.back")));
                if (!(mc.currentScreen instanceof PeerCraftInGameMenuScreen)) throw new IllegalStateException("Back did not return to hub");
                press(button(mc, PeerCraftLang.tr("peercraft.ingame.friends")));
                if (!(mc.currentScreen instanceof PeerCraftInGameFriendsScreen)) throw new IllegalStateException("Friends entry broken");
            }
            if (ticks == 95) capture(mc, "ingame-friends");
            if (ticks == 110) {
                setSession(null);
                org.lwjgl.opengl.Display.setDisplayMode(new org.lwjgl.opengl.DisplayMode(640, 480));
                mc.resize(640, 480);
                mc.displayGuiScreen(new GuiIngameMenu());
            }
            if (ticks == 150) {
                host.restore(); done = true;
                System.out.println("SESSION_UI_1710_PASSED"); mc.shutdown();
            }
        } catch (Throwable failure) {
            failure.printStackTrace(); done = true;
            try { host.restore(); setSession(null); } catch (Exception ignored) { }
            System.out.println("SESSION_UI_1710_FAILED"); mc.shutdown();
        }
    }
    @SuppressWarnings("unchecked") private java.util.List<GuiButton> buttons(Minecraft mc) throws Exception {
        java.lang.reflect.Field field = GuiScreen.class.getDeclaredField("buttonList"); field.setAccessible(true);
        return (java.util.List<GuiButton>)field.get(mc.currentScreen);
    }
    private GuiButton button(Minecraft mc, String label) throws Exception {
        for (GuiButton button : buttons(mc)) if (button.displayString.equals(label)) return button;
        throw new IllegalStateException("Missing button " + label);
    }
    private static void press(GuiButton button) throws Exception {
        java.lang.reflect.Field action = button.getClass().getDeclaredField("onPress"); action.setAccessible(true);
        ((Runnable) action.get(button)).run();
    }
    private void verify(Minecraft mc) throws Exception {
        GuiButton pc = button(mc, "PC");
        if (pc.width != 20 || pc.height != 20) throw new IllegalStateException("PC button is not compact");
        for (GuiButton b : buttons(mc)) {
            if (b == pc) continue;
            if (pc.xPosition < b.xPosition + b.width && pc.xPosition + pc.width > b.xPosition
                    && pc.yPosition < b.yPosition + b.height && pc.yPosition + pc.height > b.yPosition)
                throw new IllegalStateException("PC button overlaps " + b.displayString);
        }
        if (pc.xPosition < 0 || pc.yPosition < 0 || pc.xPosition + 20 > mc.currentScreen.width || pc.yPosition + 20 > mc.currentScreen.height)
            throw new IllegalStateException("PC offscreen");
        System.out.println("INGAME_MENU_PAUSE_ENTRY_PASSED " + pc.xPosition + "," + pc.yPosition);
    }
    private static void setSession(AccountClient.AccountSession session) throws Exception {
        java.lang.reflect.Field field = AccountClient.class.getDeclaredField("currentSession");
        field.setAccessible(true);
        field.set(AccountClient.INSTANCE, session);
    }
    private void capture(Minecraft mc, String name) {
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
    }
}
