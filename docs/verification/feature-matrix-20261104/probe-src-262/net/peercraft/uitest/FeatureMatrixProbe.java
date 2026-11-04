package net.peercraft.uitest;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.gui.*;
import net.peercraft.network.modsync.ModSyncPlan;
import net.peercraft.client.update.UpdateChecker;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.admission.AdmissionClient;
import net.peercraft.network.admission.AdmissionProtocol;
import net.peercraft.network.p2p.P2PBridge;

/**
 * Two real clients (host + joiner) against a local rendezvous server. Drives the production
 * screens: telemetry first-run notice, title update notice, join by code, join admission,
 * mod sync removal confirmation, restart screen and the in-game ESC (PC) menu.
 * Minecraft 26.2 API. Coordination files carry only public IDs/status.
 */
public final class FeatureMatrixProbe implements ClientModInitializer {
    private final String role = System.getProperty("peercraft.fm.role", "host");
    private final boolean host = "host".equals(role);
    private final String scenario = System.getProperty("peercraft.fm.scenario", "host");
    private final String telemetryChoice = System.getProperty("peercraft.fm.telemetry", "decline");
    private final boolean expectUpdate = Boolean.getBoolean("peercraft.fm.expectUpdate");
    private final Path coord = Paths.get(System.getProperty("peercraft.fm.coord"));
    private final int port = Integer.getInteger("peercraft.fm.port");
    private final String name = System.getProperty("peercraft.fm.name", host ? "HostPlayer" : "Joiner");
    private final long deadline = System.currentTimeMillis() + 420_000;

