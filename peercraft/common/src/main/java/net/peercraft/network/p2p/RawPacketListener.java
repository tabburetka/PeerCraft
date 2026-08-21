package net.peercraft.network.p2p;

import java.net.InetAddress;

/**
 * Callback for non-relay (rendezvous/punch) datagrams that {@link P2PBridge} demuxes
 * off the shared socket before they'd ever reach {@link FramedPacket#decode}. Runs on
 * P2PReceiver's processing thread — implementations must not block.
 */
public interface RawPacketListener {
    void onPacket(byte[] data, int length, InetAddress address, int port);

    // Stops any background threads owned by this listener (retry/keepalive) without invoking
    // its callbacks — for the case where it's being silently replaced by a new one (P2PBridge
    // started a new attempt) or is simply no longer needed (the host's world closed), as opposed
    // to the attempt actually failing. Without this, for example, RendezvousClient's keepalive
    // thread would end up an orphaned daemon thread that keeps sending REGISTER forever.
    void cancel();
}
