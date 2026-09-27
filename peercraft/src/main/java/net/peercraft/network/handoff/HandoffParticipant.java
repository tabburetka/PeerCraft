package net.peercraft.network.handoff;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.UUID;

/** Confirmed participant identity survives replacement of Minecraft TCP and the UDP port. */
public final class HandoffParticipant {
    public enum Role { SOURCE, SUCCESSOR, OBSERVER }
    public final UUID accountId;
    public final Role role;
    private final byte[] controlAuthority;
    private InetSocketAddress endpoint;
    public HandoffParticipant(UUID accountId, Role role, InetSocketAddress endpoint, byte[] controlAuthority) {
        if (role == null || endpoint == null || endpoint.isUnresolved() || endpoint.getPort() == 0
                || controlAuthority == null || controlAuthority.length != 32)
            throw new IllegalArgumentException("Invalid confirmed participant");
        boolean nonzero = false; for (byte b : controlAuthority) nonzero |= b != 0;
        if (!nonzero) throw new IllegalArgumentException("Missing participant authority");
        this.accountId = accountId; this.role = role; this.endpoint = endpoint; this.controlAuthority = controlAuthority.clone();
    }
    public synchronized InetSocketAddress endpoint() { return endpoint; }
    /** Endpoint changes require the operation authority, never merely the same source IP. */
    public synchronized boolean authenticates(byte[] authority, InetSocketAddress sender, boolean allowPortChange) {
        if (authority == null || !MessageDigest.isEqual(controlAuthority, authority)
                || sender == null || sender.isUnresolved() || sender.getPort() == 0) return false;
        if (!endpoint.equals(sender)) {
            if (!allowPortChange) return false;
            endpoint = sender;
        }
        return true;
    }
    public HandoffControlProtocol.Message message(int type, UUID session, long offer, long epoch, byte[] payload) {
        return new HandoffControlProtocol.Message(type, session, offer, epoch, controlAuthority, payload);
    }
}
