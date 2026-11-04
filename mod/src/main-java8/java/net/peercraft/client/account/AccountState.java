package net.peercraft.client.account;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * What gets persisted to disk between game launches (see {@link AccountStorage}) — deliberately
 * NOT the password or its hash, only what's needed for a silent {@code TYPE_ACCOUNT_LOGIN_REMEMBER}
 * relogin. Mirrors {@code AccountClient.AccountSession}.
 *
 * <p>Java 8 backport of the {@code record} in {@code src/main/java} for the Minecraft 1.16.5
 * target. Field names are unchanged so the existing Gson-serialized {@code account.json}
 * round-trips byte-for-byte.
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
        if (this == o) {
            return true;
        }
        if (!(o instanceof AccountState)) {
            return false;
        }
        AccountState other = (AccountState) o;
        return licensed == other.licensed
                && Objects.equals(accountId, other.accountId)
                && Objects.equals(friendCode, other.friendCode)
                && Objects.equals(displayName, other.displayName)
                && Arrays.equals(rememberToken, other.rememberToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, licensed, friendCode, displayName, Arrays.hashCode(rememberToken));
    }

    @Override
    public String toString() {
        return "AccountState[accountId=" + accountId + ", licensed=" + licensed
                + ", friendCode=" + friendCode + ", displayName=" + displayName
                + ", rememberToken=" + Arrays.toString(rememberToken) + "]";
    }
}
