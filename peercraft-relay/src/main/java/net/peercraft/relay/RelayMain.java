package net.peercraft.relay;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import net.peercraft.network.ownrelay.OwnRelayProtocol;

/**
 * {@code java -jar peercraft-relay.jar [--config relay.properties] [--rotate-secret] [--public-host HOST]}
 * <p>Prints the relay code a player pastes into PeerCraft settings, then forwards sessions.
 */
public final class RelayMain {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private RelayMain() {}

    public static void main(String[] args) throws Exception {
        String configPath = System.getenv().getOrDefault("PEERCRAFT_RELAY_CONFIG", "relay.properties");
        boolean rotate = false;
        String hostOverride = System.getenv().getOrDefault("PEERCRAFT_RELAY_PUBLIC_HOST", "");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = value(args, ++i);
                case "--rotate-secret" -> rotate = true;
                case "--public-host" -> hostOverride = value(args, ++i);
                case "--help", "-h" -> { usage(); return; }
                default -> { System.err.println("Unknown option: " + args[i]); usage(); System.exit(2); }
            }
        }
        Path file = Paths.get(configPath);
        RelayConfig config = RelayConfig.loadOrCreate(file, rotate);
        if (rotate) log("A new secret was generated. Old relay codes no longer work.");

        DatagramSocket socket;
        try { socket = new DatagramSocket(config.port); }
        catch (IOException busy) {
            log("Cannot listen on UDP port " + config.port + " (" + busy.getMessage() + "). Is another relay running?");
            System.exit(1); return;
        }
        String host = !hostOverride.isBlank() ? hostOverride.trim() : config.publicHost;
        if (host.isEmpty()) {
            InetAddress detected = detectPublicAddress();
            host = detected != null ? detected.getHostAddress() : "YOUR-PUBLIC-IP";
            if (detected == null) log("Could not detect the public IP. Set publicHost in " + file.toAbsolutePath());
        }
        String code = OwnRelayProtocol.formatCode(host, config.port, config.secret);
        System.out.println();
        System.out.println("  PeerCraft relay is running on UDP port " + config.port);
        System.out.println("  Relay code (PeerCraft -> Settings -> Own relay):");
        System.out.println();
        System.out.println("    " + code);
        System.out.println();
        System.out.println("  Keep this code private. Players also need UDP port " + config.port
                + " reachable from the internet (router port forwarding or a VPS firewall rule).");
        System.out.println("  Settings: " + file.toAbsolutePath());
        System.out.println();

        Runtime.getRuntime().addShutdownHook(new Thread(socket::close, "relay-shutdown"));
        RelayServer.serve(config, socket, RelayMain::log);
    }

    private static String value(String[] args, int index) {
        if (index >= args.length) { usage(); System.exit(2); }
        return args[index];
    }

    private static void usage() {
        System.out.println("Usage: java -jar peercraft-relay.jar [--config FILE] [--rotate-secret] [--public-host HOST]");
    }

    static void log(String message) {
        System.out.println("[" + LocalDateTime.now().format(TIME) + "] " + message);
    }

    /** One STUN binding request (RFC 5389) to learn this machine's public IPv4 address. */
    static InetAddress detectPublicAddress() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(1500);
            InetSocketAddress server = new InetSocketAddress(InetAddress.getByName("stun.cloudflare.com"), 3478);
            byte[] transaction = new byte[12];
            new SecureRandom().nextBytes(transaction);
            ByteBuffer request = ByteBuffer.allocate(20).putShort((short) 0x0001).putShort((short) 0).putInt(0x2112A442).put(transaction);
            byte[] buffer = new byte[512];
            for (int attempt = 0; attempt < 3; attempt++) {
                socket.send(new DatagramPacket(request.array(), 20, server));
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try { socket.receive(reply); } catch (java.net.SocketTimeoutException retry) { continue; }
                InetAddress address = parseMappedAddress(buffer, reply.getLength(), transaction);
                if (address != null) return address;
            }
        } catch (IOException | RuntimeException unavailable) {
            // Offline or blocked: the caller asks the user to configure publicHost.
        }
        return null;
    }

    static InetAddress parseMappedAddress(byte[] bytes, int length, byte[] transaction) throws IOException {
        if (length < 20) return null;
        ByteBuffer b = ByteBuffer.wrap(bytes, 0, length);
        if (b.getShort() != 0x0101) return null;
        int bodyLength = b.getShort() & 0xffff;
        if (b.getInt() != 0x2112A442) return null;
        byte[] id = new byte[12]; b.get(id);
        if (!Arrays.equals(id, transaction) || 20 + bodyLength > length) return null;
        while (b.remaining() >= 4) {
            int type = b.getShort() & 0xffff, size = b.getShort() & 0xffff;
            if (size > b.remaining()) return null;
            int start = b.position();
            if (type == 0x0020 && size >= 8 && bytes[start + 1] == 0x01) {
                byte[] ip = new byte[4];
                for (int i = 0; i < 4; i++) ip[i] = (byte) (bytes[start + 4 + i] ^ (0x2112A442 >>> (24 - 8 * i)));
                return InetAddress.getByAddress(ip);
            }
            b.position(start + ((size + 3) & ~3));
        }
        return null;
    }
}
