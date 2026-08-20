package net.peercraft.rendezvous.account;

import net.peercraft.rendezvous.AccountProtocol;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Password hashing/verification for unlicensed accounts. Uses only javax.crypto (ships with
 * the JDK) — no external dependency needed. The raw password never travels over the wire
 * after registration: login uses {@link #hmacChallenge} (HMAC-SHA256 over the stored PBKDF2
 * hash) so a passive sniffer of the plaintext UDP channel never sees anything replayable as
 * the password itself, only a challenge-specific HMAC.
 */
final class PasswordHasher {

    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;

    private PasswordHasher() {
    }

    static byte[] randomSalt() {
        byte[] salt = new byte[AccountProtocol.SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    static byte[] hash(char[] password, byte[] salt) {
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

    /** HMAC-SHA256(storedHash, challenge) — both client (after rederiving the hash from the password+salt) and server compute this and compare. */
    static byte[] hmacChallenge(byte[] storedHash, byte[] challenge) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(storedHash, "HmacSHA256"));
            return mac.doFinal(challenge);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}
