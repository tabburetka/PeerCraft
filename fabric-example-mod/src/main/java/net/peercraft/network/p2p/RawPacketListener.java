package net.peercraft.network.p2p;

import java.net.InetAddress;

/**
 * Callback for non-relay (rendezvous/punch) datagrams that {@link P2PBridge} demuxes
 * off the shared socket before they'd ever reach {@link FramedPacket#decode}. Runs on
 * P2PReceiver's processing thread — implementations must not block.
 */
public interface RawPacketListener {
    void onPacket(byte[] data, int length, InetAddress address, int port);
}
