package net.peercraft.client;

import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.client.account.AccountState;
import net.peercraft.client.account.AccountStorage;
import net.peercraft.client.gui.HandoffClientController;
import net.peercraft.client.modsync.ModSyncFilesystem;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.config.PeerCraftSettingsStore;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.platform.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/** Loader-agnostic client init — called from each loader's thin client entrypoint. */
public final class PeerCraftClientCommon {
    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    private PeerCraftClientCommon() {
    }

    public static void initClient() {
        // Fold the in-game PeerCraft Settings screen's saved flags (config/peercraft/settings.json)
        // into PeerCraftConfig before anything reads a flag. Tolerant — a missing/corrupt file
        // just means the launch flags / built-in defaults apply. An explicit -Dpeercraft.* still wins.
        try {
            PeerCraftConfig.applyOverrides(PeerCraftSettingsStore.load().toOverrideMap());
        } catch (RuntimeException e) {
            LOGGER.warn("[PeerCraft] settings.json пропущен: {}", e.toString());
        }

        String mode = PeerCraftConfig.mode();

        // Best-effort: clear any half-written mod-sync temp files (*.jar.part, serving/) from a
        // previous run. Never touches real .jar files. Tolerant — never throws.
        try {
            ModSyncFilesystem.sweepOnStartup(Services.PLATFORM.getModsDir());
        } catch (RuntimeException e) {
            LOGGER.debug("[PeerCraft] Уборка mod-sync пропущена: {}", e.toString());
        }

        // Accounts/friends work independently of hosting/joining mode — a player might only
        // ever use the friends list, never P2P itself this session.
        if (!PeerCraftConfig.MODE_DISABLED.equals(mode)) {
            AccountClient.INSTANCE.connect(PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort());
            attemptSilentRelogin();
            // Best-effort: tells the server to drop presence immediately on quit instead of
            // waiting out PresenceRegistry's TTL — harmless no-op if never logged in. A JVM
            // shutdown hook (rather than a loader lifecycle event) keeps this loader-agnostic.
            Runtime.getRuntime().addShutdownHook(new Thread(AccountClient.INSTANCE::stopPresenceHeartbeat, "peercraft-shutdown-heartbeat"));
        }

        if (PeerCraftConfig.MODE_DISABLED.equals(mode) || PeerCraftConfig.MODE_HOST.equals(mode)) {
            LOGGER.info("[PeerCraft] Клиентский прокси не запускается в режиме {}", mode);
            return;
        }

        // Joiner-side host-handoff wiring: installs a HandoffClientAgent on every successful
        // join and turns offer / MIGRATE into screens (see HandoffClientController). The
        // client/gui/** package is swapped wholesale for a 1.16.5-API twin on that backport
        // (see build.fabric-1165.gradle.kts), so this call resolves there too.
        HandoffClientController.INSTANCE.register();

        P2PBridge.INSTANCE.startProxy(PeerCraftConfig.proxyPort());
        if (PeerCraftConfig.internetPlay()) {
            LOGGER.info("[PeerCraft] internetPlay=true — присоединение к комнате теперь запускается кнопкой \"PeerCraft: Join\" на титульном экране, а не при старте игры.");
        } else {
            P2PBridge.INSTANCE.startClient();
        }
    }

    private static void attemptSilentRelogin() {
        Optional<AccountState> saved = AccountStorage.load();
        //? if >=1.17
        if (saved.isEmpty()) {
        //? if <1.17
        /*if (!saved.isPresent()) {*/
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
