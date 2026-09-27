package net.peercraft.client.handoff;

import net.peercraft.network.handoff.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static net.peercraft.network.handoff.HandoffAuthorityProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

class HandoffNetworkRecoveryTest {
    @TempDir Path root;
    HandoffJournal journal(String role) throws Exception {
        Path saves = Files.createDirectories(root.resolve("saves"));
        Path records = Files.createDirectories(root.resolve("journals"));
        HandoffJournal j = new HandoffJournal(records.resolve("attempt.journal"), UUID.randomUUID(), 4, 0, new byte[32]);
        j.role = role; j.authorityHost = "127.0.0.1"; j.source = saves.resolve("source").toString();
        Files.createDirectories(Paths.get(j.source)); PeercraftWorldMeta.ensureHosting(Paths.get(j.source));
        j.digest[0] = 17; return j;
    }
    final class Authority implements AutoCloseable {
        final DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        final Thread reader;
        Authority(int state, boolean owner, byte[] digest) throws Exception {
            reader = new Thread(() -> {
                while (!socket.isClosed()) try {
                    byte[] bytes = new byte[2048]; DatagramPacket packet = new DatagramPacket(bytes, bytes.length); socket.receive(packet);
                    Message request = HandoffAuthorityProtocol.decode(bytes, packet.getLength());
                    Message reply = new Message(REPLY); reply.requestId = request.requestId; reply.sessionId = request.sessionId;
                    reply.offerId = request.offerId; reply.state = state; reply.epoch = state == ABORTED ? 0 : 1;
                    reply.currentEpoch = reply.epoch; reply.isCurrentAttempt = true; reply.ownsCurrentEpoch = owner; reply.digest = digest.clone();
                    byte[] result = HandoffAuthorityProtocol.encode(reply); socket.send(new DatagramPacket(result, result.length, packet.getSocketAddress()));
                } catch (IOException ignored) { }
            }); reader.setDaemon(true); reader.start();
        }
        public void close() throws Exception { socket.close(); reader.join(2000); assertFalse(reader.isAlive()); }
    }
    @Test void lostCommitReplyRestoresStaleWarningWithoutGivingSourceOwnership() throws Exception {
        HandoffJournal j = journal("SOURCE"); j.advance(HandoffJournal.Phase.COMMIT_SENT);
        try (Authority authority = new Authority(COMMITTED, false, j.digest)) {
            j.authorityPort = authority.socket.getLocalPort(); j.save();
            HandoffNetworkRecovery.recover(j, root.resolve("saves"), (path, sid, epoch, key) -> fail("Source cannot receive a grant"), true);
            assertEquals(HandoffJournal.Phase.COMMITTED, HandoffJournal.read(j.path()).phase);
            assertTrue(PeercraftWorldMeta.loadOrNull(Paths.get(j.source)).isStaleAfterHandoff());
        }
    }
    @Test void unknownOutcomeRetainsSnapshotAndExistingWorld() throws Exception {
        HandoffJournal j = journal("SUCCESSOR"); j.source = ""; j.target = root.resolve("saves/target").toString();
        Path target = Files.createDirectories(Paths.get(j.target)); Files.write(target.resolve("region"), new byte[]{3});
        Path archive = root.resolve("journals/archive.zip"); Files.write(archive, new byte[]{7}); j.archive = archive.toString();
        j.advance(HandoffJournal.Phase.VERIFIED);
        try (Authority authority = new Authority(UNKNOWN, false, j.digest)) {
            j.authorityPort = authority.socket.getLocalPort(); j.save();
            assertThrows(IOException.class, () -> HandoffNetworkRecovery.recover(j, root.resolve("saves"), (path, sid, epoch, key) -> fail("UNKNOWN cannot grant launch"), true));
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(archive)); assertArrayEquals(new byte[]{3}, Files.readAllBytes(target.resolve("region")));
            assertEquals(HandoffJournal.Phase.VERIFIED, HandoffJournal.read(j.path()).phase);
        }
    }
    @Test void confirmedAbortDeletesOnlyThisAttemptsScratch() throws Exception {
        HandoffJournal j = journal("SUCCESSOR"); String attempt = j.session + "-" + Long.toHexString(j.offer);
        Path staging = Files.createDirectories(root.resolve("saves/.peercraft-handoff-staging-" + attempt));
        j.staging = staging.toString(); Path archive = root.resolve("journals/" + attempt + ".zip");
        Files.write(archive, new byte[]{7}); j.archive = archive.toString();
        try (Authority authority = new Authority(ABORTED, false, j.digest)) {
            j.authorityPort = authority.socket.getLocalPort(); j.save();
            HandoffNetworkRecovery.recover(j, root.resolve("saves"), (path, sid, epoch, key) -> fail(), true);
            assertFalse(Files.exists(staging)); assertFalse(Files.exists(archive)); assertTrue(Files.exists(Paths.get(j.source)));
            assertEquals(HandoffJournal.Phase.ABORTED, HandoffJournal.read(j.path()).phase);
        }
    }
    @Test void journalPersistsNativeRecoveryParameters() throws Exception {
        HandoffJournal j = journal("SUCCESSOR"); j.authorityPort = 1234; j.backup = "backup"; j.keepBackup = true; j.save();
        HandoffJournal read = HandoffJournal.read(j.path()); assertEquals(j.role, read.role); assertEquals(j.authorityHost, read.authorityHost);
        assertEquals(1234, read.authorityPort); assertEquals("backup", read.backup); assertTrue(read.keepBackup); assertArrayEquals(j.digest, read.digest);
    }
}