    private int stage;
    private int ticks;
    private long waitUntil;
    private boolean finished;
    private CompletableFuture<AccountClient.AccountSession> auth;
    private final Set<Screen> handled = Collections.newSetFromMap(new IdentityHashMap<>());
    private PauseScreen pause;
    private int menuTicks;
    private boolean capturedWaiting;
    private final Set<UUID> approved = new HashSet<>();

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
    }

    private void tick() {
        if (finished) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (System.currentTimeMillis() > deadline) throw new IllegalStateException("Timed out at stage " + stage + " screen=" + name(screen()));
            if (exists("failed-" + (host ? "joiner" : "host"))) throw new IllegalStateException("Other side failed");
            if (host) hostTick(mc); else joinerTick(mc);
        } catch (Throwable failure) {
            failure.printStackTrace();
            mark("FM_FAILED role=" + role + " scenario=" + scenario + " stage=" + stage + " :: " + failure);
            write("failed-" + role, String.valueOf(failure));
            finished = true;
            P2PBridge.INSTANCE.stop();
            mc.stop();
        }
    }

    // ---------------------------------------------------------------- host

    private void hostTick(Minecraft mc) throws Exception {
        approvePending();
        switch (stage) {
            case 0 -> {
                if (!titleReady(mc)) return;
                handleTelemetryNotice();
                if (!(screen() instanceof TitleScreen)) return;
                if (!signIn()) return;
                var settings = new net.minecraft.world.level.LevelSettings("PeerCraft matrix", net.minecraft.world.level.GameType.CREATIVE,
                        new net.minecraft.world.level.LevelSettings.DifficultySettings(net.minecraft.world.Difficulty.PEACEFUL, false, false),
                        true, net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("matrix-" + System.currentTimeMillis(), settings,
                        new net.minecraft.world.level.levelgen.WorldOptions(123, false, false),
                        r -> r.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                                .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
                stage = 1;
            }
            case 1 -> {
                if (mc.player == null || mc.getSingleplayerServer() == null || !mc.getConnection().hasClientLoaded()) return;
                net.peercraft.client.PeerCraftHostOptions.internetPlayRequested = true;
                net.peercraft.client.PeerCraftHostOptions.allowUnlicensedPlayers = true;
                net.peercraft.client.PeerCraftHostOptions.maxPlayers = 4;
                require(mc.getSingleplayerServer().publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN,
                        net.minecraft.util.HttpUtil.getAvailablePort()), "LAN publish failed");
                stage = 2;
            }
            case 2 -> {
                String room = P2PBridge.INSTANCE.registeredRoomCode();
                if (room == null || room.isEmpty()) return;
                write("room", room);
                mark("FM_HOST_ROOM " + room);
                stage = 3;
            }
            case 3 -> {
                // ESC menu as host, once a real guest is in the world.
                if (!exists("joiner-in-world")) { if (exists("stop")) stop(mc); return; }
                if (mc.getSingleplayerServer().getPlayerCount() < 2) return;
                if (escMenu(mc, "host")) stage = 4;
            }
            case 4 -> {
                write("host-menu-done", "yes");
                if (exists("stop")) stop(mc);
            }
            default -> { }
        }
    }

    private void approvePending() {
        for (AdmissionClient.Entry e : AdmissionClient.INSTANCE.entries()) {
            if (approved.add(e.id)) {
                Minecraft mc = Minecraft.getInstance();
                setScreen(new JoinRequestsScreen(new PauseScreen(true)));
                capture(mc, "host-join-request-" + approved.size());
                AdmissionClient.INSTANCE.decide(e.id, AdmissionProtocol.APPROVED);
                mark("FM_ADMISSION_APPROVED " + e.name);
                waitUntil = System.currentTimeMillis() + 1500;
            }
        }
        if (waitUntil != 0 && System.currentTimeMillis() > waitUntil) {
            waitUntil = 0;
            if (screen() instanceof JoinRequestsScreen) setScreen(null);
        }
    }

    // ---------------------------------------------------------------- joiner

    private void joinerTick(Minecraft mc) throws Exception {
        Screen s = screen();
        switch (stage) {
            case 0 -> {
                if (!titleReady(mc)) return;
                if (handleTelemetryNotice()) return;
                if (!(s instanceof TitleScreen)) return;
                stage = 1; ticks = 0;
            }
            case 1 -> {
                // Update notice: give the background check (local HTTP) up to 10 s.
                ticks++;
                boolean available = UpdateChecker.available() != null;
                boolean widget = s.children().stream().anyMatch(c -> c instanceof UpdateNoticeWidget w && w.visible);
                if (expectUpdate && !available && ticks < 200) return;
                if (!expectUpdate && ticks < 120) return;
                if (ticks < 30) return;
                capture(mc, scenario + "-title");
                require(available == expectUpdate, "Update availability " + available + ", expected " + expectUpdate);
                require(!expectUpdate || widget, "Update notice widget not on title screen");
                UpdateChecker.UpdateInfo info = UpdateChecker.available();
                mark("FM_UPDATE_NOTICE expected=" + expectUpdate + " available=" + available + " widget=" + widget
                        + (info == null ? "" : " current=" + info.current + " latest=" + info.latest + " url=" + info.url));
                stage = 2;
            }
            case 2 -> {
                if (!signIn()) return;
                if (!exists("room")) return;
                setScreen(new PeerCraftJoinScreen(new TitleScreen()));
                stage = 3;
            }
            case 3 -> {
                if (!(s instanceof PeerCraftJoinScreen)) return;
                EditBox box = (EditBox) field(s, PeerCraftJoinScreen.class, "roomCodeBox");
                box.setValue(read("room"));
                press((Button) field(s, PeerCraftJoinScreen.class, "connectButton"));
                mark("FM_JOIN_PRESSED " + read("room"));
                stage = 4; ticks = 0;
            }
            case 4 -> {
                if (++ticks % 40 == 0) mark("FM_TRACE screen=" + name(s) + " level=" + (mc.level != null)
                        + " admission=" + AdmissionClient.INSTANCE.guestStatus + " p2pSession=" + P2PBridge.INSTANCE.isClientSessionActive());
                joinFlow(mc, s);
            }
            case 5 -> {
                // In the world: ESC menu as guest, then leave.
                if (escMenu(mc, scenario + "-guest")) {
                    write("joiner-menu-done-" + scenario, "yes");
                    mark("FM_JOINER_DONE scenario=" + scenario);
                    stage = 6; ticks = 0;
                }
            }
            case 6 -> {
                if (++ticks == 20) { mc.disconnectFromWorld(Component.empty()); }
                if (ticks > 60 && mc.level == null) { finished = true; P2PBridge.INSTANCE.stop(); mc.stop(); }
            }
            default -> { }
        }
    }

    private void joinFlow(Minecraft mc, Screen s) throws Exception {
        if (mc.level != null && mc.player != null && mc.getConnection() != null && mc.getConnection().hasClientLoaded()) {
            require(!"remove".equals(scenario), "Joined the world although a removal had to be confirmed first");
            mark("FM_JOINED_WORLD scenario=" + scenario + " player=" + mc.player.getName().getString());
            write("joiner-in-world", scenario);
            stage = 5; menuTicks = 0; pause = null;
            return;
        }
        if (s == null || handled.contains(s)) return;
        if (s instanceof JoinRequestWaitingScreen) {
            if (!capturedWaiting) { capturedWaiting = true; capture(mc, scenario + "-admission-waiting"); }
            if (AdmissionClient.INSTANCE.guestStatus == AdmissionProtocol.APPROVED) AdmissionClient.INSTANCE.confirm();
            return;
        }
        if (s instanceof PeerCraftProgressNoticeScreen) {
            handled.add(s);
            press(find(s, tr("peercraft.gui.progress_notice.understood")));
            mark("FM_PROGRESS_NOTICE_ACK");
        } else if (s instanceof ModSyncSecurityNoticeScreen) {
            handled.add(s);
            capture(mc, scenario + "-modsync-security");
            press(find(s, tr("peercraft.modsync.notice.accept")));
            mark("FM_MODSYNC_TRUST_ACCEPTED");
        } else if (s instanceof ModSyncIssuesScreen) {
            handled.add(s);
            capture(mc, scenario + "-modsync-issues");
            throw new IllegalStateException("Unexpected mod sync issues screen");
        } else if (s instanceof ModSyncConfirmScreen) {
            if (ticksOn(s) < 15) return; // let it render once
            handled.add(s);
            ModSyncPlan plan = (ModSyncPlan) field(s, ModSyncConfirmScreen.class, "plan");
            List<String> removals = new ArrayList<>();
            for (ModSyncPlan.Removal r : plan.removals()) removals.add(r.id() + "=" + r.fileName());
            mark("FM_MODSYNC_CONFIRM scenario=" + scenario + " downloads=" + plan.mods().size() + " removals=" + removals);
            require(removals.stream().anyMatch(r -> r.startsWith("appleskin=")), "AppleSkin removal row missing: " + removals);
            require("remove".equals(scenario) || "untick".equals(scenario), "Confirm screen in scenario " + scenario);
            capture(mc, scenario + "-modsync-confirm");
            if ("untick".equals(scenario)) {
                // Row checkboxes are SteampunkSettingsTheme.Toggle (package-private Button).
                Button box = (Button) s.children().stream().filter(c -> c.getClass().getSimpleName().equals("Toggle")).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No removal checkbox"));
                require(toggled(box), "Removal row was not ticked by default");
                press(box);
                require(!toggled(box), "Checkbox did not untick");
                mark("FM_REMOVAL_UNTICKED");
                capture(mc, scenario + "-modsync-confirm-unticked");
            }
            press(find(s, tr("peercraft.modsync.confirm.accept")));
        } else if (s instanceof ModSyncRestartRequiredScreen) {
            if (ticksOn(s) < 15) return;
            handled.add(s);
            require("remove".equals(scenario), "Restart screen in scenario " + scenario);
            capture(mc, scenario + "-modsync-restart");
            mark("FM_MODSYNC_RESTART_SCREEN");
            write("joiner-restart", "yes");
            waitThenQuit(s);
        } else if (s instanceof net.minecraft.client.gui.screens.DisconnectedScreen) {
            capture(mc, scenario + "-disconnected");
            throw new IllegalStateException("Disconnected: " + s.getTitle().getString());
        }
    }

    private final Map<Screen, Integer> screenTicks = new IdentityHashMap<>();
    private int ticksOn(Screen s) { return screenTicks.merge(s, 1, Integer::sum); }

    private void waitThenQuit(Screen s) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            Minecraft.getInstance().execute(() -> {
                mark("FM_QUIT_FOR_HELPER");
                finished = true;
                press(find(s, tr("peercraft.modsync.restart.quit")));
            });
        });
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- ESC menu

    /** Returns true once the pause entry, hub and players screens were checked and captured. */
    private boolean escMenu(Minecraft mc, String tag) throws Exception {
        menuTicks++;
        if (menuTicks == 20) {
            pause = new PauseScreen(true);
            setScreen(pause);
        } else if (menuTicks == 30) {
            Button pc = find(pause, "PC");
            for (GuiEventListener child : pause.children()) {
                if (child == pc || !(child instanceof AbstractWidget w) || !w.visible) continue;
                if (pc.getX() < w.getX() + w.getWidth() && pc.getX() + pc.getWidth() > w.getX()
                        && pc.getY() < w.getY() + w.getHeight() && pc.getY() + pc.getHeight() > w.getY())
                    throw new IllegalStateException("PC button overlaps " + w.getMessage().getString());
            }
            require(pc.getWidth() == 20 && pc.getHeight() == 20, "PC button is not 20x20");
            capture(mc, tag + "-pause");
            press(pc);
            require(screen() instanceof PeerCraftInGameMenuScreen, "PC did not open the in-game menu");
        } else if (menuTicks == 45) {
            capture(mc, tag + "-hub");
            press(find(screen(), tr("peercraft.ingame.players")));
            require(screen() instanceof PeerCraftServerPlayersScreen, "Players entry broken");
        } else if (menuTicks == 60) {
            capture(mc, tag + "-players");
            screen().onClose();
            require(screen() instanceof PeerCraftInGameMenuScreen, "Back did not return to the in-game menu");
            press(find(screen(), tr("peercraft.ingame.friends")));
            require(screen() instanceof PeerCraftInGameFriendsScreen, "Friends entry broken");
        } else if (menuTicks == 75) {
            capture(mc, tag + "-friends");
            setScreen(null);
            mark("FM_ESC_MENU_PASSED " + tag);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- shared

    private boolean titleReady(Minecraft mc) {
        return mc.gui.overlay() == null && screen() != null;
    }

    /** First-run telemetry notice: captured, then answered per peercraft.fm.telemetry. */
    private boolean handleTelemetryNotice() {
        Screen s = screen();
        if (s == null || !"PeerCraftConfirmScreen".equals(s.getClass().getSimpleName()) || handled.contains(s)) return false;
        if (!s.getTitle().getString().equals(tr("peercraft.telemetry.title"))) return false;
        if (ticksOn(s) < 15) return true;
        handled.add(s);
        capture(Minecraft.getInstance(), role + "-" + scenario + "-telemetry-notice");
        boolean allow = "allow".equals(telemetryChoice);
        press(find(s, tr(allow ? "peercraft.telemetry.allow" : "peercraft.telemetry.decline")));
        mark("FM_TELEMETRY_CHOICE " + (allow ? "allow" : "decline") + " enabled=" + net.peercraft.network.telemetry.Telemetry.isEnabled());
        require(net.peercraft.network.telemetry.Telemetry.isEnabled() == allow, "Telemetry gate does not match the choice");
        return true;
    }

    private boolean signIn() throws Exception {
        if (AccountClient.INSTANCE.getCurrentSession() != null) return true;
        if (auth == null) {
            AccountClient.INSTANCE.connect("127.0.0.1", port);
            auth = new CompletableFuture<>();
            AccountClient.INSTANCE.registerUnlicensed(name, "LocalMatrixProbePassword".toCharArray(), new AccountClient.AuthCallback() {
                public void onSuccess(AccountClient.AccountSession session) { auth.complete(session); }
                public void onFailed(String reason) { auth.completeExceptionally(new IllegalStateException(reason)); }
            });
        }
        if (!auth.isDone()) return false;
        mark("FM_SIGNED_IN " + name + " " + auth.get().accountId());
        return AccountClient.INSTANCE.getCurrentSession() != null;
    }

    private void stop(Minecraft mc) {
        mark("FM_HOST_DONE");
        finished = true;
        P2PBridge.INSTANCE.stop();
        mc.stop();
    }

    private static Screen screen() { return Minecraft.getInstance().gui.screen(); }
    private static void setScreen(Screen s) { Minecraft.getInstance().gui.setScreen(s); }
    private static String tr(String key) { return Component.translatable(key).getString(); }
    private static String name(Object o) { return o == null ? "null" : o.getClass().getSimpleName(); }

    private static void press(net.minecraft.client.gui.components.AbstractButton b) {
        b.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
    }

    private static Button find(Screen screen, String label) {
        return screen.children().stream()
                .filter(c -> c instanceof Button b && b.getMessage().getString().equals(label))
                .map(c -> (Button) c).findFirst()
                .orElseThrow(() -> new IllegalStateException("No button '" + label + "' on " + name(screen)));
    }

    private static boolean toggled(Button toggle) throws Exception {
        return (Boolean) field(toggle, toggle.getClass(), "selected");
    }

    private static Object field(Object o, Class<?> owner, String field) throws Exception {
        var f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(o);
    }

    private void capture(Minecraft mc, String shot) {
        Screenshot.grab(mc.gameDirectory, "fm-" + shot + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                m -> System.out.println("FM_CAPTURED " + shot));
    }

    private static void mark(String line) { System.out.println(line); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    private boolean exists(String n) { return Files.exists(coord.resolve(n)); }
    private String read(String n) throws Exception { return Files.readString(coord.resolve(n)).trim(); }
    private void write(String n, String v) {
        try {
            Files.createDirectories(coord);
            Path tmp = coord.resolve(n + ".tmp");
            Files.writeString(tmp, v, StandardCharsets.UTF_8);
            Files.move(tmp, coord.resolve(n), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
