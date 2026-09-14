package net.peercraft.network.handoff;

import net.peercraft.network.rendezvous.RendezvousProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot {@code TYPE_LOOKUP_HOST} poller: asks the rendezvous server "what room is account
 * {@code X} hosting?" every {@link #RETRY_MILLIS} until it gets a non-empty room code or the
 * timeout elapses. Used by a joiner after a handoff MIGRATE to find the successor's new room
 * (whose code has changed).
 *
 * <p>Its own {@link DatagramSocket} — the joiner isn't otherwise talking to the rendezvous
 * server at this point, and its old NAT mapping there has long since closed.
 */
public final class LookupHostClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    private static final long RETRY_MILLIS = 2000L;

    public interface Callback {
        void onFound(String roomCode);

        void onGaveUp();
    }

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile Thread thread;

    public void start(UUID accountId, String rendezvousHost, int rendezvousPort, long timeoutMillis, Callback cb) {
        Thread t = new Thread(() -> run(accountId, rendezvousHost, rendezvousPort, timeoutMillis, cb), "PeerCraft-Handoff-Lookup");
        t.setDaemon(true);
        this.thread = t;
        t.start();
    }

    public void stop() {
        stopped.set(true);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void run(UUID accountId, String host, int port, long timeoutMillis, Callback cb) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout((int) Math.min(RETRY_MILLIS, Math.max(200, timeoutMillis)));
            InetAddress addr = InetAddress.getByName(host);
            byte[] query = RendezvousProtocol.encodeLookupHost(accountId);
            byte[] buf = new byte[512];
            while (!stopped.get() && System.currentTimeMillis() < deadline) {
                socket.send(new DatagramPacket(query, query.length, addr, port));
                try {
                    DatagramPacket reply = new DatagramPacket(buf, buf.length);
                    socket.receive(reply);
                    int type = RendezvousProtocol.messageType(reply.getData(), reply.getLength());
                    if (type == (RendezvousProtocol.TYPE_LOOKUP_HOST_REPLY & 0xFF)) {
                        String code = RendezvousProtocol.decodeLookupHostReply(reply.getData(), reply.getLength());
                        if (code != null && !code.isEmpty()) {
                            if (!stopped.getAndSet(true)) {
                                cb.onFound(code);
                            }
                            return;
                        }
                    }
                } catch (java.net.SocketTimeoutException expected) {
                    // no reply in this window — loop and retry
                }
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            LOGGER.warn("[Handoff] Ошибка поиска нового хоста: {}", e.toString());
        }
        if (!stopped.getAndSet(true)) {
            cb.onGaveUp();
        }
    }
}
