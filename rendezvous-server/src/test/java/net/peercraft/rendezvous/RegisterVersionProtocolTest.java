package net.peercraft.rendezvous;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RegisterVersionProtocolTest {
    private static final UUID ATTEMPT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID ACCOUNT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void productionClientReportsPrivateAndFriendsVersionsWithoutWorldNames() {
        var connectivity = new net.peercraft.network.rendezvous.RendezvousProtocol.ConnectivityAdvertisement(
                false, false, List.of(), ATTEMPT);
        for (int mode = 0; mode < 3; mode++) {
            byte[] packet = mode == 0
                    ? net.peercraft.network.rendezvous.RendezvousProtocol.encodeRegisterAnonymous(
                            4, 1, false, "private world", "1.12.2", connectivity)
                    : net.peercraft.network.rendezvous.RendezvousProtocol.encodeRegisterWithAccount(
                            4, 1, ACCOUNT, new byte[16], mode == 2, false, "private world", "1.12.2", connectivity);
            var decoded = RendezvousProtocol.decodeRegister(packet, packet.length);
            assertEquals("1.12.2", decoded.mcVersion());
            assertEquals("", decoded.worldName());
            assertFalse(decoded.publicRoom());
            assertEquals(mode != 0, decoded.account().isPresent());
            assertEquals(mode == 2, decoded.friendsOnly());
            assertEquals(ATTEMPT, decoded.connectivity().orElseThrow().clientAttemptId());
            assertFalse(new String(packet, StandardCharsets.UTF_8).contains("private world"));

            byte[] serverPacket = mode == 0
                    ? RendezvousProtocol.encodeRegisterAnonymous(4, 1, false, "private world", "1.12.2",
                            serverConnectivity())
                    : RendezvousProtocol.encodeRegisterWithAccount(4, 1, ACCOUNT, new byte[16], mode == 2,
                            false, "private world", "1.12.2", serverConnectivity());
            assertArrayEquals(packet, serverPacket);
            var clientDecoded = net.peercraft.network.rendezvous.RendezvousProtocol.decodeRegister(packet, packet.length);
            assertEquals("1.12.2", clientDecoded.mcVersion());
            assertEquals(ATTEMPT, clientDecoded.connectivity().orElseThrow().clientAttemptId());
        }
    }

    @Test
    void packetsWithoutVersionTrailerStillDecode() {
        for (boolean publicRoom : new boolean[] {false, true}) {
            byte[] base = RendezvousProtocol.encodeRegisterAnonymous(4, 1, publicRoom, "public world", "26.2");
            byte[] oldPacket = append(base, connectivityBytes());
            var decoded = RendezvousProtocol.decodeRegister(oldPacket, oldPacket.length);
            assertEquals(publicRoom ? "26.2" : "", decoded.mcVersion());
            assertEquals(ATTEMPT, decoded.connectivity().orElseThrow().clientAttemptId());
            byte[] encoded = RendezvousProtocol.encodeRegisterAnonymous(4, 1, publicRoom, "public world", "26.2",
                    serverConnectivity());
            assertArrayEquals(oldPacket, publicRoom ? encoded : Arrays.copyOf(encoded, oldPacket.length));
        }
    }

    @Test
    void malformedOrFutureVersionMetadataDoesNotAffectRegistration() {
        byte[][] trailers = {
                {(byte) 0xCB}, {(byte) 0xCB, 1}, {(byte) 0xCB, 1, 0},
                {(byte) 0xCB, 2, 1, '1'}, {(byte) 0xCC, 1, 1, '1'},
                {(byte) 0xCB, 1, 2, '1'}, {(byte) 0xCB, 1, 1, '1', '2'},
                {(byte) 0xCB, 1, 1, ' '}, {(byte) 0xCB, 1, 1, (byte) 0xFF}
        };
        for (boolean publicRoom : new boolean[] {false, true}) {
            byte[] base = append(RendezvousProtocol.encodeRegisterAnonymous(
                    4, 1, publicRoom, "public world", "26.2"), connectivityBytes());
            for (byte[] trailer : trailers) {
                byte[] packet = append(base, trailer);
                var decoded = RendezvousProtocol.decodeRegister(packet, packet.length);
                assertEquals(publicRoom ? "26.2" : "", decoded.mcVersion());
                assertEquals(ATTEMPT, decoded.connectivity().orElseThrow().clientAttemptId());
            }
            byte[] conflictingVersion = append(base, new byte[] {(byte) 0xCB, 1, 1, '9'});
            assertEquals(publicRoom ? "26.2" : "9",
                    RendezvousProtocol.decodeRegister(conflictingVersion, conflictingVersion.length).mcVersion());
        }
    }

    @Test
    void privateVersionIsBoundedAndFitsServerDatagramWithMaximumCandidates() throws Exception {
        List<RendezvousProtocol.Address> candidates = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            candidates.add(new RendezvousProtocol.Address(InetAddress.getByName("2001:db8::" + i), 12345));
        }
        var connectivity = new RendezvousProtocol.ConnectivityAdvertisement(false, false, candidates, ATTEMPT);
        String longestVersion = "123456789012345678901234";
        byte[] packet = RendezvousProtocol.encodeRegisterWithAccount(4, 1, ACCOUNT, new byte[16],
                true, false, "private world", longestVersion, connectivity);
        assertTrue(packet.length <= 512);
        var decoded = RendezvousProtocol.decodeRegister(packet, packet.length);
        assertEquals(longestVersion, decoded.mcVersion());
        assertEquals(candidates, decoded.connectivity().orElseThrow().candidates());
        for (String invalid : new String[] {null, "", longestVersion + "5", "1.21 beta", "версия"}) {
            byte[] invalidPacket = RendezvousProtocol.encodeRegisterAnonymous(4, 1, false, "", invalid, connectivity);
            assertEquals("", RendezvousProtocol.decodeRegister(invalidPacket, invalidPacket.length).mcVersion());
        }
    }

    private static RendezvousProtocol.ConnectivityAdvertisement serverConnectivity() {
        return new RendezvousProtocol.ConnectivityAdvertisement(false, false, List.of(), ATTEMPT);
    }

    private static byte[] connectivityBytes() {
        // JOIN's existing connectivity encoder is unchanged by REGISTER metadata.
        byte[] join = RendezvousProtocol.encodeJoin("", serverConnectivity());
        return Arrays.copyOfRange(join, RendezvousProtocol.encodeJoin("").length + 1, join.length);
    }

    private static byte[] append(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
