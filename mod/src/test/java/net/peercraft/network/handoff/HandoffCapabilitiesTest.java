package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HandoffCapabilitiesTest {
    private static InetSocketAddress peer(int port) { return new InetSocketAddress(InetAddress.getLoopbackAddress(), port); }
    private static byte[] reply(byte[] request, long features) {
        byte[] result = request.clone(); result[2] = 2; ByteBuffer.wrap(result).putLong(11, features); return result;
    }
    @Test void requiresEveryParticipantBeforeOffering() throws Exception {
        AtomicReference<HandoffCapabilities> local = new AtomicReference<>();
        local.set(new HandoffCapabilities((peer, bytes) -> local.get().onPacket(reply(bytes, 1), 19, peer), 1));
        local.get().require(new HashSet<>(Arrays.asList(peer(30001), peer(30002))), 1, 1000);
    }
    @Test void oneOldParticipantRejectsTheWholeOperation() {
        AtomicReference<HandoffCapabilities> local = new AtomicReference<>();
        local.set(new HandoffCapabilities((peer, bytes) -> local.get().onPacket(reply(bytes, peer.getPort() == 30001 ? 1 : 0), 19, peer), 1));
        assertThrows(IOException.class, () -> local.get().require(new HashSet<>(Arrays.asList(peer(30001), peer(30002))), 1, 1000));
    }
    @Test void replyFromAnotherPortDoesNotAuthorizeMissingParticipant() {
        AtomicReference<HandoffCapabilities> local = new AtomicReference<>();
        local.set(new HandoffCapabilities((peer, bytes) -> local.get().onPacket(reply(bytes, 1), 19, peer(30002)), 1));
        assertThrows(IOException.class, () -> local.get().require(new HashSet<>(Arrays.asList(peer(30001))), 1, 30));
    }
    @Test void legacyPacketCannotBeCapabilityResponse() {
        AtomicReference<HandoffCapabilities> local = new AtomicReference<>();
        local.set(new HandoffCapabilities((peer, bytes) -> {
            byte[] fake = reply(bytes, 1); fake[0] = HandoffProtocol.MAGIC; local.get().onPacket(fake, 19, peer);
        }, 1));
        assertThrows(IOException.class, () -> local.get().require(new HashSet<>(Arrays.asList(peer(30001))), 1, 30));
    }
}
