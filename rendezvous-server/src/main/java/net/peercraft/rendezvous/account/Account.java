package net.peercraft.rendezvous.account;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable account record — persisted to disk via {@link AccountStore} (Gson), unlike the
 * short-lived {@code Room}/{@code Player} state elsewhere in the server. Package-private —
 * only classes in this package touch it directly; {@link AccountService} is the public face.
 */
final class Account {

    final UUID accountId;
    String displayName;
    final boolean licensed;
    final String friendCode;

    // null for licensed accounts — their identity is Mojang's, not a local password.
    byte[] passwordSalt;
    byte[] passwordHash;
    // Rotated on every successful TYPE_ACCOUNT_LOGIN_REMEMBER use — limits replay of a
    // sniffed remember-token to "first user of it wins", not indefinite reuse.
    byte[] rememberToken;

    // Mutual once accepted — a friendship always appears in BOTH accounts' `friends` sets,
    // maintained by AccountService.respondToRequest/removeFriend. outgoing/incoming are the
    // pending-request halves, cleared on accept/decline. NOT final: Gson deserializes via
    // Unsafe field injection (Account has no no-arg constructor), which bypasses these field
    // initializers entirely and hands back its own (non-concurrent) Set implementation — see
    // normalizeCollectionsAfterDeserialization(), which AccountStore.load() calls to restore
    // the ConcurrentHashMap-backed instances for every account read from disk.
    Set<UUID> friends = ConcurrentHashMap.newKeySet();
    Set<UUID> outgoingRequests = ConcurrentHashMap.newKeySet();
    Set<UUID> incomingRequests = ConcurrentHashMap.newKeySet();

    Account(UUID accountId, String displayName, boolean licensed, String friendCode) {
        this.accountId = accountId;
        this.displayName = displayName;
        this.licensed = licensed;
        this.friendCode = friendCode;
    }

    /** Called once per account right after Gson deserialization — see the field comment above. */
    void normalizeCollectionsAfterDeserialization() {
        this.friends = concurrentCopyOf(this.friends);
        this.outgoingRequests = concurrentCopyOf(this.outgoingRequests);
        this.incomingRequests = concurrentCopyOf(this.incomingRequests);
    }

    private static Set<UUID> concurrentCopyOf(Set<UUID> source) {
        Set<UUID> fresh = ConcurrentHashMap.newKeySet();
        if (source != null) {
            fresh.addAll(source);
        }
        return fresh;
    }
}
