package net.peercraft.uitest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.peercraft.client.gui.PeerCraftInGameFriendsScreen;
import net.peercraft.client.gui.PeerCraftInGameMenuScreen;
import net.peercraft.client.gui.PeerCraftServerPlayersScreen;
import net.peercraft.network.account.AccountClient;

/** Creates a flat world, checks the pause "PC" entry and captures every in-game PeerCraft screen. */
public final class IngameMenuPreview implements ClientModInitializer {
    private boolean creating;
    private int worldTicks;
    private PauseScreen pause;

    public void onInitializeClient() {
        net.peercraft.config.TelemetryConsent.choose(false);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                if (!creating) {
                    if (!(mc.gui.screen() instanceof TitleScreen) || mc.gui.overlay() != null) return;
                    creating = true;
                    var settings = new net.minecraft.world.level.LevelSettings("PeerCraft — меню в игре", net.minecraft.world.level.GameType.CREATIVE,
                            new net.minecraft.world.level.LevelSettings.DifficultySettings(net.minecraft.world.Difficulty.PEACEFUL, false, false),
                            true, net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel("ingame-menu-" + System.currentTimeMillis(), settings,
                            new net.minecraft.world.level.levelgen.WorldOptions(123, false, false),
                            registries -> registries.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                                    .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
                    return;
                }
                if (mc.level == null || mc.player == null || !mc.getConnection().hasClientLoaded()) return;
                worldTicks++;
                if (worldTicks == 20) {
                    pause = new PauseScreen(true);
                    mc.gui.setScreen(pause);
                }
                if (worldTicks == 30) {
                    Button pc = find(pause, "PC");
                    for (var child : pause.children()) {
                        if (child == pc || !(child instanceof AbstractWidget w)) continue;
                        if (pc.getX() < w.getX() + w.getWidth() && pc.getX() + pc.getWidth() > w.getX()
                                && pc.getY() < w.getY() + w.getHeight() && pc.getY() + pc.getHeight() > w.getY())
                            throw new IllegalStateException("PC button overlaps " + w.getMessage().getString());
                    }
                    if (pc.getWidth() != 20 || pc.getHeight() != 20) throw new IllegalStateException("PC button is not compact");
                    System.out.println("INGAME_MENU_PAUSE_ENTRY_PASSED " + pc.getX() + "," + pc.getY());
                    capture(mc, "pause");
                }
                if (worldTicks == 40) {
                    find(pause, "PC").onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                    if (!(mc.gui.screen() instanceof PeerCraftInGameMenuScreen)) throw new IllegalStateException("PC did not open the hub");
                }
                if (worldTicks == 50) capture(mc, "hub-signed-out");
                if (worldTicks == 55) {
                    setSession(new AccountClient.AccountSession(java.util.UUID.randomUUID(), new byte[16], new byte[16], false, "ABC123", "Developer"));
                    mc.gui.setScreen(new PeerCraftInGameMenuScreen(pause));
                }
                if (worldTicks == 65) capture(mc, "hub");
                if (worldTicks == 70) {
                    find(mc.gui.screen(), net.minecraft.network.chat.Component.translatable("peercraft.ingame.players").getString())
                            .onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                    if (!(mc.gui.screen() instanceof PeerCraftServerPlayersScreen)) throw new IllegalStateException("Players entry broken");
                }
                if (worldTicks == 80) capture(mc, "players");
                if (worldTicks == 85) {
                    mc.gui.screen().onClose();
                    if (!(mc.gui.screen() instanceof PeerCraftInGameMenuScreen)) throw new IllegalStateException("Back did not return to hub");
                    find(mc.gui.screen(), net.minecraft.network.chat.Component.translatable("peercraft.ingame.friends").getString())
                            .onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                    if (!(mc.gui.screen() instanceof PeerCraftInGameFriendsScreen)) throw new IllegalStateException("Friends entry broken");
                }
                if (worldTicks == 95) capture(mc, "friends");
                if (worldTicks == 100) {
                    setSession(null);
                    System.out.println("INGAME_MENU_PASSED");
                }
                if (worldTicks == 110) mc.stop();
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.out.println("INGAME_MENU_FAILED");
                worldTicks = 105;
            }
        });
    }

    private static Button find(net.minecraft.client.gui.screens.Screen screen, String label) {
        return screen.children().stream()
                .filter(c -> c instanceof Button b && b.getMessage().getString().equals(label))
                .map(c -> (Button) c).findFirst().orElseThrow(() -> new IllegalStateException("No button " + label));
    }

    private static void setSession(AccountClient.AccountSession session) throws Exception {
        var field = AccountClient.class.getDeclaredField("currentSession");
        field.setAccessible(true);
        field.set(AccountClient.INSTANCE, session);
    }

    private static void capture(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "ingame-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                message -> System.out.println("INGAME_MENU_CAPTURED " + name));
    }
}
