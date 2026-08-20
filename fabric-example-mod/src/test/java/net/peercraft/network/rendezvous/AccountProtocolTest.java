package net.peercraft.network.rendezvous;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccountProtocolTest {

    private static byte[] fill(int n, int value) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) value;
        }
        return b;
    }

    @Test
    void licensedBeginRoundTrips() {
        byte[] encoded = AccountProtocol.encodeLicensedBegin("Steve");
        AccountProtocol.LicensedBegin decoded = AccountProtocol.decodeLicensedBegin(encoded, encoded.length);
        assertEquals("Steve", decoded.username());
    }

    @Test
    void serverIdChallengeRoundTrips() {
        byte[] encoded = AccountProtocol.encodeServerIdChallenge(123456789L, "someServerId");
        AccountProtocol.ServerIdChallenge decoded = AccountProtocol.decodeServerIdChallenge(encoded, encoded.length);
        assertEquals(123456789L, decoded.requestId());
        assertEquals("someServerId", decoded.serverId());
    }

    @Test
    void licensedConfirmRoundTrips() {
        byte[] encoded = AccountProtocol.encodeLicensedConfirm(42L);
        AccountProtocol.LicensedConfirm decoded = AccountProtocol.decodeLicensedConfirm(encoded, encoded.length);
        assertEquals(42L, decoded.requestId());
    }

    @Test
    void accountRegisterRoundTrips() {
        byte[] salt = fill(AccountProtocol.SALT_LENGTH, 0x11);
        byte[] hash = fill(AccountProtocol.PASSWORD_HASH_LENGTH, 0x22);
        byte[] encoded = AccountProtocol.encodeAccountRegister("Pirate", salt, hash);
        AccountProtocol.AccountRegister decoded = AccountProtocol.decodeAccountRegister(encoded, encoded.length);
        assertEquals("Pirate", decoded.username());
        assertArrayEquals(salt, decoded.salt());
        assertArrayEquals(hash, decoded.passwordHash());
    }

    @Test
    void loginBeginByAccountIdRoundTrips() {
        UUID accountId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        byte[] encoded = AccountProtocol.encodeLoginBeginByAccountId(accountId);
        AccountProtocol.LoginBegin decoded = AccountProtocol.decodeLoginBegin(encoded, encoded.length);
        assertFalse(decoded.byFriendCode());
        assertEquals(accountId, decoded.accountId());
        assertNull(decoded.friendCode());
    }

    @Test
    void loginBeginByFriendCodeRoundTrips() {
        byte[] encoded = AccountProtocol.encodeLoginBeginByFriendCode("ABCDEF");
        AccountProtocol.LoginBegin decoded = AccountProtocol.decodeLoginBegin(encoded, encoded.length);
        assertTrue(decoded.byFriendCode());
        assertEquals("ABCDEF", decoded.friendCode());
        assertNull(decoded.accountId());
    }

    @Test
    void loginChallengeRoundTrips() {
        byte[] salt = fill(AccountProtocol.SALT_LENGTH, 0x99);
        byte[] challenge = fill(AccountProtocol.CHALLENGE_LENGTH, 0x33);
        byte[] encoded = AccountProtocol.encodeLoginChallenge(7L, salt, challenge);
        AccountProtocol.LoginChallenge decoded = AccountProtocol.decodeLoginChallenge(encoded, encoded.length);
        assertEquals(7L, decoded.requestId());
        assertArrayEquals(salt, decoded.salt());
        assertArrayEquals(challenge, decoded.challenge());
    }

    @Test
    void loginResponseRoundTrips() {
        byte[] hmac = fill(AccountProtocol.HMAC_LENGTH, 0x44);
        byte[] encoded = AccountProtocol.encodeLoginResponse(8L, hmac);
        AccountProtocol.LoginResponse decoded = AccountProtocol.decodeLoginResponse(encoded, encoded.length);
        assertEquals(8L, decoded.requestId());
        assertArrayEquals(hmac, decoded.hmac());
    }

    @Test
    void loginRememberRoundTrips() {
        UUID accountId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        byte[] rememberToken = fill(AccountProtocol.TOKEN_LENGTH, 0x55);
        byte[] encoded = AccountProtocol.encodeLoginRemember(accountId, rememberToken);
        AccountProtocol.LoginRemember decoded = AccountProtocol.decodeLoginRemember(encoded, encoded.length);
        assertEquals(accountId, decoded.accountId());
        assertArrayEquals(rememberToken, decoded.rememberToken());
    }

    @Test
    void authOkRoundTrips() {
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000007");
        byte[] sessionToken = fill(AccountProtocol.TOKEN_LENGTH, 0x66);
        byte[] rememberToken = fill(AccountProtocol.TOKEN_LENGTH, 0x77);
        byte[] encoded = AccountProtocol.encodeAuthOk(accountId, sessionToken, rememberToken, true, "ABCDEF", "Hi");
        AccountProtocol.AuthOk decoded = AccountProtocol.decodeAuthOk(encoded, encoded.length);
        assertEquals(accountId, decoded.accountId());
        assertArrayEquals(sessionToken, decoded.sessionToken());
        assertArrayEquals(rememberToken, decoded.rememberToken());
        assertTrue(decoded.licensed());
        assertEquals("ABCDEF", decoded.friendCode());
        assertEquals("Hi", decoded.displayName());
    }

    @Test
    void authFailRoundTrips() {
        byte[] encoded = AccountProtocol.encodeAuthFail(9L, AccountProtocol.REASON_BAD_CREDENTIALS);
        AccountProtocol.AuthFail decoded = AccountProtocol.decodeAuthFail(encoded, encoded.length);
        assertEquals(9L, decoded.requestId());
        assertEquals(AccountProtocol.REASON_BAD_CREDENTIALS, decoded.reason());
    }

    @Test
    void accountRenameRoundTrips() {
        byte[] sessionToken = fill(AccountProtocol.TOKEN_LENGTH, 0x88);
        byte[] encoded = AccountProtocol.encodeAccountRename(sessionToken, "NewNick");
        AccountProtocol.AccountRename decoded = AccountProtocol.decodeAccountRename(encoded, encoded.length);
        assertArrayEquals(sessionToken, decoded.sessionToken());
        assertEquals("NewNick", decoded.newName());
    }

    @Test
    void renameAckRoundTrips() {
        byte[] encoded = AccountProtocol.encodeRenameAck(true, (byte) 0, "NewNick");
        AccountProtocol.RenameAck decoded = AccountProtocol.decodeRenameAck(encoded, encoded.length);
        assertTrue(decoded.ok());
        assertEquals((byte) 0, decoded.reason());
        assertEquals("NewNick", decoded.appliedName());
    }

    // ---- friends (Фаза 3) ----

    @Test
    void friendCodeLookupRoundTrips() {
        byte[] sessionToken = fill(AccountProtocol.TOKEN_LENGTH, 0xAA);
        byte[] encoded = AccountProtocol.encodeFriendCodeLookup(sessionToken, "ABCDEF");
        AccountProtocol.FriendCodeLookup decoded = AccountProtocol.decodeFriendCodeLookup(encoded, encoded.length);
        assertArrayEquals(sessionToken, decoded.sessionToken());
        assertEquals("ABCDEF", decoded.friendCode());
    }

    @Test
    void friendCodeLookupReplyRoundTripsWhenFound() {
        UUID accountId = UUID.randomUUID();
        byte[] encoded = AccountProtocol.encodeFriendCodeLookupReply(true, accountId, true, "Steve");
        AccountProtocol.FriendCodeLookupReply decoded = AccountProtocol.decodeFriendCodeLookupReply(encoded, encoded.length);
        assertTrue(decoded.found());
        assertEquals(accountId, decoded.accountId());
        assertTrue(decoded.licensed());
        assertEquals("Steve", decoded.displayName());
    }

    @Test
    void friendCodeLookupReplyRoundTripsWhenNotFound() {
        byte[] encoded = AccountProtocol.encodeFriendCodeLookupReply(false, new UUID(0, 0), false, "");
        AccountProtocol.FriendCodeLookupReply decoded = AccountProtocol.decodeFriendCodeLookupReply(encoded, encoded.length);
        assertFalse(decoded.found());
    }

    @Test
    void friendRequestSendRoundTrips() {
        byte[] sessionToken = fill(AccountProtocol.TOKEN_LENGTH, 0xBB);
        UUID target = UUID.randomUUID();
        byte[] encoded = AccountProtocol.encodeFriendRequestSend(sessionToken, target);
        AccountProtocol.FriendRequestSend decoded = AccountProtocol.decodeFriendRequestSend(encoded, encoded.length);
        assertArrayEquals(sessionToken, decoded.sessionToken());
        assertEquals(target, decoded.targetAccountId());
    }

    @Test
    void friendRequestAckRoundTrips() {
        byte[] encoded = AccountProtocol.encodeFriendRequestAck(false, AccountProtocol.REASON_ALREADY_FRIENDS);
        AccountProtocol.FriendRequestAck decoded = AccountProtocol.decodeFriendRequestAck(encoded, encoded.length);
        assertFalse(decoded.ok());
        assertEquals(AccountProtocol.REASON_ALREADY_FRIENDS, decoded.reason());
    }

    @Test
    void friendRequestListReplyRoundTripsWithMultipleEntries() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        java.util.List<AccountProtocol.IncomingRequest> requests = java.util.List.of(
                new AccountProtocol.IncomingRequest(a, true, "Alice"),
                new AccountProtocol.IncomingRequest(b, false, "Bob"));
        byte[] encoded = AccountProtocol.encodeFriendRequestListReply(requests);
        AccountProtocol.FriendRequestListReply decoded = AccountProtocol.decodeFriendRequestListReply(encoded, encoded.length);
        assertEquals(2, decoded.requests().size());
        assertEquals(a, decoded.requests().get(0).fromAccountId());
        assertTrue(decoded.requests().get(0).licensed());
        assertEquals("Alice", decoded.requests().get(0).displayName());
        assertEquals(b, decoded.requests().get(1).fromAccountId());
        assertFalse(decoded.requests().get(1).licensed());
        assertEquals("Bob", decoded.requests().get(1).displayName());
    }

    @Test
    void friendRequestListReplyRoundTripsWhenEmpty() {
        byte[] encoded = AccountProtocol.encodeFriendRequestListReply(java.util.List.of());
        AccountProtocol.FriendRequestListReply decoded = AccountProtocol.decodeFriendRequestListReply(encoded, encoded.length);
        assertTrue(decoded.requests().isEmpty());
    }

    @Test
    void friendRequestRespondRoundTrips() {
        byte[] sessionToken = fill(AccountProtocol.TOKEN_LENGTH, 0xCC);
        UUID from = UUID.randomUUID();
        byte[] encoded = AccountProtocol.encodeFriendRequestRespond(sessionToken, from, true);
        AccountProtocol.FriendRequestRespond decoded = AccountProtocol.decodeFriendRequestRespond(encoded, encoded.length);
        assertArrayEquals(sessionToken, decoded.sessionToken());
        assertEquals(from, decoded.fromAccountId());
        assertTrue(decoded.accept());
    }

    @Test
    void friendRemoveRoundTrips() {
        byte[] sessionToken = fill(AccountProtocol.TOKEN_LENGTH, 0xDD);
        UUID friend = UUID.randomUUID();
        byte[] encoded = AccountProtocol.encodeFriendRemove(sessionToken, friend);
        AccountProtocol.FriendRemove decoded = AccountProtocol.decodeFriendRemove(encoded, encoded.length);
        assertArrayEquals(sessionToken, decoded.sessionToken());
        assertEquals(friend, decoded.friendAccountId());
    }

    @Test
    void friendListReplyRoundTripsWithHostingStatusAndRoomCode() {
        UUID a = UUID.randomUUID();
        java.util.List<AccountProtocol.FriendEntry> friends = java.util.List.of(
                new AccountProtocol.FriendEntry(a, true, "Alice", AccountProtocol.STATUS_HOSTING, "ZZZZZZ"));
        byte[] encoded = AccountProtocol.encodeFriendListReply(friends);
        AccountProtocol.FriendListReply decoded = AccountProtocol.decodeFriendListReply(encoded, encoded.length);
        assertEquals(1, decoded.friends().size());
        assertEquals(a, decoded.friends().get(0).accountId());
        assertEquals(AccountProtocol.STATUS_HOSTING, decoded.friends().get(0).status());
        assertEquals("ZZZZZZ", decoded.friends().get(0).roomCode());
    }

    @Test
    void friendListReplyRoundTripsWithOfflineEmptyRoomCode() {
        UUID a = UUID.randomUUID();
        java.util.List<AccountProtocol.FriendEntry> friends = java.util.List.of(
                new AccountProtocol.FriendEntry(a, false, "Bob", AccountProtocol.STATUS_OFFLINE, ""));
        byte[] encoded = AccountProtocol.encodeFriendListReply(friends);
        AccountProtocol.FriendListReply decoded = AccountProtocol.decodeFriendListReply(encoded, encoded.length);
        assertEquals(AccountProtocol.STATUS_OFFLINE, decoded.friends().get(0).status());
        assertEquals("", decoded.friends().get(0).roomCode());
    }

    @Test
    void searchAccountsRoundTrips() {
        byte[] sessionToken = fill(AccountProtocol.TOKEN_LENGTH, 0xEE);
        byte[] encoded = AccountProtocol.encodeSearchAccounts(sessionToken, "Ste");
        AccountProtocol.SearchAccounts decoded = AccountProtocol.decodeSearchAccounts(encoded, encoded.length);
        assertArrayEquals(sessionToken, decoded.sessionToken());
        assertEquals("Ste", decoded.query());
    }

    @Test
    void searchAccountsReplyRoundTripsSortedLicensedFirst() {
        UUID licensedId = UUID.randomUUID();
        UUID unlicensedId = UUID.randomUUID();
        java.util.List<AccountProtocol.SearchResult> results = java.util.List.of(
                new AccountProtocol.SearchResult(licensedId, true, "SteveReal"),
                new AccountProtocol.SearchResult(unlicensedId, false, "SteveFake"));
        byte[] encoded = AccountProtocol.encodeSearchAccountsReply(results);
        AccountProtocol.SearchAccountsReply decoded = AccountProtocol.decodeSearchAccountsReply(encoded, encoded.length);
        assertEquals(2, decoded.results().size());
        assertTrue(decoded.results().get(0).licensed());
        assertFalse(decoded.results().get(1).licensed());
    }

    @Test
    void fixedEncodingMatchesServerCopy() {
        // Pins exact wire bytes so drift between this copy and the mod's duplicated
        // AccountProtocol.java is caught immediately (same convention as
        // RendezvousProtocolTest.fixedEncodingMatchesServerCopy).
        byte[] licensedBegin = AccountProtocol.encodeLicensedBegin("Ab");
        assertArrayEquals(new byte[]{(byte) 0xE1, 0x20, 0x02, 'A', 'b'}, licensedBegin);

        byte[] licensedConfirm = AccountProtocol.encodeLicensedConfirm(1L);
        assertArrayEquals(new byte[]{(byte) 0xE1, 0x22, 0, 0, 0, 0, 0, 0, 0, 1}, licensedConfirm);

        byte[] loginBeginByCode = AccountProtocol.encodeLoginBeginByFriendCode("ABCDEF");
        assertArrayEquals(new byte[]{(byte) 0xE1, 0x24, 0x01, 'A', 'B', 'C', 'D', 'E', 'F'}, loginBeginByCode);

        UUID zeroOne = new UUID(0L, 1L);
        byte[] loginBeginById = AccountProtocol.encodeLoginBeginByAccountId(zeroOne);
        assertArrayEquals(new byte[]{
                (byte) 0xE1, 0x24, 0x00,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 1
        }, loginBeginById);

        byte[] authFail = AccountProtocol.encodeAuthFail(9L, AccountProtocol.REASON_BAD_CREDENTIALS);
        assertArrayEquals(new byte[]{(byte) 0xE1, 0x29, 0, 0, 0, 0, 0, 0, 0, 9, 3}, authFail);

        byte[] friendRequestAck = AccountProtocol.encodeFriendRequestAck(true, (byte) 0);
        assertArrayEquals(new byte[]{(byte) 0xE1, 0x33, 1, 0}, friendRequestAck);

        byte[] emptyFriendList = AccountProtocol.encodeFriendListReply(java.util.List.of());
        assertArrayEquals(new byte[]{(byte) 0xE1, 0x39, 0}, emptyFriendList);
    }
}
