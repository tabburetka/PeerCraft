package net.peercraft.client.update;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Checks {@code update.json.sig}: ECDSA P-256 / SHA-256 (DER, base64) over the exact bytes of
 * {@code update.json}, made by {@code tools/release/make-update-manifest.py}. ECDSA rather than
 * Ed25519 because Ed25519 is missing on the Java 8 clients. Only a verified manifest may offer
 * a download; the plain notice never depends on it.
 */
public final class UpdateSignature {
    /**
     * Release public keys (X.509 SubjectPublicKeyInfo, base64). When rotating, ship the old and
     * the new key together for a couple of releases before dropping the old one.
     */
    static final String[] PUBLIC_KEYS = {
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0WsWxCO7iBurtT/mqG1ZIevd2etotfaep2wfQR4bdoBhuxG5ijEc0SVWibRrNKc9oF6vJxSeNbZtMq/QQjg2JQ==",
    };

    private UpdateSignature() {
    }

    public static boolean verify(byte[] body, String signatureText) {
        return verify(body, signatureText, PUBLIC_KEYS);
    }

    static boolean verify(byte[] body, String signatureText, String[] keys) {
        if (body == null || signatureText == null) return false;
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureText.trim().getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (String key : keys) {
            try {
                PublicKey publicKey = KeyFactory.getInstance("EC")
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key)));
                Signature verifier = Signature.getInstance("SHA256withECDSA");
                verifier.initVerify(publicKey);
                verifier.update(body);
                if (verifier.verify(signature)) return true;
            } catch (Exception ignored) {
                // A malformed signature or a JVM without SunEC must never become "trusted".
            }
        }
        return false;
    }
}
