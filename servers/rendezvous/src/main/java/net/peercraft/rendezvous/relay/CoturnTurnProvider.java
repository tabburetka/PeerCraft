package net.peercraft.rendezvous.relay;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** coturn TURN REST credentials: timestamp:opaque-id and Base64(HMAC-SHA1). */
public final class CoturnTurnProvider implements TurnProvider {
    private final byte[] secret;
    private final String url;
    private final InetSocketAddress healthEndpoint;
    private final int limit;
    private final int bulkRate;
    private final LongSupplier clock;
    CoturnTurnProvider(Properties properties, String secret, LongSupplier clock) throws IOException {
        if (secret == null || !secret.matches("[a-fA-F0-9]{64,128}"))
            throw new IOException("Coturn requires a server-only random hex secret");
        this.secret = secret.getBytes(StandardCharsets.UTF_8); this.clock = clock;
        String host = properties.getProperty("coturn.publicHost", "");
        if (!host.matches("[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}") || host.contains(".."))
            throw new IOException("Coturn publicHost must be an IPv4 literal or hostname");
        try {
            int port = Integer.parseInt(properties.getProperty("coturn.port", "3478"));
            limit = Integer.parseInt(properties.getProperty("coturn.maxConnections", "2"));
            bulkRate = Integer.parseInt(properties.getProperty("coturn.bulkBytesPerSecond", "524288"));
            if (bulkRate < 65536 || bulkRate > 524288) throw new IllegalArgumentException();
            if ((port != 3478 && port != 443) || limit < 1 || limit > 4) throw new IllegalArgumentException();
            url = "turn:" + host + ":" + port + "?transport=udp";
            int healthPort = Integer.parseInt(properties.getProperty("coturn.healthPort", Integer.toString(port)));
            if (healthPort < 1 || healthPort > 65535) throw new IllegalArgumentException();
            InetAddress healthHost = InetAddress.getByName(properties.getProperty("coturn.healthHost", "127.0.0.1"));
            if (!healthHost.isLoopbackAddress()) throw new IllegalArgumentException();
            healthEndpoint = new InetSocketAddress(healthHost, healthPort);
        } catch (IllegalArgumentException e) { throw new IOException("Invalid coturn limits or local health endpoint"); }
    }
    @Override public boolean requiresUsageBudget() { return false; }
    @Override public boolean supportsIndividualRevocation() { return false; }
    @Override public int connectionLimit() { return limit; }
    @Override public Credentials issue(int ttlSeconds, String identifier) throws IOException {
        if (ttlSeconds < 1 || ttlSeconds > 900) throw new IOException("Invalid coturn credential lifetime");
        long expirySeconds = Math.addExact(clock.getAsLong() / 1000, ttlSeconds);
        String username = expirySeconds + ":peercraft-" + UUID.randomUUID();
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            String password = Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
            return new Credentials(List.of(url), username, password, Math.multiplyExact(expirySeconds, 1000), bulkRate);
        } catch (GeneralSecurityException e) { throw new IOException("Coturn credential generation unavailable"); }
    }
    @Override public void revoke(String username) throws IOException {
        throw new IOException("Coturn REST credentials require expiry or a whole-service shutdown");
    }
    @Override public Usage usage(Instant start, Instant end) throws IOException {
        throw new IOException("Self-hosted coturn has no Cloudflare billing usage");
    }
    @Override public void checkHealth() throws IOException {
        byte[] transaction = new byte[12]; new SecureRandom().nextBytes(transaction);
        byte[] request = ByteBuffer.allocate(20).putShort((short)1).putShort((short)0)
                .putInt(0x2112a442).put(transaction).array();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(healthEndpoint); socket.setSoTimeout(2000);
            socket.send(new DatagramPacket(request, request.length));
            byte[] response = new byte[1024]; DatagramPacket packet = new DatagramPacket(response, response.length);
            socket.receive(packet);
            if (packet.getLength() < 20) throw new IOException("Coturn health response invalid");
            ByteBuffer header = ByteBuffer.wrap(response, 0, packet.getLength());
            int type = header.getShort() & 0xffff, length = header.getShort() & 0xffff;
            if (type != 0x0101 || header.getInt() != 0x2112a442 || length % 4 != 0 || length + 20 != packet.getLength())
                throw new IOException("Coturn health response invalid");
            byte[] received = new byte[12]; header.get(received);
            if (!java.security.MessageDigest.isEqual(transaction, received)) throw new IOException("Coturn health transaction invalid");
        }
    }
}
