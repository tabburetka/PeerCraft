package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.peercraft.network.account.AccountClient;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The rendezvous server never pushes friend requests, so while the player is in a world this
 * polls the incoming list and posts one chat line per new request. The count also tints the
 * pause-menu "PC" button.
 */
public final class FriendRequestNotifier {
    private static final long PERIOD_SECONDS = 45;
    private static final Object LOCK = new Object();
    private static ScheduledExecutorService executor;
    private static final Set<UUID> announced = new HashSet<>();
    private static volatile int pendingCount;
    private static volatile UUID accountId;

    private FriendRequestNotifier() {
    }

    public static void start() {
        synchronized (LOCK) {
            if (executor != null) return;
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "peercraft-friend-requests");
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleWithFixedDelay(FriendRequestNotifier::poll, 15, PERIOD_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Incoming requests as of the last poll; 0 when signed out or not yet known. */
    public static int pendingCount() {
        AccountClient.AccountSession session = AccountClient.INSTANCE.getCurrentSession();
        return session != null && session.accountId().equals(accountId) ? pendingCount : 0;
    }

    /** Screens that already hold a fresh list update the cached state without a round trip. */
    static void update(List<AccountClient.IncomingRequest> requests) {
        AccountClient.AccountSession session = AccountClient.INSTANCE.getCurrentSession();
        if (session == null) return;
        synchronized (LOCK) {
            if (!session.accountId().equals(accountId)) announced.clear();
            accountId = session.accountId();
            pendingCount = requests.size();
            // Requests the player has already seen on screen are never announced in chat.
            for (AccountClient.IncomingRequest request : requests) announced.add(request.fromAccountId());
        }
    }

    private static void poll() {
        try {
            AccountClient.AccountSession session = AccountClient.INSTANCE.getCurrentSession();
            if (session == null || Minecraft.getMinecraft().theWorld == null) return;
            AccountClient.INSTANCE.listIncomingRequests(new AccountClient.FriendRequestListCallback() {
                @Override
                public void onResult(List<AccountClient.IncomingRequest> requests) {
                    java.util.List<String> fresh = new java.util.ArrayList<>();
                    synchronized (LOCK) {
                        if (!session.accountId().equals(accountId)) announced.clear();
                        accountId = session.accountId();
                        pendingCount = requests.size();
                        Set<UUID> current = new HashSet<>();
                        for (AccountClient.IncomingRequest request : requests) {
                            current.add(request.fromAccountId());
                            if (announced.add(request.fromAccountId())) fresh.add(request.displayName());
                        }
                        // A declined-then-resent request is announced again.
                        announced.retainAll(current);
                    }
                    if (!fresh.isEmpty()) Minecraft.getMinecraft().func_152344_a(() -> announce(fresh));
                }

                @Override
                public void onTimeout() {
                }
            });
        } catch (RuntimeException ignored) {
            // Never let one failed poll cancel the schedule.
        }
    }

    private static void announce(List<String> names) {
        net.minecraft.client.entity.EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;
        String message = names.size() == 1
                ? PeerCraftLang.tr("peercraft.ingame.friend_request_chat", names.get(0))
                : PeerCraftLang.tr("peercraft.ingame.friend_requests_chat", names.size());
        player.addChatMessage(new net.minecraft.util.ChatComponentText(message));
    }
}
