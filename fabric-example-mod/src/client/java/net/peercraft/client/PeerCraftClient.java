package net.peercraft.client;

import net.fabricmc.api.ClientModInitializer;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PeerCraftClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    @Override
    public void onInitializeClient() {
        String mode = PeerCraftConfig.mode();
        if (PeerCraftConfig.MODE_DISABLED.equals(mode) || PeerCraftConfig.MODE_HOST.equals(mode)) {
            LOGGER.info("[PeerCraft] Клиентский прокси не запускается в режиме {}", mode);
            return;
        }

        P2PBridge.INSTANCE.startProxy(PeerCraftConfig.proxyPort());
        if (PeerCraftConfig.internetPlay()) {
            LOGGER.info("[PeerCraft] internetPlay=true — присоединение к комнате теперь запускается кнопкой \"PeerCraft: Join\" на титульном экране, а не при старте игры.");
        } else {
            P2PBridge.INSTANCE.startClient();
        }
    }
}
