package net.peercraft.network.ownrelay;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;

/** Relay code resolution and the settings screen's "Check" button. Blocking: never call on the game thread. */
public final class OwnRelayClient {
    private OwnRelayClient() {}

    /** Outcome of {@link #ping}; {@code reason} is a {@code peercraft.gui.relay.check.*} suffix. */
    public static final class PingResult {
        public final boolean ok;
        public final long millis;
        public final String reason;
        PingResult(boolean ok, long millis, String reason) { this.ok = ok; this.millis = millis; this.reason = reason; }
    }

    /** Prefers IPv4: the P2P socket and most home routers use it. */
    public static InetSocketAddress resolve(OwnRelayProtocol.Code code) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(code.host);
        InetAddress chosen = null;
        for (InetAddress address : addresses) if (address instanceof Inet4Address) { chosen = address; break; }
        if (chosen == null && addresses.length > 0) chosen = addresses[0];
        if (chosen == null) throw new IOException("Relay host has no address");
        return new InetSocketAddress(chosen, code.port);
    }

    public static PingResult ping(String codeText) {
        OwnRelayProtocol.Code code = OwnRelayProtocol.parseCode(codeText);
        if (code == null) return new PingResult(false, 0, "invalid_code");
        InetSocketAddress relay;
        try { relay = resolve(code); }
        catch (IOException unknownHost) { return new PingResult(false, 0, "unknown_host"); }
        byte[] secret = code.secret();
        byte[] nonce = new byte[OwnRelayProtocol.NONCE_BYTES];
        new SecureRandom().nextBytes(nonce);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(1000);
            byte[] buffer = new byte[256];
            for (int attempt = 0; attempt < 3; attempt++) {
                long started = System.nanoTime();
                byte[] request = OwnRelayProtocol.encodeAuth(OwnRelayProtocol.PING, secret, nonce, System.currentTimeMillis());
                socket.send(new DatagramPacket(request, request.length, relay));
                long deadline = started + 1_000_000_000L;
                while (System.nanoTime() < deadline) {
                    DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                    try { socket.receive(reply); } catch (SocketTimeoutException timeout) { break; }
                    if (!relay.equals(reply.getSocketAddress())) continue;
                    if (OwnRelayProtocol.verifyPong(buffer, reply.getLength(), secret, nonce))
                        return new PingResult(true, (System.nanoTime() - started) / 1_000_000L, "ok");
                    int reason = OwnRelayProtocol.refusedReason(buffer, reply.getLength(), nonce);
                    if (reason == OwnRelayProtocol.REASON_STALE) return new PingResult(false, 0, "clock");
                    if (reason >= 0) return new PingResult(false, 0, "auth");
                }
            }
            return new PingResult(false, 0, "no_answer");
        } catch (IOException unavailable) {
            return new PingResult(false, 0, "no_answer");
        }
    }
}
