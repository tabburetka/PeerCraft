package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiScreen;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.rendezvous.AccountProtocol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Forge 1.12.2 twin. In-world friends list: incoming requests on top (accept in one click), then friends with status. Joining lives in the multiplayer screen. */
public class PeerCraftInGameFriendsScreen extends PeerCraftRowsScreen {
    private List<AccountClient.FriendInfo> friends;
    private List<AccountClient.IncomingRequest> requests;
    private final Set<UUID> pending = new HashSet<>();
    private boolean loadingStarted;

    public PeerCraftInGameFriendsScreen(GuiScreen parent) {
        super(parent, PeerCraftLang.tr("peercraft.ingame.friends.title"));
    }

    @Override
    public void initGui() {
        if (!loadingStarted) {
            loadingStarted = true;
            setStatus(PeerCraftLang.tr("peercraft.gui.multiplayer.loading_friends"), PeerCraftUi.TEXT_MUTED);
            load();
        }
        super.initGui();
    }

    private void load() {
        AccountClient.INSTANCE.listFriends(new AccountClient.FriendListCallback() {
            @Override public void onResult(List<AccountClient.FriendInfo> result) {
                runOnClientThread(() -> { friends = new ArrayList<>(result); updateStatus(); refresh(); });
            }
            @Override public void onTimeout() { runOnClientThread(() -> timeout()); }
        });
        AccountClient.INSTANCE.listIncomingRequests(new AccountClient.FriendRequestListCallback() {
            @Override public void onResult(List<AccountClient.IncomingRequest> result) {
                FriendRequestNotifier.update(result);
                runOnClientThread(() -> { requests = new ArrayList<>(result); updateStatus(); refresh(); });
            }
            @Override public void onTimeout() { runOnClientThread(() -> timeout()); }
        });
    }

    private void timeout() {
        setStatus(PeerCraftLang.tr("peercraft.gui.common.account_server_timeout"), PeerCraftUi.TEXT_ERROR);
    }

    private void updateStatus() {
        if (friends == null || requests == null) return;
        if (friends.isEmpty() && requests.isEmpty()) setStatus(PeerCraftLang.tr("peercraft.ingame.friends.empty"), PeerCraftUi.TEXT_MUTED);
        else setStatus("", PeerCraftUi.TEXT_MUTED);
    }

    @Override
    protected List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (requests != null) for (AccountClient.IncomingRequest request : requests) {
            rows.add(new Row(request.displayName(), request.licensed(), true,
                    PeerCraftLang.tr("peercraft.ingame.friends.wants_to_add"), PeerCraftUi.TEXT_ACCENT,
                    new RowAction(PeerCraftLang.tr("peercraft.gui.friend_requests.accept"), true,
                            !pending.contains(request.fromAccountId()), () -> accept(request))));
        }
        if (friends != null) for (AccountClient.FriendInfo friend : friends) {
            rows.add(new Row(friend.displayName(), friend.licensed(), true, status(friend), statusColor(friend),
                    new RowAction(PeerCraftLang.tr("peercraft.gui.multiplayer.remove"), false,
                            !pending.contains(friend.accountId()), () -> confirmRemove(friend))));
        }
        return rows;
    }

    private static String status(AccountClient.FriendInfo friend) {
        if (friend.status() == AccountProtocol.STATUS_HOSTING)
            return PeerCraftLang.tr("peercraft.gui.multiplayer.status_hosting", friend.roomCode());
        return PeerCraftLang.tr(friend.status() == AccountProtocol.STATUS_ONLINE
                ? "peercraft.gui.multiplayer.status_online" : "peercraft.gui.multiplayer.status_offline");
    }

    private static int statusColor(AccountClient.FriendInfo friend) {
        return friend.status() == AccountProtocol.STATUS_OFFLINE ? PeerCraftUi.TEXT_MUTED : PeerCraftUi.TEXT_SUCCESS;
    }

    @Override
    protected List<Footer> footer() {
        int count = requests == null ? 0 : requests.size();
        List<Footer> footer = new ArrayList<>();
        footer.add(new Footer(count > 0
                ? PeerCraftLang.tr("peercraft.ingame.friends.requests_count", count)
                : PeerCraftLang.tr("peercraft.gui.multiplayer.friend_requests_button"), false, true,
                () -> PeerCraftUi.setScreen(mc, new PeerCraftFriendRequestsScreen(new PeerCraftInGameFriendsScreen(parent)))));
        footer.add(new Footer(PeerCraftLang.tr("peercraft.gui.common.back"), true, true, this::onClose));
        return footer;
    }

    private void accept(AccountClient.IncomingRequest request) {
        if (!pending.add(request.fromAccountId())) return;
        refresh();
        AccountClient.INSTANCE.respondToRequest(request.fromAccountId(), true, new AccountClient.AckCallback() {
            @Override public void onSuccess() {
                runOnClientThread(() -> {
                    pending.remove(request.fromAccountId());
                    // Reload so the new friend shows up with live status.
                    friends = null; requests = null;
                    setStatus(PeerCraftLang.tr("peercraft.ingame.friends.accepted", request.displayName()), PeerCraftUi.TEXT_SUCCESS);
                    load();
                    refresh();
                });
            }
            @Override public void onFailed(String reason) { runOnClientThread(() -> failed(request.fromAccountId(), reason)); }
        });
    }

    private void confirmRemove(AccountClient.FriendInfo friend) {
        PeerCraftUi.setScreen(this.mc, new PeerCraftConfirmScreen(confirmed -> {
            PeerCraftUi.setScreen(this.mc, this);
            if (confirmed) remove(friend);
        }, PeerCraftLang.tr("peercraft.gui.multiplayer.remove_friend_confirm_title"),
                PeerCraftLang.tr("peercraft.gui.multiplayer.remove_friend_confirm_message", friend.displayName())));
    }

    private void remove(AccountClient.FriendInfo friend) {
        if (!pending.add(friend.accountId())) return;
        refresh();
        AccountClient.INSTANCE.removeFriend(friend.accountId(), new AccountClient.AckCallback() {
            @Override public void onSuccess() {
                runOnClientThread(() -> {
                    pending.remove(friend.accountId());
                    if (friends != null) friends.remove(friend);
                    updateStatus();
                    refresh();
                });
            }
            @Override public void onFailed(String reason) { runOnClientThread(() -> failed(friend.accountId(), reason)); }
        });
    }

    private void failed(UUID id, String reason) {
        pending.remove(id);
        setStatus(reason, PeerCraftUi.TEXT_ERROR);
        refresh();
    }
}
