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
        LOGGER.info("[PeerCraft] Client entrypoint загружен. raw peercraft.mode='{}', effective mode='{}'", PeerCraftConfig.rawMode(), mode);
        if (PeerCraftConfig.MODE_DISABLED.equals(mode) || PeerCraftConfig.MODE_HOST.equals(mode)) {
            LOGGER.info("[PeerCraft] Клиентский TCP-прокси и UDP-клиент не запускаются в режиме {}", mode);
            return;
        }

        LOGGER.info("[PeerCraft] Режим {}, TCP proxyPort {}, clientUdpPort {}, peer {}:{}",
                mode,
                PeerCraftConfig.proxyPort(),
                PeerCraftConfig.clientUdpPort(),
                PeerCraftConfig.peerHost(),
                PeerCraftConfig.peerPortForClient());
        P2PBridge.INSTANCE.startProxy(PeerCraftConfig.proxyPort());
        P2PBridge.INSTANCE.startClient();
    }
}
