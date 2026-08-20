package net.peercraft.network.p2p;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerIdentityRegistryTest {

    @Test
    void putThenGetReturnsTheStoredAccountId() {
        PlayerIdentityRegistry registry = PlayerIdentityRegistry.INSTANCE;
        UUID accountId = UUID.randomUUID();

        registry.put(54321, accountId);

        assertEquals(accountId, registry.get(54321));
        registry.remove(54321);
    }

    @Test
    void unknownPortReturnsNull() {
        PlayerIdentityRegistry registry = PlayerIdentityRegistry.INSTANCE;

        assertNull(registry.get(1));
    }

    @Test
    void removeForgetTheMapping() {
        PlayerIdentityRegistry registry = PlayerIdentityRegistry.INSTANCE;
        UUID accountId = UUID.randomUUID();
        registry.put(54322, accountId);

        registry.remove(54322);

        assertNull(registry.get(54322));
    }
}
