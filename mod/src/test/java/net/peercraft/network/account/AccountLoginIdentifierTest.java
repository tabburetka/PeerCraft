package net.peercraft.network.account;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AccountLoginIdentifierTest {
    @Test
    void normalizesFriendCodes() {
        AccountLoginIdentifier parsed = AccountLoginIdentifier.parse(" abc123 ");
        assertEquals("ABC123", parsed.friendCode());
        assertNull(parsed.accountId());
    }

    @Test
    void acceptsFullRecoveryIdWithoutFriendCode() {
        UUID id = UUID.randomUUID();
        AccountLoginIdentifier parsed = AccountLoginIdentifier.parse(" " + id.toString().toUpperCase() + " ");
        assertEquals(id, parsed.accountId());
        assertNull(parsed.friendCode());
    }

    @Test
    void rejectsInvalidAndShortenedIds() {
        for (String input : new String[] {null, "", "abc", "ABC DE", "1-1-1-1-1", "ИГРОК1",
                "12345678-1234-1234-1234-12345678901g"}) {
            assertThrows(IllegalArgumentException.class, () -> AccountLoginIdentifier.parse(input));
        }
    }
}
