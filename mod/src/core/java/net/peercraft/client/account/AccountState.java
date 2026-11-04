package net.peercraft.client.account;

import java.util.UUID;

/**
 * What gets persisted to disk between game launches (see {@link AccountStorage}) — deliberately
 * NOT the password or its hash, only what's needed for a silent {@code TYPE_ACCOUNT_LOGIN_REMEMBER}
 * relogin. Mirrors {@code AccountClient.AccountSession} but is its own type since one is a
 * network-layer value (src/main) and this is a client-persistence value (src/client).
 */
public final class AccountState {
    private final UUID accountId;
    private final boolean licensed;
    private final String friendCode;
    private final String displayName;
    private final byte[] rememberToken;

    public AccountState(UUID accountId, boolean licensed, String friendCode, String displayName, byte[] rememberToken) {
        this.accountId = accountId;
        this.licensed = licensed;
        this.friendCode = friendCode;
        this.displayName = displayName;
        this.rememberToken = rememberToken;
    }

    public UUID accountId() {
        return accountId;
    }

    public boolean licensed() {
        return licensed;
    }

    public String friendCode() {
        return friendCode;
    }

    public String displayName() {
        return displayName;
    }

    public byte[] rememberToken() {
        return rememberToken;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AccountState)) return false;
        AccountState other = (AccountState) o;
        return java.util.Objects.equals(accountId, other.accountId)
                && licensed == other.licensed
                && java.util.Objects.equals(friendCode, other.friendCode)
                && java.util.Objects.equals(displayName, other.displayName)
                && java.util.Objects.equals(rememberToken, other.rememberToken);
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + java.util.Objects.hashCode(accountId);
        h = 31 * h + Boolean.hashCode(licensed);
        h = 31 * h + java.util.Objects.hashCode(friendCode);
        h = 31 * h + java.util.Objects.hashCode(displayName);
        h = 31 * h + java.util.Objects.hashCode(rememberToken);
        return h;
    }

    @Override
    public String toString() {
        return "AccountState[accountId=" + accountId + ", licensed=" + licensed + ", friendCode=" + friendCode + ", displayName=" + displayName + ", rememberToken=" + rememberToken + "]";
    }
}
