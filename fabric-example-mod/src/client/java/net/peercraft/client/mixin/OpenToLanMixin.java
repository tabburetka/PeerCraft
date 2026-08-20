package net.peercraft.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.peercraft.client.PeerCraftHostOptions;
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

            // Игроки подключаются через наш UDP-мост, а не напрямую по локальной сети, но
            // P2PBridge реле прозрачно для байт — настоящий зашифрованный Mojang-handshake
            // проходит сквозь туннель как есть. IntegratedServer.initServer() уже включает
            // аутентификацию (setUsesAuthentication(true)) при старте мира по умолчанию —
            // publishServer() это не трогает, так что если хост НЕ разрешил пиратов, ничего
            // делать не нужно вообще, аутентификация просто остаётся включённой. Если хост
            // явно разрешил нелицензионных игроков — отключаем её, как раньше.
            if (PeerCraftHostOptions.allowUnlicensedPlayers) {
                server.setUsesAuthentication(false);
                LOGGER.info("[PeerCraft P2P] Проверка сессии Mojang отключена для хоста PeerCraft (разрешены нелицензионные игроки).");
            } else {
                server.setUsesAuthentication(true);
                LOGGER.info("[PeerCraft P2P] Проверка сессии Mojang включена — только лицензионные игроки смогут подключиться.");
            }

            // 1. Закрываем LocalProxy на Хосте, чтобы освободить 25565
            if (P2PBridge.INSTANCE.getProxy() != null) {
                P2PBridge.INSTANCE.getProxy().stop();
                LOGGER.info("[PeerCraft P2P] localProxy закрыт на Хосте.");
            }

            // 2. Передаем LAN-порт в P2PBridge для запуска UDP-хоста — либо статически
            // (127.0.0.1/локальный тест), либо через сервер знакомств + hole punching
            // для реального интернет-P2P. Решение — флажок "PeerCraft: через интернет"
            // на этом же экране (ShareToLanScreenMixin), а не launch-флаг.
            if (PeerCraftHostOptions.internetPlayRequested) {
                LOGGER.info("[PeerCraft P2P] Через интернет — используем сервер знакомств (макс. игроков: {}), peerHost/peerPort игнорируются.", PeerCraftHostOptions.maxPlayers);
                P2PBridge.INSTANCE.startHostViaRendezvous(lanPort, PeerCraftHostOptions.maxPlayers, new P2PBridge.HostListener() {
                    @Override
                    public void onRoomCreated(String code, boolean changed) {
                        if (changed) {
                            sendChatMessage("PeerCraft: старый код комнаты больше не работает (сервер знакомств потерял комнату) — новый код: " + code);
                        } else {
                            sendChatMessage("PeerCraft: код комнаты для второго игрока — " + code);
                        }
                    }

                    @Override
                    public void onFailed(String reason) {
                        sendChatMessage("PeerCraft: " + reason);
                    }
                });
            } else {
                P2PBridge.INSTANCE.startHost(lanPort);
            }
        }
    }

    // Хост закрыл мир (вышел, отключился, вернулся в титульный экран) — если попытка
    // хостинга через сервер знакомств так и не была заклеймлена джойнером, её
    // RendezvousClient иначе продолжал бы слать keepalive REGISTER вечно, молча
    // пересоздавая комнату с новым кодом на сервере каждые ~10 минут (см. cancelRendezvous).
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void onStopServer(CallbackInfo ci) {
        P2PBridge.INSTANCE.cancelRendezvous();
    }

    // Колбэки P2PBridge/RendezvousClient зовутся из фонового потока — сообщение в чат
    // (клиентское, никуда не отправляется) можно показывать только с клиентского потока.
    private static void sendChatMessage(String message) {
        Minecraft.getInstance().execute(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(Component.literal(message), false);
            }
        });
    }
}