package net.peercraft.client;

import net.fabricmc.api.ClientModInitializer;
import net.peercraft.network.p2p.P2PBridge;

public class PeerCraftClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Запускаем TCP-прокси на клиенте при старте игры
        P2PBridge.INSTANCE.startProxy(25565);
        P2PBridge.INSTANCE.startClientTest();
    }
}