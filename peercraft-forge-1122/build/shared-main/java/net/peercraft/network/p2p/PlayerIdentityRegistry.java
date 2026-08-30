package net.peercraft.network.p2p;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HOST-side only (Phase 5): maps the local loopback port a {@code HostConnection}'s TCP socket
 * to the host's {@code IntegratedServer} was opened FROM to the joiner's PeerCraft accountId,
 * when known. {@code ServerLoginNetworkHandlerMixin} consults this by the remote port it sees
 * on the accepted connection — on loopback, a socket's local port (recorded here, right after
 * {@code P2PBridge.startNewHostConnection} opens it) is exactly the port the server sees as
 * that connection's remote port. This is what lets the host override a joiner's offline-mode
 * UUID with their real PeerCraft accountId instead of the vanilla `OfflinePlayer:<nick>` hash
 * — see the accounts+friends plan's Phase 5 for the full mechanism and why two joiners sharing
 * a nickname would otherwise collide into the same playerdata file.
 *
 * Anonymous joiners (no PeerCraft account) simply never get an entry here — {@code
 * ServerLoginNetworkHandlerMixin} leaves vanilla's own UUID assignment untouched when a lookup
 * misses, so this is purely additive, never a regression for hosts/joiners not using accounts.
 */
public final class PlayerIdentityRegistry {

    public static final PlayerIdentityRegistry INSTANCE = new PlayerIdentityRegistry();

    private final Map<Integer, UUID> accountIdByLocalPort = new ConcurrentHashMap<>();

    private PlayerIdentityRegistry() {
    }

    public void put(int localPort, UUID accountId) {
        accountIdByLocalPort.put(localPort, accountId);
    }

    public UUID get(int remotePort) {
        return accountIdByLocalPort.get(remotePort);
    }

    public void remove(int localPort) {
        accountIdByLocalPort.remove(localPort);
    }
}
