package net.peercraft.network.rendezvous;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConnectivityProtocolTest {
    private static RendezvousProtocol.Address ipv6() throws Exception {
        return new RendezvousProtocol.Address(InetAddress.getByName("2001:db8::5"), 50002);
    }
    @Test void anonymousNewClientAdvertisesDirectChecksWithoutRelayConsentOrAccount() throws Exception {
        var ad = new RendezvousProtocol.ConnectivityAdvertisement(false, false, Collections.singletonList(ipv6()));
        byte[] register = RendezvousProtocol.encodeRegisterAnonymous(4, 0, false, "", "", ad);
        var decoded = RendezvousProtocol.decodeRegister(register, register.length);
        assertTrue(decoded.account().isEmpty()); assertTrue(decoded.connectivity().isPresent());
        assertFalse(decoded.connectivity().get().relayConsent());
        assertEquals(ipv6(), decoded.connectivity().get().candidates().get(0));
        byte[] join = RendezvousProtocol.encodeJoin("ABCDEF", ad);
        var joined = RendezvousProtocol.decodeJoin(join, join.length);
        assertTrue(joined.sessionToken().isEmpty()); assertTrue(joined.connectivity().isPresent());
    }
    @Test void accountAdmissionAndHostConsentRemainDistinctFields() throws Exception {
        var ad = new RendezvousProtocol.ConnectivityAdvertisement(true, false, Collections.singletonList(ipv6()));
        UUID account = UUID.randomUUID(); byte[] token = new byte[16];
        byte[] bytes = RendezvousProtocol.encodeRegisterWithAccount(8, 2, account, token, true, true, "World", "1.21.1", ad);
        var register = RendezvousProtocol.decodeRegister(bytes, bytes.length);
        assertEquals(account, register.account().get().accountId()); assertTrue(register.friendsOnly());
        assertEquals("World", register.worldName()); assertTrue(register.connectivity().get().relayCapable());
        assertFalse(register.connectivity().get().relayConsent());
        byte[] join = RendezvousProtocol.encodeJoinWithAccount("ABCDEF", token, ad);
        assertArrayEquals(token, RendezvousProtocol.decodeJoin(join, join.length).sessionToken().get());
    }
    @Test void detailedOfferRoundTripsAndOwnsItsSecretBytes() throws Exception {
        UUID attempt = UUID.fromString("00000000-0000-0000-0000-000000000001");
        byte[] key = new byte[32]; Arrays.fill(key, (byte) 3);
        var offer = new RendezvousProtocol.NetworkOffer(attempt, key, Collections.singletonList(ipv6()), true, "https://relay.example/v1");
        key[0] = 9;
        byte[] bytes = RendezvousProtocol.encodePeerFoundDetailed(ipv6(), 7, Optional.empty(), offer);
        var result = RendezvousProtocol.decodePeerFound(bytes, bytes.length);
        assertTrue(result.joinerAccountId().isEmpty()); assertEquals(attempt, result.networkOffer().get().attemptId());
        assertEquals(3, result.networkOffer().get().challengeKey()[0]);
        byte[] copy = result.networkOffer().get().challengeKey(); copy[0] = 4;
        assertEquals(3, result.networkOffer().get().challengeKey()[0]);
        assertEquals("https://relay.example/v1", result.networkOffer().get().brokerUrl());
        assertEquals(ipv6(), result.networkOffer().get().peerCandidates().get(0));
        assertEquals("e1061020010db8000000000000000000000005c352000000000000000700ca01", hex(Arrays.copyOf(bytes, 32)));
    }
    @Test void oldWireFormatsStayUnchangedAndDirectOnly() throws Exception {
        byte[] register = RendezvousProtocol.encodeRegister(4, 0);
        assertArrayEquals(new byte[] {(byte) 0xe1, 1, 4, 0}, register);
        assertTrue(RendezvousProtocol.decodeRegister(register, register.length).connectivity().isEmpty());
        byte[] join = RendezvousProtocol.encodeJoin("ABCDEF");
        assertTrue(RendezvousProtocol.decodeJoin(join, join.length).connectivity().isEmpty());
        byte[] found = RendezvousProtocol.encodePeerFound(ipv6(), 7);
        assertTrue(RendezvousProtocol.decodePeerFound(found, found.length).networkOffer().isEmpty());
    }
    @Test void rejectsTruncatedOrOversizedCandidateExtensions() throws Exception {
        var ad = new RendezvousProtocol.ConnectivityAdvertisement(true, true, Collections.singletonList(ipv6()));
        byte[] bytes = RendezvousProtocol.encodeJoin("ABCDEF", ad);
        assertThrows(RuntimeException.class, () -> RendezvousProtocol.decodeJoin(bytes, bytes.length - 1));
        assertThrows(IllegalArgumentException.class, () -> new RendezvousProtocol.ConnectivityAdvertisement(true, true,
                Collections.nCopies(9, ipv6())));
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
}
