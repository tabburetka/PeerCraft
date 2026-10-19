package net.peercraft.uitest;

import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.network.p2p.P2PSender;
import net.peercraft.network.rendezvous.RendezvousClient;

final class SessionHostFixture {
    private Object client;
    private boolean host;
    void enable() throws Exception {
        java.lang.reflect.Field h = P2PBridge.class.getDeclaredField("isHost"); h.setAccessible(true);
        java.lang.reflect.Field c = P2PBridge.class.getDeclaredField("hostRendezvousClient"); c.setAccessible(true);
        host = h.getBoolean(P2PBridge.INSTANCE); client = c.get(P2PBridge.INSTANCE);
        h.setBoolean(P2PBridge.INSTANCE, true);
        c.set(P2PBridge.INSTANCE, new RendezvousClient(new P2PSender(null), java.net.InetAddress.getLoopbackAddress(), 1));
    }
    void restore() throws Exception {
        java.lang.reflect.Field h = P2PBridge.class.getDeclaredField("isHost"); h.setAccessible(true); h.setBoolean(P2PBridge.INSTANCE, host);
        java.lang.reflect.Field c = P2PBridge.class.getDeclaredField("hostRendezvousClient"); c.setAccessible(true); c.set(P2PBridge.INSTANCE, client);
    }
}
