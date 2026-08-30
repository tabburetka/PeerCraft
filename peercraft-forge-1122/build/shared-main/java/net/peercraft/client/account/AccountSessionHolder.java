package net.peercraft.client.account;

import net.peercraft.network.account.AccountClient;

/**
 * Thin convenience wrapper around {@code AccountClient.INSTANCE}'s current session — GUI
 * screens and (from Phase 4 onward) the presence heartbeat read through here instead of
 * reaching into the network-layer singleton directly. Also owns the login<->disk-persistence
 * link: a successful login is saved, a logout clears the saved state too.
 */
public final class AccountSessionHolder {

    private AccountSessionHolder() {
    }

    public static AccountClient.AccountSession current() {
        return AccountClient.INSTANCE.getCurrentSession();
    }

    public static boolean isLoggedIn() {
        return current() != null;
    }

    /** Call after any successful login/register/rename — persists the session for next launch. */
    public static void persist(AccountClient.AccountSession session) {
        AccountStorage.save(new AccountState(session.accountId(), session.licensed(), session.friendCode(),
                session.displayName(), session.rememberToken()));
    }

    public static void logout() {
        AccountClient.INSTANCE.clearSession();
        AccountStorage.clear();
    }
}
