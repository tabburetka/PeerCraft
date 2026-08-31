package net.peercraft.network.modsync;

import net.peercraft.network.p2p.RawPacketListener;

/**
 * The joiner's view of the punched peer-to-peer link, handed to {@link ModSyncAgent#run} by
 * {@code P2PBridge} once the NAT punch succeeds: a way to push {@link ModSyncProtocol}
 * datagrams to the host and to register the listener {@code P2PBridge} routes the host's
 * {@code 0xE2} datagrams to. Keeps the client layer from importing {@code P2PBridge} internals.
 */
public interface ModSyncLink {

    /** Send one mod-sync datagram to the host over the tunnel. */
    void send(byte[] data);

    /** Route inbound {@code 0xE2} datagrams from the host to {@code listener} until {@link #unbind()}. */
    void bindInbound(RawPacketListener listener);

    /** Stop routing inbound datagrams and cancel the bound listener. */
    void unbind();
}
