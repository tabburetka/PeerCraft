package net.peercraft.client.mixin;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;

@Mixin(IntegratedServer.class)
public abstract class OpenToLanMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    @Inject(method = "publishServer", at = @At("RETURN"))
    private void onOpenToLan(GameType gameMode, boolean cheatsAllowed, int port, CallbackInfoReturnable<Boolean> cir) {

        if (cir.getReturnValue()) {
            if (PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode()) || PeerCraftConfig.MODE_CLIENT.equals(PeerCraftConfig.mode())) {
                LOGGER.info("[PeerCraft P2P] Хост-мост не запускается в режиме {}", PeerCraftConfig.mode());
                return;
            }

            IntegratedServer server = (IntegratedServer) (Object) this;

            // Получаем LAN-порт, на котором поднялся мир Майнкрафта
            int lanPort = server.getPort();

            LOGGER.info("[PeerCraft P2P] Мир успешно открыт для сети на порту: {}", lanPort);

            // Игроки подключаются через наш UDP-мост, а не напрямую по локальной сети,
            // поэтому у них нет возможности пройти обычную LAN-автообнаруженную сессию.
            // Отключаем проверку сессии Mojang для этого хоста, как это по сути уже
            // происходит для игроков, заходящих через обычное автообнаружение LAN.
            server.setUsesAuthentication(false);
            LOGGER.info("[PeerCraft P2P] Проверка сессии Mojang отключена для хоста PeerCraft.");

            // 1. Закрываем LocalProxy на Хосте, чтобы освободить 25565
            if (P2PBridge.INSTANCE.getProxy() != null) {
                P2PBridge.INSTANCE.getProxy().stop();
                LOGGER.info("[PeerCraft P2P] localProxy закрыт на Хосте.");
            }

            // 2. Передаем LAN-порт в P2PBridge для запуска UDP-хоста
            P2PBridge.INSTANCE.startHost(lanPort);
        }
    }
}