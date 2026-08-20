package net.peercraft.rendezvous.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

    @Test
    void hashIsDeterministicForSamePasswordAndSalt() {
        byte[] salt = PasswordHasher.randomSalt();
        byte[] a = PasswordHasher.hash("hunter2".toCharArray(), salt);
        byte[] b = PasswordHasher.hash("hunter2".toCharArray(), salt);
        assertArrayEquals(a, b);
    }

    @Test
    void differentSaltsProduceDifferentHashesForSamePassword() {
        byte[] saltA = PasswordHasher.randomSalt();
        byte[] saltB = PasswordHasher.randomSalt();
        byte[] a = PasswordHasher.hash("hunter2".toCharArray(), saltA);
        byte[] b = PasswordHasher.hash("hunter2".toCharArray(), saltB);
        assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    void differentPasswordsProduceDifferentHashesForSameSalt() {
        byte[] salt = PasswordHasher.randomSalt();
        byte[] a = PasswordHasher.hash("hunter2".toCharArray(), salt);
        byte[] b = PasswordHasher.hash("hunter3".toCharArray(), salt);
        assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    void clientCanRederiveTheSameHashFromPasswordAndSaltAloneOnANewDevice() {
        // This is the whole point of shipping salt in TYPE_ACCOUNT_LOGIN_CHALLENGE — a login
        // from a device that never saw this account before must still be able to answer the
        // challenge correctly, using only the password it was just typed and the salt the
        // server just sent.
        byte[] salt = PasswordHasher.randomSalt();
        byte[] registeredHash = PasswordHasher.hash("hunter2".toCharArray(), salt);

        byte[] rederivedOnNewDevice = PasswordHasher.hash("hunter2".toCharArray(), salt);

        assertArrayEquals(registeredHash, rederivedOnNewDevice);
    }

    @Test
    void hmacChallengeMatchesWhenBothSidesUseTheSameStoredHash() {
        byte[] salt = PasswordHasher.randomSalt();
        byte[] storedHash = PasswordHasher.hash("hunter2".toCharArray(), salt);
        byte[] challenge = "some-random-16-byte".getBytes();

        byte[] serverSide = PasswordHasher.hmacChallenge(storedHash, challenge);
        byte[] clientSide = PasswordHasher.hmacChallenge(storedHash, challenge);

        assertArrayEquals(serverSide, clientSide);
        assertTrue(PasswordHasher.constantTimeEquals(serverSide, clientSide));
    }

    @Test
    void hmacChallengeDiffersForDifferentChallenges() {
        // Guards the replay-resistance property: the same stored hash must never produce the
        // same response for two different login attempts, otherwise a sniffed response could
        // be replayed against a future challenge.
        byte[] salt = PasswordHasher.randomSalt();
        byte[] storedHash = PasswordHasher.hash("hunter2".toCharArray(), salt);

        byte[] responseA = PasswordHasher.hmacChallenge(storedHash, "challenge-one-16b".getBytes());
        byte[] responseB = PasswordHasher.hmacChallenge(storedHash, "challenge-two-16b".getBytes());

        assertFalse(java.util.Arrays.equals(responseA, responseB));
    }

    @Test
    void pinnedTestVectorMatchesModCopy() {
        // Pins an exact (password, salt) -> hash -> HMAC chain so any accidental drift in
        // algorithm/iteration-count/key-length between this copy and the mod's duplicated
        // PasswordCrypto.java is caught immediately — same convention as the protocol's
        // fixedEncodingMatchesModCopy tests. Values computed once via a standalone jshell
        // PBKDF2WithHmacSHA256 run, not derived from this class, so both copies are checked
        // independently against the same ground truth.
        byte[] salt = fill(16, 0x01);
        byte[] hash = PasswordHasher.hash("correct horse battery staple".toCharArray(), salt);
        assertEquals("7a8b6fb373581fdc4071d5f9e9c327613a9b5d8ff3106258411bdff7662e5503", toHex(hash));

        byte[] challenge = fill(16, 0x02);
        byte[] hmac = PasswordHasher.hmacChallenge(hash, challenge);
        assertEquals("ebbff796e3c6c2732f316bc5dd8f4d3683210b0565ef346675235c3a8ee2a49e", toHex(hmac));
    }

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
    void wrongPasswordProducesAHashThatFailsTheChallenge() {
        byte[] salt = PasswordHasher.randomSalt();
        byte[] storedHash = PasswordHasher.hash("hunter2".toCharArray(), salt);
        byte[] challenge = "some-random-16-byte".getBytes();
        byte[] expectedResponse = PasswordHasher.hmacChallenge(storedHash, challenge);

        byte[] wrongHash = PasswordHasher.hash("wrongpassword".toCharArray(), salt);
        byte[] attackerResponse = PasswordHasher.hmacChallenge(wrongHash, challenge);

        assertFalse(PasswordHasher.constantTimeEquals(expectedResponse, attackerResponse));
    }
}
