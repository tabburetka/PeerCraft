package net.peercraft.uitest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.client.gui.PeerCraftSessionScreen;

public final class SessionUiPreview implements ClientModInitializer {
    private int ticks;
    private boolean started, done;
    private final SessionHostFixture host = new SessionHostFixture();
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (done) return;
            try {
                if (!started) {
                    if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
                    host.enable(); started = true;
                    mc.setScreen(new PauseScreen(true));
                }
                ticks++;
                if (ticks == 20 || ticks == 80) {
                    verify(mc);
                    capture(mc, ticks == 20 ? "pause" : "pause-small");
                }
                if (ticks == 30 || ticks == 90) sessionButton(mc).onPress();
                if (ticks == 40 || ticks == 100) {
                    if (!(mc.screen instanceof PeerCraftSessionScreen)) throw new IllegalStateException("Session navigation failed");
                    capture(mc, ticks == 40 ? "session" : "session-small");
                }
                if (ticks == 60) {
                    org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 640, 480);
                    mc.setScreen(new PauseScreen(true));
                }
                if (ticks == 110) mc.screen.mouseScrolled(mc.screen.width / 2, mc.screen.height / 2, -10);
                if (ticks == 120) capture(mc, "session-scrolled");
                if (ticks == 130) {
                    host.restore(); done = true;
                    System.out.println("SESSION_UI_1165_PASSED"); mc.stop();
                }
            } catch (Throwable failure) {
                failure.printStackTrace(); done = true;
                try { host.restore(); } catch (Exception ignored) { }
                System.out.println("SESSION_UI_1165_FAILED"); mc.stop();
            }
        });
    }
    private Button sessionButton(Minecraft mc) {
        for (net.minecraft.client.gui.components.events.GuiEventListener child : mc.screen.children())
            if (child instanceof Button && ((Button)child).getMessage().getString().equals(new TranslatableComponent("peercraft.session.title").getString())) return (Button)child;
        throw new IllegalStateException("Missing session button");
    }
    private void verify(Minecraft mc) {
        Button session = sessionButton(mc), handoff = null;
        for (net.minecraft.client.gui.components.events.GuiEventListener child : mc.screen.children()) {
            if (!(child instanceof Button)) continue;
            Button button = (Button)child;
            if (button.y < 0 || button.y + button.getHeight() > mc.screen.height) throw new IllegalStateException("Offscreen button");
            if (button.getMessage().getString().equals(new TranslatableComponent("peercraft.handoff.menu.button").getString())) handoff = button;
        }
        if (handoff == null || handoff.y != session.y || session.x + session.getWidth() > handoff.x)
            throw new IllegalStateException("Handoff missing or overlapping");
    }
    private void capture(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.getMainRenderTarget(), message -> { });
    }
}
