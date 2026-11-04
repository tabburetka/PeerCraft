package net.peercraft.network.p2p;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerIdentityRegistryTest {

    @Test
    void putThenGetReturnsTheStoredAccountId() {
        PlayerIdentityRegistry registry = PlayerIdentityRegistry.INSTANCE;
        UUID accountId = UUID.randomUUID();

        registry.put(54321, accountId);

        assertEquals(accountId, registry.get(new InetSocketAddress("127.0.0.1", 54321)));
        registry.remove(54321);
    }

    @Test
    void unknownPortReturnsNull() {
        PlayerIdentityRegistry registry = PlayerIdentityRegistry.INSTANCE;

        assertNull(registry.get(new InetSocketAddress("127.0.0.1", 1)));
    }

    @Test
    void removeForgetTheMapping() {
        PlayerIdentityRegistry registry = PlayerIdentityRegistry.INSTANCE;
        UUID accountId = UUID.randomUUID();
        registry.put(54322, accountId);

        registry.remove(54322);

        assertNull(registry.get(new InetSocketAddress("127.0.0.1", 54322)));
    }

    @Test
    void lanClientWithTheProxyPortCannotClaimItsAccount() throws Exception {
        PlayerIdentityRegistry registry = PlayerIdentityRegistry.INSTANCE;
        registry.put(54323, UUID.randomUUID());
        try {
            assertNull(registry.get(new InetSocketAddress(
                    InetAddress.getByAddress(new byte[] {(byte) 192, (byte) 168, 1, 42}), 54323)));
            assertNull(registry.get(InetSocketAddress.createUnresolved("localhost", 54323)));
            assertNull(registry.get((SocketAddress) null));
            assertNull(registry.get(new SocketAddress() { }));
        } finally {
            registry.remove(54323);
        }
    }

    @Test
    void ipv6LoopbackResolvesTheProxyAccount() throws Exception {
        PlayerIdentityRegistry registry = PlayerIdentityRegistry.INSTANCE;
        UUID account = UUID.randomUUID();
        registry.put(54324, account);
        try {
            byte[] loopback = new byte[16];
            loopback[15] = 1;
            assertEquals(account, registry.get(new InetSocketAddress(InetAddress.getByAddress(loopback), 54324)));
        } finally {
            registry.remove(54324);
        }
    }
}
