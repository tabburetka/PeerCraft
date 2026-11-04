package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.peercraft.network.account.AccountClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Forge 1.12.2 twin. Everyone on the current server, with a one-click friend request. A player's profile UUID is
 * their PeerCraft accountId when the host is PeerCraft (see ServerLoginPacketListenerImplMixin);
 * otherwise the account is resolved by exact display name.
 */
public class PeerCraftServerPlayersScreen extends PeerCraftRowsScreen {
    private enum State { SENDING, SENT, NOT_FOUND, AMBIGUOUS, ACCEPTED }

    private static final class Player {
        private final UUID id; private final String name;
        Player(UUID id, String name) { this.id = id; this.name = name; }
        UUID id() { return id; }
        String name() { return name; }
    }

    private List<AccountClient.FriendInfo> friends;
    private List<AccountClient.IncomingRequest> requests;
    private final Map<UUID, State> states = new HashMap<>();
    private boolean loadingStarted;

    public PeerCraftServerPlayersScreen(GuiScreen parent) {
        super(parent, PeerCraftLang.tr("peercraft.ingame.players.title"));
    }

    private static boolean signedIn() {
        return AccountClient.INSTANCE.getCurrentSession() != null;
    }

    @Override
    public void initGui() {
        if (!loadingStarted && signedIn()) {
            loadingStarted = true;
            AccountClient.INSTANCE.listFriends(new AccountClient.FriendListCallback() {
                @Override public void onResult(List<AccountClient.FriendInfo> result) { runOnClientThread(() -> { friends = result; refresh(); }); }
                @Override public void onTimeout() { runOnClientThread(() -> timeout()); }
            });
            AccountClient.INSTANCE.listIncomingRequests(new AccountClient.FriendRequestListCallback() {
                @Override public void onResult(List<AccountClient.IncomingRequest> result) {
                    FriendRequestNotifier.update(result);
                    runOnClientThread(() -> { requests = result; refresh(); });
                }
                @Override public void onTimeout() { runOnClientThread(() -> timeout()); }
            });
        }
        if (!signedIn()) setStatus(PeerCraftLang.tr("peercraft.ingame.sign_in_needed"), PeerCraftUi.TEXT_MUTED);
        super.initGui();
    }

    private void timeout() {
        setStatus(PeerCraftLang.tr("peercraft.gui.common.account_server_timeout"), PeerCraftUi.TEXT_ERROR);
    }

