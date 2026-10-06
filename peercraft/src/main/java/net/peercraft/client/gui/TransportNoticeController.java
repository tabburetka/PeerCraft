package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
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
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (pendingMode != null && now > modeExpires) pendingMode = null;
        if (pendingFailure != null && now > failureExpires) pendingFailure = null;
        if (pendingMode != null && mc.player != null && P2PBridge.INSTANCE.isClientSessionActive()) {
            Component message = Component.translatable(pendingMode); pendingMode = null;
            //? if <26.1
            mc.player.displayClientMessage(message, false);
            //? if >=26.1
            /*mc.player.sendSystemMessage(message);*/
        }
        //? if <26.2
        Screen current = mc.screen;
        //? if >=26.2
        /*Screen current = mc.gui.screen();*/
        if (pendingFailure != null && mc.player == null && current instanceof DisconnectedScreen) {
            String reason = pendingFailure; pendingFailure = null;
            Screen games = new PeerCraftMultiplayerScreen(new TitleScreen());
            PeerCraftUi.setScreen(mc, new PeerCraftConfirmScreen(retry ->
                    PeerCraftUi.setScreen(mc, retry ? new PeerCraftJoinScreen(games) : games),
                    Component.translatable("peercraft.p2p.disconnected_title"), Component.translatable(reason),
                    Component.translatable("peercraft.p2p.retry"), Component.translatable("peercraft.p2p.games")));
        }
    }
}
