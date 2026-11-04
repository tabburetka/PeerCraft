package net.peercraft.network.control;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Primitives of the client↔rendezvous control channel: P-256 ECDH, HKDF-SHA256 and
 * AES-128-GCM, all available on stock Java 8. Public keys travel as the 65-byte uncompressed
 * point; every received point is checked to be on the curve before it is used.
 */
public final class ControlCrypto {
    public static final int PUBLIC_KEY_BYTES = 65;
    public static final int KEY_BYTES = 16;
    public static final int TAG_BYTES = 16;

    // DER prefix of a SubjectPublicKeyInfo for a P-256 uncompressed point.
    private static final byte[] X509_P256_PREFIX = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200");
    private static final BigInteger P = new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    private static final BigInteger B = new BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);
    private static final SecureRandom RANDOM = new SecureRandom();

    private ControlCrypto() { }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is unavailable", e);
        }
    }

    public static byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    /** @return the 65-byte uncompressed point of an EC public key. */
    public static byte[] encodePublicKey(PublicKey key) {
        byte[] der = key.getEncoded();
        if (der.length != X509_P256_PREFIX.length + PUBLIC_KEY_BYTES) throw new IllegalArgumentException("Not a P-256 key");
        return Arrays.copyOfRange(der, X509_P256_PREFIX.length, der.length);
    }

    public static PublicKey decodePublicKey(byte[] point) throws GeneralSecurityException {
        if (point == null || point.length != PUBLIC_KEY_BYTES || point[0] != 0x04) throw new GeneralSecurityException("Bad public key");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(point, 33, 65));
        if (x.compareTo(P) >= 0 || y.compareTo(P) >= 0) throw new GeneralSecurityException("Bad public key");
        // y^2 = x^3 - 3x + b (mod p): rejects invalid-curve points before they reach ECDH.
        BigInteger right = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P);
        if (!y.modPow(BigInteger.valueOf(2), P).equals(right)) throw new GeneralSecurityException("Point is not on P-256");
        byte[] der = Arrays.copyOf(X509_P256_PREFIX, X509_P256_PREFIX.length + PUBLIC_KEY_BYTES);
        System.arraycopy(point, 0, der, X509_P256_PREFIX.length, PUBLIC_KEY_BYTES);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
    }

    public static PrivateKey decodePrivateKey(byte[] pkcs8) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    public static byte[] ecdh(PrivateKey own, PublicKey peer) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(own);
        agreement.doPhase(peer, true);
        return agreement.generateSecret();
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) digest.update(part);
            return digest.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] part : parts) mac.update(part);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RFC 5869 HKDF-SHA256. */
    public static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) {
        byte[] prk = hmac(salt, ikm);
        byte[] out = new byte[length];
        byte[] block = new byte[0];
        int filled = 0;
        for (int counter = 1; filled < length; counter++) {
            block = hmac(prk, block, info, new byte[]{(byte) counter});
            int take = Math.min(block.length, length - filled);
            System.arraycopy(block, 0, out, filled, take);
            filled += take;
        }
        return out;
    }

    /** Encrypts {@code plain} into {@code out} at {@code outOffset}; returns the bytes written. */
    public static int seal(byte[] key, byte[] nonce, byte[] aad, int aadLength,
                           byte[] plain, int plainOffset, int plainLength, byte[] out, int outOffset) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BYTES * 8, nonce));
        cipher.updateAAD(aad, 0, aadLength);
        return cipher.doFinal(plain, plainOffset, plainLength, out, outOffset);
    }

    /** @return the plaintext, or {@code null} if authentication fails. */
    public static byte[] open(byte[] key, byte[] nonce, byte[] aad, int aadLength, byte[] in, int offset, int length) {
        if (length < TAG_BYTES) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(aad, 0, aadLength);
            return cipher.doFinal(in, offset, length);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    /** Short human-readable fingerprint for logs and pin files. */
    public static String fingerprint(byte[] publicKey) {
        byte[] digest = sha256(publicKey);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 2 == 0) out.append(':');
            out.append(String.format("%02x", digest[i] & 0xff));
        }
        return out.toString();
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
