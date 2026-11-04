package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HandoffProtocolTest {

    private static final byte M = (byte) 0xE3;

    @Test
    void magicAndTypeAreReadable() {
        byte[] ping = HandoffProtocol.encodePing();
        assertEquals(HandoffProtocol.T_PING, HandoffProtocol.messageType(ping, ping.length));
        assertEquals(-1, HandoffProtocol.messageType(new byte[]{0x00, 0x01}, 2));
        assertEquals(-1, HandoffProtocol.messageType(new byte[]{M}, 1));
    }

    @Test
    void fixedEncodings() {
        assertArrayEquals(new byte[]{M, HandoffProtocol.T_PING}, HandoffProtocol.encodePing());

        assertArrayEquals(new byte[]{M, HandoffProtocol.T_SUCCESSOR_PREFERENCE, 1},
                HandoffProtocol.encodeSuccessorPreference(true));
        assertArrayEquals(new byte[]{M, HandoffProtocol.T_SUCCESSOR_PREFERENCE, 0},
                HandoffProtocol.encodeSuccessorPreference(false));

        // ACCEPT / MIGRATE_OK: magic, type, 8-byte offerId (big-endian)
        assertArrayEquals(
                new byte[]{M, HandoffProtocol.T_ACCEPT, 0, 0, 0, 0, 0, 0, 0, 7},
                HandoffProtocol.encodeAccept(7));
        assertArrayEquals(
                new byte[]{M, HandoffProtocol.T_MIGRATE_OK, 0, 0, 0, 0, 0, 0, 0, 7},
                HandoffProtocol.encodeMigrateOk(7));

        // DECLINE: magic, type, offerId, 2-byte strlen, key bytes
        assertArrayEquals(
                new byte[]{M, HandoffProtocol.T_DECLINE, 0, 0, 0, 0, 0, 0, 0, 3, 0, 1, 'k'},
                HandoffProtocol.encodeDecline(3, "k"));
        assertArrayEquals(
                new byte[]{M, HandoffProtocol.T_ABORT, 0, 0, 0, 0, 0, 0, 0, 3, 0, 1, 'k'},
                HandoffProtocol.encodeAbort(3, "k"));
    }

    @Test
    void offerRoundTrips() {
        UUID successor = UUID.randomUUID();
        byte[] encoded = HandoffProtocol.encodeOffer(
                0x0102030405060708L, "My World", 12_345_678L, 8,
                HandoffProtocol.OFFER_FLAG_ALLOW_UNLICENSED | HandoffProtocol.OFFER_FLAG_PUBLIC_ROOM,
                List.of(new HandoffProtocol.ModRef("sodium", "0.5.8"), new HandoffProtocol.ModRef("lithium", "0.12")),
                "9e107d9d-3720-4f0e-8b2c-0000deadbeef");

        assertEquals(HandoffProtocol.T_OFFER, HandoffProtocol.messageType(encoded, encoded.length));
        HandoffProtocol.Offer o = HandoffProtocol.decodeOffer(encoded, encoded.length);
        assertEquals(HandoffProtocol.PROTO_VERSION, o.protoVersion());
        assertEquals(0x0102030405060708L, o.offerId());
        assertEquals("My World", o.worldLabel());
        assertEquals(12_345_678L, o.estArchiveBytes());
        assertEquals(8, o.maxPlayers());
        assertTrue(o.allowUnlicensed());
        assertFalse(o.friendsOnly());
        assertTrue(o.publicRoom());
        assertEquals(2, o.requiredMods().size());
        assertEquals("sodium", o.requiredMods().get(0).id());
        assertEquals("0.12", o.requiredMods().get(1).version());
        assertEquals("9e107d9d-3720-4f0e-8b2c-0000deadbeef", o.worldId());
    }

    @Test
    void offerWithNoModsRoundTrips() {
        byte[] encoded = HandoffProtocol.encodeOffer(1, "w", 0, 4, 0, List.of(), "wid-1");
        HandoffProtocol.Offer o = HandoffProtocol.decodeOffer(encoded, encoded.length);
        assertEquals(0, o.requiredMods().size());
        assertFalse(o.allowUnlicensed());
        assertFalse(o.publicRoom());
        assertEquals("wid-1", o.worldId());
    }

    @Test
    void declineAndAbortRoundTrip() {
        byte[] d = HandoffProtocol.encodeDecline(42, "peercraft.handoff.decline.missing_mods");
        HandoffProtocol.Decline decline = HandoffProtocol.decodeDecline(d, d.length);
        assertEquals(42, decline.offerId());
        assertEquals("peercraft.handoff.decline.missing_mods", decline.reasonKey());

        byte[] a = HandoffProtocol.encodeAbort(42, "peercraft.handoff.abort.transfer_failed");
        HandoffProtocol.Abort abort = HandoffProtocol.decodeAbort(a, a.length);
        assertEquals(42, abort.offerId());
        assertEquals("peercraft.handoff.abort.transfer_failed", abort.reasonKey());
    }

    @Test
    void migrateRoundTrips() {
        UUID successor = UUID.randomUUID();
        byte[] encoded = HandoffProtocol.encodeMigrate(99, successor);
        assertEquals(HandoffProtocol.T_MIGRATE, HandoffProtocol.messageType(encoded, encoded.length));
        HandoffProtocol.Migrate m = HandoffProtocol.decodeMigrate(encoded, encoded.length);
        assertEquals(99, m.offerId());
        assertEquals(successor, m.successorAccountId());
    }

    @Test
    void acceptAndMigrateOkDecodeOfferId() {
        byte[] acc = HandoffProtocol.encodeAccept(0xCAFEBABEL);
        assertEquals(0xCAFEBABEL, HandoffProtocol.decodeOfferId(acc, acc.length));
        byte[] ok = HandoffProtocol.encodeMigrateOk(0xCAFEBABEL);
        assertEquals(0xCAFEBABEL, HandoffProtocol.decodeOfferId(ok, ok.length));
    }

    @Test
    void successorPreferenceRoundTrips() {
        byte[] decline = HandoffProtocol.encodeSuccessorPreference(true);
        assertEquals(HandoffProtocol.T_SUCCESSOR_PREFERENCE, HandoffProtocol.messageType(decline, decline.length));
        assertTrue(HandoffProtocol.decodeSuccessorPreference(decline, decline.length));

        byte[] willing = HandoffProtocol.encodeSuccessorPreference(false);
        assertFalse(HandoffProtocol.decodeSuccessorPreference(willing, willing.length));

        // Older/short senders decode to "willing" rather than throwing.
        assertFalse(HandoffProtocol.decodeSuccessorPreference(new byte[]{M, HandoffProtocol.T_SUCCESSOR_PREFERENCE}, 2));
    }
}
