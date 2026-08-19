package net.peercraft.rendezvous;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Minimal UDP rendezvous server: lets a Minecraft host publish a short room code and
 * a joiner exchange it for the host's server-observed public address (and vice versa),
 * so both sides can attempt UDP hole punching directly with each other. The server's
 * job ends there — it never sees or relays any actual game traffic.
 */
public final class RendezvousServer {

    private static final int DEFAULT_PORT = 51000;
    // Every real message here is well under 32 bytes; keeping the receive buffer this
    // small doubles as abuse protection (nothing large enough to be useful for a UDP
    // reflection/amplification attack could ever be a valid request here).
    private static final int MAX_DATAGRAM_SIZE = 512;
    private static final long SWEEP_INTERVAL_SECONDS = 30;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        new RendezvousServer(port).run();
    }

    private final int port;
    private final RoomRegistry registry = new RoomRegistry();
    private volatile DatagramSocket socket;

    RendezvousServer(int port) {
        this.port = port;
    }

    /** Bound listening port, or 0 if not started yet — mainly useful for tests that bind to port 0. */
    int getBoundPort() {
        DatagramSocket s = socket;
        return (s != null && !s.isClosed()) ? s.getLocalPort() : 0;
    }

    void run() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            this.socket = socket;
            log("Listening on UDP port " + socket.getLocalPort());

            ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rendezvous-sweep");
                t.setDaemon(true);
                return t;
            });
            sweeper.scheduleAtFixedRate(registry::sweepExpired, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);

            byte[] buffer = new byte[MAX_DATAGRAM_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (true) {
                try {
                    packet.setLength(buffer.length);
                    socket.receive(packet);
                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
                    handle(socket, data, packet.getAddress(), packet.getPort());
                } catch (Exception e) {
                    InetAddress fromAddr = packet.getAddress();
                    String from = fromAddr != null ? fromAddr.getHostAddress() + ":" + packet.getPort() : "unknown sender";
                    logErr("Error handling packet from " + from + ": " + e);
                    e.printStackTrace();
                }
            }
        }
    }

    private void handle(DatagramSocket socket, byte[] data, InetAddress fromAddr, int fromPort) throws IOException {
        int type = RendezvousProtocol.messageType(data, data.length);
        if (type < 0) {
            return; // not our magic byte, or too short — silently ignore
        }

        RendezvousProtocol.Address from = new RendezvousProtocol.Address(fromAddr, fromPort);

        switch (type) {
            case RendezvousProtocol.TYPE_REGISTER -> handleRegister(socket, from);
            case RendezvousProtocol.TYPE_JOIN -> handleJoin(socket, data, from);
            default -> { /* PUNCH/PUNCH_ACK are peer-to-peer only; replies never arrive here — ignore anything else */ }
        }
    }

    private void handleRegister(DatagramSocket socket, RendezvousProtocol.Address from) throws IOException {
        RoomRegistry.RegisterResult result = registry.register(from);
        if (result instanceof RoomRegistry.Registered registered) {
            send(socket, RendezvousProtocol.encodeRoomCreated(registered.code(), from), from);
            if (registered.reused()) {
                log("REGISTER from " + describe(from) + " -> existing room " + registered.code() + " (idempotent resend/keepalive)");
            } else {
                log("REGISTER from " + describe(from) + " -> new room " + registered.code());
            }
        } else {
            RoomRegistry.RegisterRejected rejected = (RoomRegistry.RegisterRejected) result;
            send(socket, RendezvousProtocol.encodeJoinFail(rejected.reason()), from);
            log("REGISTER from " + describe(from) + " -> rejected (reason=" + rejected.reason() + ")");
        }
    }

    private void handleJoin(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        RendezvousProtocol.Join join = RendezvousProtocol.decodeJoin(data, data.length);
        RoomRegistry.JoinResult result = registry.join(join.code(), from);
        if (result instanceof RoomRegistry.Matched matched) {
            send(socket, RendezvousProtocol.encodePeerFound(matched.joinerAddress(), matched.token()), matched.hostAddress());
            send(socket, RendezvousProtocol.encodePeerFound(matched.hostAddress(), matched.token()), matched.joinerAddress());
            log("Room " + join.code() + " matched: " + describe(matched.hostAddress()) + " <-> " + describe(matched.joinerAddress()));
        } else {
            RoomRegistry.JoinRejected rejected = (RoomRegistry.JoinRejected) result;
            send(socket, RendezvousProtocol.encodeJoinFail(rejected.reason()), from);
            log("JOIN " + join.code() + " from " + describe(from) + " -> rejected (reason=" + rejected.reason() + ")");
            if (rejected.reason() == RendezvousProtocol.REASON_INVALID_CODE) {
                // Dump everything the server currently knows about — the most useful moment to
                // see this is exactly when a code that "should" exist doesn't, e.g. because the
                // host's NAT silently re-mapped its port between REGISTER and a later keepalive
                // and orphaned the original room's code (see RoomRegistry.register()'s comment).
                java.util.List<String> rooms = registry.describeAllRooms();
                log("  known rooms (" + rooms.size() + "): " + (rooms.isEmpty() ? "(none)" : String.join(", ", rooms)));
            }
        }
    }

    private static void send(DatagramSocket socket, byte[] data, RendezvousProtocol.Address to) throws IOException {
        socket.send(new DatagramPacket(data, data.length, to.host(), to.port()));
    }

    private static String describe(RendezvousProtocol.Address address) {
        return address.host().getHostAddress() + ":" + address.port();
    }

    private static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME_FORMAT) + "] [RendezvousServer] " + message);
    }

    private static void logErr(String message) {
        System.err.println("[" + LocalTime.now().format(TIME_FORMAT) + "] [RendezvousServer] " + message);
    }
}
