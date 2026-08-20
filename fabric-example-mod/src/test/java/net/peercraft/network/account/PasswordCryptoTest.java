package net.peercraft.network.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordCryptoTest {

    private static byte[] fill(int n, int value) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) value);
        return b;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void hashIsDeterministicForSamePasswordAndSalt() {
        byte[] salt = PasswordCrypto.randomSalt();
        byte[] a = PasswordCrypto.hash("hunter2".toCharArray(), salt);
        byte[] b = PasswordCrypto.hash("hunter2".toCharArray(), salt);
        assertArrayEquals(a, b);
    }

    @Test
    void hmacChallengeMatchesWhenBothSidesUseTheSameStoredHash() {
        byte[] salt = PasswordCrypto.randomSalt();
        byte[] hash = PasswordCrypto.hash("hunter2".toCharArray(), salt);
        byte[] challenge = "some-random-16-byte".getBytes();

        assertArrayEquals(PasswordCrypto.hmacChallenge(hash, challenge), PasswordCrypto.hmacChallenge(hash, challenge));
    }

    @Test
    void pinnedTestVectorMatchesServerCopy() {
        // Same test vector as PasswordHasherTest.pinnedTestVectorMatchesModCopy on the server
        // side — computed once via a standalone jshell PBKDF2WithHmacSHA256 run, checked here
        // independently. If this ever fails while the server's copy still passes, the two
        // PasswordHasher/PasswordCrypto copies have drifted out of parameter sync (iteration
        // count, key length, algorithm) and logins from real clients will silently fail.
        byte[] salt = fill(16, 0x01);
        byte[] hash = PasswordCrypto.hash("correct horse battery staple".toCharArray(), salt);
        assertEquals("7a8b6fb373581fdc4071d5f9e9c327613a9b5d8ff3106258411bdff7662e5503", toHex(hash));

        byte[] challenge = fill(16, 0x02);
        byte[] hmac = PasswordCrypto.hmacChallenge(hash, challenge);
        assertEquals("ebbff796e3c6c2732f316bc5dd8f4d3683210b0565ef346675235c3a8ee2a49e", toHex(hmac));
    }
}
