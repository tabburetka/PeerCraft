package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiMainMenu;
import net.peercraft.network.p2p.P2PBridge;

/** Records network-thread events; presents them only after vanilla finishes disconnecting. */
public final class TransportNoticeController {
    private static volatile String pendingMode, pendingFailure;
    private static volatile long modeExpires, failureExpires;
    private TransportNoticeController() {}
    public static void register() {
        P2PBridge.INSTANCE.setOnTransportSelected(key -> {
            if (key != null) pendingFailure = null;
            pendingMode = key; modeExpires = System.currentTimeMillis() + 45 * 60_000L;
        });
        P2PBridge.INSTANCE.setOnTransportFailure(key -> {
            pendingMode = null; pendingFailure = key; failureExpires = System.currentTimeMillis() + 120_000;
        });
    }
    /** Runs on the Minecraft thread. Do not replace handoff/install/recovery screens. */
    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        long now = System.currentTimeMillis();
        if (pendingMode != null && now > modeExpires) pendingMode = null;
        if (pendingFailure != null && now > failureExpires) pendingFailure = null;
        if (pendingMode != null && mc.thePlayer != null && P2PBridge.INSTANCE.isClientSessionActive()) {
            mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(PeerCraftLang.tr(pendingMode))); pendingMode = null;
        }
        GuiScreen current = mc.currentScreen;
        if (pendingFailure != null && mc.thePlayer == null && current instanceof GuiDisconnected) {
            String reason = pendingFailure; pendingFailure = null;
            GuiScreen games = new PeerCraftMultiplayerScreen(new GuiMainMenu());
            PeerCraftUi.setScreen(mc, new PeerCraftConfirmScreen(retry ->
                    PeerCraftUi.setScreen(mc, retry ? new PeerCraftJoinScreen(games) : games),
                    PeerCraftLang.tr("peercraft.p2p.disconnected_title"), PeerCraftLang.tr(reason),
                    PeerCraftLang.tr("peercraft.p2p.retry"), PeerCraftLang.tr("peercraft.p2p.games")));
        }
    }
}
