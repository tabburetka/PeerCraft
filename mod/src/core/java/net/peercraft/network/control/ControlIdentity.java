package net.peercraft.network.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

/**
 * The rendezvous server's long-term control-channel key. Stored as a small properties file
 * (private key PKCS#8, public key as the raw point, both base64) and created on first start.
 * Clients pin the public half, so losing this file means shipping a new pin.
 */
public final class ControlIdentity {
    public final KeyPair keyPair;
    public final byte[] publicKey;

    private ControlIdentity(KeyPair keyPair) {
        this.keyPair = keyPair;
        this.publicKey = ControlCrypto.encodePublicKey(keyPair.getPublic());
    }

    public static ControlIdentity generate() {
        return new ControlIdentity(ControlCrypto.generateKeyPair());
    }

    /** Loads the identity at {@code file}, creating it (owner-only where supported) when absent. */
    public static ControlIdentity loadOrCreate(Path file) throws IOException {
        if (Files.exists(file)) return load(file);
        ControlIdentity created = generate();
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, created.serialize().getBytes(StandardCharsets.US_ASCII));
        try {
            Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) { }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        return created;
    }

    public static ControlIdentity load(Path file) throws IOException {
        Properties properties = new Properties();
        List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
        properties.load(new java.io.StringReader(String.join("\n", lines)));
        try {
            byte[] publicKey = Base64.getDecoder().decode(properties.getProperty("public", ""));
            byte[] privateKey = Base64.getDecoder().decode(properties.getProperty("private", ""));
            ControlIdentity identity = new ControlIdentity(new KeyPair(
                    ControlCrypto.decodePublicKey(publicKey), ControlCrypto.decodePrivateKey(privateKey)));
            // A mismatched pair would make every handshake fail confirmation; catch it at startup.
            selfTest(identity);
            return identity;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Control identity " + file + " is corrupt", e);
        }
    }

    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey);
    }

    public String fingerprint() {
        return ControlCrypto.fingerprint(publicKey);
    }

    String serialize() {
        return "# PeerCraft rendezvous control-channel identity. Keep private; clients pin the public key.\n"
                + "public=" + publicKeyBase64() + "\n"
                + "private=" + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()) + "\n";
    }

    private static void selfTest(ControlIdentity identity) throws GeneralSecurityException {
        ControlProtocol.ClientHello hello = new ControlProtocol.ClientHello();
        ControlProtocol.ServerReply reply = ControlProtocol.answer(identity.keyPair, hello.packet(), hello.packet().length);
        if (reply == null) throw new GeneralSecurityException("Key pair does not work");
        ControlProtocol.Established established = ControlProtocol.finishClient(hello, reply.packet, reply.packet.length);
        if (established == null) throw new GeneralSecurityException("Key pair does not work");
    }
}
