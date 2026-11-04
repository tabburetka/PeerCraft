package net.peercraft.uitest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.peercraft.client.gui.PeerCraftSessionScreen;

/** Opens production screens in a separate game directory and leaves it open for review. */
public final class SessionUiPreview implements ClientModInitializer {
    private int ticks;
    private boolean opened;
    private PeerCraftSessionScreen screen;
    private boolean creatingWorld;
    private int worldTicks;
    private Object originalRendezvous;
    private boolean originalHost;
    private final String[] pages = {"OVERVIEW", "NETWORK", "WORLD", "FILES", "PLAYERS", "HOSTS"};
    public void onInitializeClient() {
        net.peercraft.config.TelemetryConsent.choose(false);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                if (!opened) {
                    if (!(mc.gui.screen() instanceof TitleScreen) || mc.gui.overlay() != null) return;
                    screen = new PeerCraftSessionScreen(mc.gui.screen());
                    mc.gui.setScreen(screen);
                    opened = true;
                    System.out.println("SESSION_UI_PREVIEW_OPENED");
                    return;
                }
                if (creatingWorld) {
                    if (mc.level == null || mc.player == null || !mc.getConnection().hasClientLoaded()) return;
                    worldTicks++;
                    if (worldTicks == 10) {
                        mc.gui.setScreen(null);
                        var board = mc.level.getScoreboard();
                        var objective = board.addObjective("session-ui", net.minecraft.world.scores.criteria.ObjectiveCriteria.DUMMY,
                                net.minecraft.network.chat.Component.literal("UI"), net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER,
                                false, null);
                        board.setDisplayObjective(net.minecraft.world.scores.DisplaySlot.LIST, objective);
                    }
                    if (worldTicks >= 10 && worldTicks <= 60) mc.options.keyPlayerList.setDown(true);
                    if (worldTicks == 50) capture(mc, "tab");
                    if (worldTicks == 61) {
                        mc.options.keyPlayerList.setDown(false);
                        setHostFixture(true);
                        mc.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
                    }
                    if (worldTicks == 80) capture(mc, "pause");
                    if (worldTicks == 90) {
                        var pause = mc.gui.screen();
                        var button = pause.children().stream()
                                .filter(c -> c instanceof net.minecraft.client.gui.components.Button b
                                        && b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("peercraft.handoff.menu.button").getString()))
                                .map(c -> (net.minecraft.client.gui.components.Button) c).findFirst().orElseThrow();
                        if (button.getY() + button.getHeight() > pause.height) throw new IllegalStateException("Session button outside pause screen");
                        long extra = pause.children().stream()
                                .filter(c -> c instanceof net.minecraft.client.gui.components.Button b
                                        && b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("peercraft.handoff.menu.button").getString())).count();
                        if (extra != 1) throw new IllegalStateException("Expected exactly one handoff entry");
                        if (button.getWidth() != 204) throw new IllegalStateException("Oversized session control");
                        var vanilla = pause.children().stream()
                                .filter(c -> c instanceof net.minecraft.client.gui.components.Button b
                                        && b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("menu.multiplayerOptions.button").getString()))
                                .map(c -> (net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow();
                        vanilla.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                        if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.MultiplayerOptionsScreen))
                            throw new IllegalStateException("Native access control stopped working");
                        mc.gui.setScreen(pause);
                        System.out.println("SESSION_UI_VANILLA_ACCESS_PRESERVED_PASSED");
                        System.out.println("SESSION_UI_COMPACT_HOST_MENU_PASSED");
                        button.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                        if (!(mc.gui.screen() instanceof net.peercraft.client.gui.HandoffPlayerPickerScreen)) throw new IllegalStateException("Pause entry did not open handoff screen");
                        var handoffScreen = mc.gui.screen();
                        var monitoring = handoffScreen.children().stream()
                                .filter(c -> c instanceof net.minecraft.client.gui.components.Button b
                                        && b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("peercraft.session.monitor_button").getString()))
                                .map(c -> (net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow();
                        capture(mc, "handoff");
                        monitoring.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                        if (!(mc.gui.screen() instanceof PeerCraftSessionScreen)) throw new IllegalStateException("Monitoring did not open from handoff");
                        mc.gui.screen().onClose();
                        if (mc.gui.screen() != handoffScreen) throw new IllegalStateException("Back did not return to handoff");
                        monitoring.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                        System.out.println("SESSION_UI_HANDOFF_MONITOR_NAVIGATION_PASSED");
                        System.out.println("SESSION_UI_PAUSE_ENTRY_PASSED");
                    }
                    if (worldTicks == 110) capture(mc, "live");
                    if (worldTicks == 120) {
                        setHostFixture(false);
                        mc.gui.setScreen(new PeerCraftSessionScreen(new net.minecraft.client.gui.screens.PauseScreen(true)));
                        System.out.println("SESSION_UI_PREVIEW_READY");
                    }
                    if (worldTicks == 125) {
                        var panel = mc.gui.screen();
                        var control = panel.children().stream()
                                .filter(c -> c instanceof net.minecraft.client.gui.components.Button b
                                        && b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("peercraft.session.handoff").getString()))
                                .map(c -> (net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow();
                        if (!control.visible || control.active) throw new IllegalStateException("Offline world needs a visible disabled handoff action");
                        capture(mc, "host-controls");
                        System.out.println("SESSION_UI_HANDOFF_DISCOVERABLE_PASSED");
                    }
                    if (worldTicks == 130) {
                        mc.gui.setScreen(new net.peercraft.client.gui.HandoffPlayerPickerScreen(new net.minecraft.client.gui.screens.PauseScreen(true)));
                    }
                    if (worldTicks == 135) capture(mc, "handoff");
                    return;
                }
                ticks++;
                if (ticks <= pages.length * 30 && ticks % 30 == 20) {
                    String name = pages[ticks / 30].toLowerCase(java.util.Locale.ROOT);
                    Screenshot.grab(mc.gameDirectory, "session-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                            message -> System.out.println("SESSION_UI_CAPTURED " + name));
                }
                if (ticks < pages.length * 30 && ticks % 30 == 0) {
                    Class<?> pageClass = Class.forName("net.peercraft.client.gui.PeerCraftSessionScreen$Page");
                    var method = PeerCraftSessionScreen.class.getDeclaredMethod("open", pageClass);
                    method.setAccessible(true);
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Object page = Enum.valueOf((Class) pageClass, pages[ticks / 30]);
                    method.invoke(screen, page);
                }
                if (ticks == pages.length * 30) {
                    creatingWorld = true;
                    var settings = new net.minecraft.world.level.LevelSettings("PeerCraft — просмотр интерфейса", net.minecraft.world.level.GameType.CREATIVE,
                            new net.minecraft.world.level.LevelSettings.DifficultySettings(net.minecraft.world.Difficulty.PEACEFUL, false, false),
                            true, net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel("session-ui-" + System.currentTimeMillis(), settings,
                            new net.minecraft.world.level.levelgen.WorldOptions(123, false, false),
                            registries -> registries.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                                    .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
                }
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.out.println("SESSION_UI_PREVIEW_FAILED");
                ticks = 10000;
                creatingWorld = false;
            }
        });
    }
    private void capture(net.minecraft.client.Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "session-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                message -> System.out.println("SESSION_UI_CAPTURED " + name));
    }
    private void setHostFixture(boolean enabled) throws Exception {
        var bridge = net.peercraft.network.p2p.P2PBridge.INSTANCE;
        var host = bridge.getClass().getDeclaredField("isHost"); host.setAccessible(true);
        var client = bridge.getClass().getDeclaredField("hostRendezvousClient"); client.setAccessible(true);
        if (enabled) {
            originalHost = host.getBoolean(bridge); originalRendezvous = client.get(bridge);
            host.setBoolean(bridge, true);
            client.set(bridge, new net.peercraft.network.rendezvous.RendezvousClient(
                    new net.peercraft.network.p2p.P2PSender(null), java.net.InetAddress.getLoopbackAddress(), 1));
        } else {
            host.setBoolean(bridge, originalHost); client.set(bridge, originalRendezvous);
        }
    }

}
