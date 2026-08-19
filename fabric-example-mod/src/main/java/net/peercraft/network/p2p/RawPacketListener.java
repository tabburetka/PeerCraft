package net.peercraft.network.p2p;

import java.net.InetAddress;

/**
 * Callback for non-relay (rendezvous/punch) datagrams that {@link P2PBridge} demuxes
 * off the shared socket before they'd ever reach {@link FramedPacket#decode}. Runs on
 * P2PReceiver's processing thread — implementations must not block.
 */
public interface RawPacketListener {
    void onPacket(byte[] data, int length, InetAddress address, int port);

    // Останавливает любые фоновые потоки этого слушателя (retry/keepalive) без вызова
    // его callback'ов — для случая, когда его тихо заменяют новым (P2PBridge начал
    // новую попытку) или он больше не нужен (мир хоста закрыт), а не когда попытка
    // реально провалилась. Без этого, например, keepalive-поток RendezvousClient
    // остаётся daemon-потоком без владельца и продолжает слать REGISTER вечно.
    void cancel();
}
