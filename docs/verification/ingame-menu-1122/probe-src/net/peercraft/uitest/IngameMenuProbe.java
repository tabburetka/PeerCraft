package net.peercraft.uitest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.peercraft.client.gui.*;
import net.peercraft.network.account.AccountClient;

/** Checks the pause-menu "PC" entry and captures every in-game PeerCraft screen on 1.12.2. */
@Mod(modid = "peercraft_ingame_menu_probe", name = "PeerCraft in-game menu probe", version = "1", clientSideOnly = true)
public final class IngameMenuProbe {
    private int ticks;
    private boolean creating, started, done;
    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        net.peercraft.config.TelemetryConsent.choose(false);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (!creating) {
                if (!(mc.currentScreen instanceof GuiMainMenu)) return;
                creating = true;
                mc.launchIntegratedServer("ingame-menu-" + System.currentTimeMillis(), "Ingame menu",
                        new net.minecraft.world.WorldSettings(123, net.minecraft.world.GameType.CREATIVE,
                                false, false, net.minecraft.world.WorldType.FLAT).enableCommands());
                return;
            }
            if (!started) {
                if (mc.world == null || mc.player == null) return;
                started = true;
            }
            ticks++;
            if (ticks == 20) mc.displayGuiScreen(new GuiIngameMenu());
            if (ticks == 30) { verify(mc); capture(mc, "ingame-pause"); }
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
            if (ticks == 105) {
                setSession(null); done = true;
                System.out.println("INGAME_MENU_1122_PASSED"); mc.shutdown();
            }
        } catch (Throwable failure) {
            failure.printStackTrace(); done = true;
            try { setSession(null); } catch (Exception ignored) { }
            System.out.println("INGAME_MENU_1122_FAILED"); mc.shutdown();
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
    private void verify(Minecraft mc) throws Exception {
        GuiButton pc = button(mc, "PC");
        if (pc.width != 20 || pc.height != 20) throw new IllegalStateException("PC button is not compact");
        for (GuiButton b : buttons(mc)) {
            if (b == pc) continue;
            if (pc.x < b.x + b.width && pc.x + pc.width > b.x && pc.y < b.y + b.height && pc.y + pc.height > b.y)
                throw new IllegalStateException("PC button overlaps " + b.displayString);
        }
        if (pc.x < 0 || pc.y < 0 || pc.x + 20 > mc.currentScreen.width || pc.y + 20 > mc.currentScreen.height)
            throw new IllegalStateException("PC offscreen");
        System.out.println("INGAME_MENU_PAUSE_ENTRY_PASSED " + pc.x + "," + pc.y);
    }
    private static void setSession(AccountClient.AccountSession session) throws Exception {
        java.lang.reflect.Field field = AccountClient.class.getDeclaredField("currentSession");
        field.setAccessible(true);
        field.set(AccountClient.INSTANCE, session);
    }
    private void capture(Minecraft mc, String name) {
        ScreenShotHelper.saveScreenshot(mc.gameDir, name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
    }
}
