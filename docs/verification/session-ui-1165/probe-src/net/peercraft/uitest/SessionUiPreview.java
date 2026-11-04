package net.peercraft.uitest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.client.gui.PeerCraftInGameFriendsScreen;
import net.peercraft.client.gui.PeerCraftInGameMenuScreen;
import net.peercraft.client.gui.PeerCraftServerPlayersScreen;
import net.peercraft.network.account.AccountClient;

/** Checks the pause-menu "PC" entry and captures every in-game PeerCraft screen on 1.16.5. */
public final class SessionUiPreview implements ClientModInitializer {
    private int ticks;
    private boolean started, done;
    private final SessionHostFixture host = new SessionHostFixture();
    public void onInitializeClient() {
        net.peercraft.config.TelemetryConsent.choose(false);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (done) return;
            try {
                if (!started) {
                    if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
                    host.enable(); started = true;
                    mc.setScreen(new PauseScreen(true));
                }
                ticks++;
                if (ticks == 20 || ticks == 140) { verify(mc); capture(mc, ticks == 20 ? "ingame-pause" : "ingame-pause-small"); }
                if (ticks == 30) {
                    button(mc, "PC").onPress();
                    if (!(mc.screen instanceof PeerCraftInGameMenuScreen)) throw new IllegalStateException("PC did not open the hub");
                }
                if (ticks == 40) capture(mc, "ingame-hub-signed-out");
                if (ticks == 45) {
                    setSession(new AccountClient.AccountSession(java.util.UUID.randomUUID(), new byte[16], new byte[16], false, "ABC123", "Developer"));
                    mc.setScreen(new PeerCraftInGameMenuScreen(new PauseScreen(true)));
                }
                if (ticks == 55) capture(mc, "ingame-hub");
                if (ticks == 60) {
                    button(mc, new TranslatableComponent("peercraft.ingame.players").getString()).onPress();
                    if (!(mc.screen instanceof PeerCraftServerPlayersScreen)) throw new IllegalStateException("Players entry broken");
                }
                if (ticks == 70) capture(mc, "ingame-players");
                if (ticks == 75) {
                    mc.screen.onClose();
                    if (!(mc.screen instanceof PeerCraftInGameMenuScreen)) throw new IllegalStateException("Back did not return to hub");
                    button(mc, new TranslatableComponent("peercraft.ingame.friends").getString()).onPress();
                    if (!(mc.screen instanceof PeerCraftInGameFriendsScreen)) throw new IllegalStateException("Friends entry broken");
                }
                if (ticks == 85) capture(mc, "ingame-friends");
                if (ticks == 100) {
                    setSession(null);
                    org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 640, 480);
                    mc.setScreen(new PauseScreen(true));
                }
                if (ticks == 150) {
                    host.restore(); done = true;
                    System.out.println("SESSION_UI_1165_PASSED"); mc.stop();
                }
            } catch (Throwable failure) {
                failure.printStackTrace(); done = true;
                try { host.restore(); setSession(null); } catch (Exception ignored) { }
                System.out.println("SESSION_UI_1165_FAILED"); mc.stop();
            }
        });
    }
    private static Button button(Minecraft mc, String label) {
        for (GuiEventListener child : mc.screen.children())
            if (child instanceof Button && ((Button) child).getMessage().getString().equals(label)) return (Button) child;
        throw new IllegalStateException("Missing button " + label);
    }
    private static void verify(Minecraft mc) {
        Button pc = button(mc, "PC");
        if (pc.getWidth() != 20 || pc.getHeight() != 20) throw new IllegalStateException("PC button is not compact");
        for (GuiEventListener child : mc.screen.children()) {
            if (child == pc || !(child instanceof AbstractWidget)) continue;
            AbstractWidget w = (AbstractWidget) child;
            if (pc.x < w.x + w.getWidth() && pc.x + pc.getWidth() > w.x && pc.y < w.y + w.getHeight() && pc.y + pc.getHeight() > w.y)
                throw new IllegalStateException("PC button overlaps " + w.getMessage().getString());
        }
        if (pc.x < 0 || pc.y < 0 || pc.x + 20 > mc.screen.width || pc.y + 20 > mc.screen.height) throw new IllegalStateException("PC offscreen");
        System.out.println("INGAME_MENU_PAUSE_ENTRY_PASSED " + pc.x + "," + pc.y);
    }
    private static void setSession(AccountClient.AccountSession session) throws Exception {
        java.lang.reflect.Field field = AccountClient.class.getDeclaredField("currentSession");
        field.setAccessible(true);
        field.set(AccountClient.INSTANCE, session);
    }
    private void capture(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.getMainRenderTarget(), message -> { });
    }
}
