package net.peercraft.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.client.account.AccountState;
import net.peercraft.client.account.AccountStorage;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.p2p.P2PBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class PeerCraftClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    @Override
    public void onInitializeClient() {
        String mode = PeerCraftConfig.mode();

        // Accounts/friends work independently of hosting/joining mode — a player might only
        // ever use the friends list, never P2P itself this session.
        if (!PeerCraftConfig.MODE_DISABLED.equals(mode)) {
            AccountClient.INSTANCE.connect(PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort());
            attemptSilentRelogin();
            // Best-effort: tells the server to drop presence immediately on quit instead of
            // waiting out PresenceRegistry's TTL — harmless no-op if never logged in.
            ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> AccountClient.INSTANCE.stopPresenceHeartbeat());
        }

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

    private void attemptSilentRelogin() {
        Optional<AccountState> saved = AccountStorage.load();
        if (saved.isEmpty()) {
            return;
        }
        AccountState state = saved.get();
        AccountClient.INSTANCE.loginRemembered(state.accountId(), state.rememberToken(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                LOGGER.info("[PeerCraft] Тихий вход выполнен: {}", session.displayName());
                AccountSessionHolder.persist(session);
            }

            @Override
            public void onFailed(String reason) {
                LOGGER.warn("[PeerCraft] Тихий вход не удался ({}) — потребуется войти вручную", reason);
                AccountStorage.clear();
            }
        });
    }
}
