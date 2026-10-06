package net.peercraft.network.account;

import java.util.Locale;
import java.util.UUID;

/** Public account locator; authentication still requires the account password. */
public final class AccountLoginIdentifier {
    private final UUID accountId;
    private final String friendCode;

    private AccountLoginIdentifier(UUID accountId, String friendCode) {
        this.accountId = accountId;
        this.friendCode = friendCode;
    }

    public static AccountLoginIdentifier parse(String input) {
        if (input == null) throw new IllegalArgumentException("Missing account identifier");
        String value = input.trim();
        if (value.matches("[a-zA-Z0-9]{6}")) {
            return new AccountLoginIdentifier(null, value.toUpperCase(Locale.ROOT));
        }
        if (value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
            return new AccountLoginIdentifier(UUID.fromString(value), null);
        }
        throw new IllegalArgumentException("Expected friend code or full account ID");
    }

    public UUID accountId() { return accountId; }
    public String friendCode() { return friendCode; }
}