    private List<Player> players() {
        List<Player> out = new ArrayList<>();
        if (mc == null || mc.getConnection() == null) return out;
        UUID self = mc.player != null ? mc.player.getUniqueID() : null;
        AccountClient.AccountSession session = AccountClient.INSTANCE.getCurrentSession();
        for (NetworkPlayerInfo info : mc.getConnection().getPlayerInfoMap()) {
            UUID id = info.getGameProfile().getId();
            String name = info.getGameProfile().getName();
            if (id.equals(self) || session != null && id.equals(session.accountId())) continue;
            out.add(new Player(id, name));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    private boolean isFriend(Player p) {
        if (friends != null) for (AccountClient.FriendInfo f : friends) {
            if (f.accountId().equals(p.id()) || f.displayName().equalsIgnoreCase(p.name())) return true;
        }
        return false;
    }

    private AccountClient.IncomingRequest incomingFrom(Player p) {
        if (requests != null) for (AccountClient.IncomingRequest r : requests) {
            if (r.fromAccountId().equals(p.id()) || r.displayName().equalsIgnoreCase(p.name())) return r;
        }
        return null;
    }

    @Override
    protected List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        boolean ready = signedIn() && friends != null && requests != null;
        for (Player p : players()) {
            State state = states.get(p.id());
            AccountClient.IncomingRequest incoming = incomingFrom(p);
            if (state == State.ACCEPTED || ready && isFriend(p)) {
                rows.add(new Row(p.name(), false, false, PeerCraftLang.tr("peercraft.ingame.players.friend"), PeerCraftUi.TEXT_SUCCESS, null));
            } else if (state == State.SENT) {
                rows.add(new Row(p.name(), false, false, PeerCraftLang.tr("peercraft.ingame.players.sent"), PeerCraftUi.TEXT_ACCENT, null));
            } else if (state == State.NOT_FOUND) {
                rows.add(new Row(p.name(), false, false, PeerCraftLang.tr("peercraft.ingame.players.not_found"), PeerCraftUi.TEXT_MUTED, null));
            } else if (state == State.AMBIGUOUS) {
                rows.add(new Row(p.name(), false, false, PeerCraftLang.tr("peercraft.ingame.players.ambiguous"), PeerCraftUi.TEXT_MUTED, null));
            } else if (incoming != null) {
                rows.add(new Row(p.name(), false, false, PeerCraftLang.tr("peercraft.ingame.friends.wants_to_add"), PeerCraftUi.TEXT_ACCENT,
                        new RowAction(PeerCraftLang.tr("peercraft.gui.friend_requests.accept"), true,
                                ready && state != State.SENDING, () -> accept(p, incoming))));
            } else {
                rows.add(new Row(p.name(), false, false, "", PeerCraftUi.TEXT_MUTED,
                        new RowAction(PeerCraftLang.tr(state == State.SENDING ? "peercraft.ingame.players.sending" : "peercraft.ingame.players.add"),
                                true, ready && state == null, () -> add(p))));
            }
        }
        if (rows.isEmpty() && signedIn()) setStatus(PeerCraftLang.tr("peercraft.ingame.players.empty"), PeerCraftUi.TEXT_MUTED);
        return rows;
    }

    @Override
    protected List<Footer> footer() {
        List<Footer> footer = new ArrayList<>();
        footer.add(new Footer(PeerCraftLang.tr("peercraft.gui.common.back"), true, true, this::onClose));
        return footer;
    }

    private void accept(Player p, AccountClient.IncomingRequest request) {
        states.put(p.id(), State.SENDING);
        refresh();
        AccountClient.INSTANCE.respondToRequest(request.fromAccountId(), true, new AccountClient.AckCallback() {
            @Override public void onSuccess() { runOnClientThread(() -> { states.put(p.id(), State.ACCEPTED); refresh(); }); }
            @Override public void onFailed(String reason) { runOnClientThread(() -> failed(p, reason)); }
        });
    }

    private void add(Player p) {
        states.put(p.id(), State.SENDING);
        setStatus("", PeerCraftUi.TEXT_MUTED);
        refresh();
        AccountClient.INSTANCE.searchAccounts(p.name(), new AccountClient.SearchCallback() {
            @Override public void onResult(List<AccountClient.SearchResult> results) {
                runOnClientThread(() -> resolved(p, results));
            }
            @Override public void onTimeout() { runOnClientThread(() -> failed(p, PeerCraftLang.tr("peercraft.gui.common.account_server_timeout"))); }
        });
    }

    private void resolved(Player p, List<AccountClient.SearchResult> results) {
        List<AccountClient.SearchResult> exact = new ArrayList<>();
        for (AccountClient.SearchResult r : results) {
            if (r.accountId().equals(p.id())) { send(p, r.accountId()); return; }
            if (r.displayName().toLowerCase(Locale.ROOT).equals(p.name().toLowerCase(Locale.ROOT))) exact.add(r);
        }
        if (exact.size() == 1) send(p, exact.get(0).accountId());
        else if (exact.size() > 1) { states.put(p.id(), State.AMBIGUOUS); refresh(); }
        // Search found nothing (or was rate-limited): the profile UUID itself may be the accountId.
        else send(p, p.id());
    }

    private void send(Player p, UUID accountId) {
        boolean guessed = accountId.equals(p.id());
        AccountClient.INSTANCE.sendFriendRequest(accountId, new AccountClient.AckCallback() {
            @Override public void onSuccess() { runOnClientThread(() -> { states.put(p.id(), State.SENT); refresh(); }); }
            @Override public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (guessed) {
                        states.put(p.id(), State.NOT_FOUND);
                        setStatus(reason, PeerCraftUi.TEXT_MUTED);
                        refresh();
                    }
                    else failed(p, reason);
                });
            }
        });
    }

    private void failed(Player p, String reason) {
        states.remove(p.id());
        setStatus(reason, PeerCraftUi.TEXT_ERROR);
        refresh();
    }
}
