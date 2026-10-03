package net.peercraft.client.account;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Show the identity warning once per unlicensed account per game launch. */
public final class AccountProgressNotice {
    private static final Set<UUID> shown = ConcurrentHashMap.newKeySet();
    private AccountProgressNotice() { }
    public static boolean firstDisplay(UUID accountId) { return shown.add(accountId); }
}
