package net.peercraft.client.account;

import java.util.UUID;

/**
 * What gets persisted to disk between game launches (see {@link AccountStorage}) — deliberately
 * NOT the password or its hash, only what's needed for a silent {@code TYPE_ACCOUNT_LOGIN_REMEMBER}
 * relogin. Mirrors {@code AccountClient.AccountSession} but is its own type since one is a
 * network-layer value (src/main) and this is a client-persistence value (src/client).
 */
public record AccountState(UUID accountId, boolean licensed, String friendCode, String displayName, byte[] rememberToken) {
}
