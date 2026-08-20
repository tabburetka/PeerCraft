package net.peercraft.network.account;

import net.peercraft.network.rendezvous.AccountProtocol;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Client-side mirror of the rendezvous-server's {@code PasswordHasher} — the two projects
 * share no code (separate Gradle builds, separate jars), so this is duplicated exactly like
 * the wire protocol is. MUST stay parameter-for-parameter identical to the server copy
 * (algorithm, iteration count, key length) or a client's locally-recomputed hash will never
 * match what the server stored at registration time.
 *
 * The raw password is only ever used locally to derive a hash — it never crosses the network,
 * neither at registration (client sends salt+hash) nor at login (client answers a
 * server-issued challenge with HMAC(hash, challenge), never the hash or password itself).
 */
public final class PasswordCrypto {

    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;

    private PasswordCrypto() {
    }

    public static byte[] randomSalt() {
        byte[] salt = new byte[AccountProtocol.SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    public static byte[] hash(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS);
            try {
                SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
                return factory.generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 hashing failed", e);
        }
    }

    public static byte[] hmacChallenge(byte[] storedHash, byte[] challenge) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(storedHash, "HmacSHA256"));
            return mac.doFinal(challenge);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }
}
