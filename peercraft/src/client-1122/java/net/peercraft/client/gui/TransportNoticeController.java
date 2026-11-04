package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiMainMenu;
import net.peercraft.network.p2p.P2PBridge;

/** Records network-thread events; presents them only after vanilla finishes disconnecting. */
public final class TransportNoticeController {
    private static volatile String pendingMode, pendingFailure, pendingHostNotice;
    private static volatile long modeExpires, failureExpires;
    private static P2PBridge.ClientJoinAttempt joinAttempt;
    private static GuiScreen joinScreen, returnScreen;
    private static boolean vanillaConnecting;
    private static java.lang.ref.WeakReference<Object> previousWorld = new java.lang.ref.WeakReference<Object>(null);
    private TransportNoticeController() {}

    public static void trackJoin(P2PBridge.ClientJoinAttempt attempt, GuiScreen owner, GuiScreen back) {
        joinAttempt = attempt;
        joinScreen = owner;
        returnScreen = back;
        vanillaConnecting = false;
        previousWorld = new java.lang.ref.WeakReference<Object>(Minecraft.getMinecraft().world);
    }

    public static void connecting() {
        vanillaConnecting = true;
        P2PBridge.ClientJoinAttempt attempt = joinAttempt;
        if (attempt != null && attempt.isCurrent()) attempt.telemetry().stage("minecraft_login");
    }

    private static void tickJoin(GuiScreen current, boolean inWorld) {
        P2PBridge.ClientJoinAttempt attempt = joinAttempt;
        if (attempt == null) return;
        if (!attempt.isBusy()) {
            allowRetry(!attempt.isCurrent());
            joinAttempt = null; joinScreen = null; returnScreen = null; return;
        }
        if (inWorld) { joinAttempt = null; joinScreen = null; returnScreen = null; return; }
        if (vanillaConnecting && current != joinScreen && current != returnScreen
                && !(current instanceof GuiDisconnected)) return;
        if (!vanillaConnecting && (current == joinScreen
                || current instanceof ModSyncPreparingScreen || current instanceof ModSyncConfirmScreen
                || current instanceof ModSyncProgressScreen || current instanceof ModSyncSecurityNoticeScreen)) return;
        if (vanillaConnecting && current instanceof GuiDisconnected)
            attempt.telemetry().finish("failed", "vanilla_disconnect");
        attempt.cancel();
        allowRetry(true);
        joinAttempt = null;
        joinScreen = null; returnScreen = null;
    }
    private static void allowRetry(boolean cancelled) {
        if (joinScreen instanceof PeerCraftJoinScreen) {
            ((PeerCraftJoinScreen) joinScreen).allowRetry(cancelled);
        }
    }

    public static void register() {
        P2PBridge.INSTANCE.setOnTransportSelected(key -> {
            if (key != null) pendingFailure = null;
            pendingMode = key; modeExpires = System.currentTimeMillis() + 45 * 60_000L;
        });
        P2PBridge.INSTANCE.setOnHostNotice(key -> pendingHostNotice = key);
        P2PBridge.INSTANCE.setOnTransportFailure(key -> {
            pendingMode = null; pendingFailure = key; failureExpires = System.currentTimeMillis() + 120_000;
        });
    }
    /** Runs on the Minecraft thread. Do not replace handoff/install/recovery screens. */
    public static void tick() {
        SessionDiagnosticsController.tick();
        AdmissionScreens.tick();
        Minecraft mc = Minecraft.getMinecraft();
        long now = System.currentTimeMillis();
        if (pendingMode != null && now > modeExpires) pendingMode = null;
        if (pendingFailure != null && now > failureExpires) pendingFailure = null;
        if (pendingMode != null && mc.player != null && P2PBridge.INSTANCE.isClientSessionActive()) {
            mc.player.sendMessage(new net.minecraft.util.text.TextComponentString(PeerCraftLang.tr(pendingMode))); pendingMode = null;
        }
        if (pendingHostNotice != null && mc.player != null) {
            String notice = pendingHostNotice; pendingHostNotice = null;
            mc.player.sendMessage(new net.minecraft.util.text.TextComponentString(PeerCraftLang.tr(notice)));
        }
        GuiScreen current = mc.currentScreen;
        if (TelemetryNoticeController.maybeShow(current)) return;
        if (current instanceof GuiMainMenu
                && !net.peercraft.config.PeerCraftConfig.MODE_DISABLED.equals(net.peercraft.config.PeerCraftConfig.mode())
                && net.peercraft.client.account.AccountWelcome.claim(
                        net.peercraft.client.account.AccountSessionHolder.current() != null)) {
            PeerCraftUi.setScreen(mc, new PeerCraftAccountScreen(current, true));
            return;
        }
        P2PBridge.INSTANCE.observeClientWorld(vanillaConnecting && mc.player != null && mc.world != null && mc.world != previousWorld.get());
        tickJoin(current, mc.player != null);
        if (pendingFailure != null && mc.player == null && current instanceof GuiDisconnected) {
            String reason = pendingFailure; pendingFailure = null;
            GuiScreen games = new PeerCraftMultiplayerScreen(new GuiMainMenu());
            PeerCraftUi.setScreen(mc, new PeerCraftConfirmScreen(retry ->
                    PeerCraftUi.setScreen(mc, retry ? new PeerCraftJoinScreen(games) : games),
                    PeerCraftLang.tr("peercraft.p2p.disconnected_title"), PeerCraftLang.tr(reason),
                    PeerCraftLang.tr("peercraft.p2p.retry"), PeerCraftLang.tr("peercraft.p2p.games")));
        }
    }
}
