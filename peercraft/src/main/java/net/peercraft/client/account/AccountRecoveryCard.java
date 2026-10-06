package net.peercraft.client.account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Public recovery locator, deliberately independent of password and login tokens. */
public final class AccountRecoveryCard {
    private AccountRecoveryCard() { }

    public static String format(AccountState state) {
        return "PeerCraft account recovery / Восстановление аккаунта PeerCraft\n"
                + "Account ID / ID аккаунта: " + state.accountId() + "\n"
                + "Friend code / Код дружбы: " + state.friendCode() + "\n\n"
                + (state.licensed()
                ? "Sign in with the same Mojang account. / Войдите через тот же аккаунт Mojang.\n"
                : "Enter the Account ID and your existing password on the login screen.\n"
                + "На экране входа укажите ID аккаунта и свой прежний пароль.\n")
                + "This card cannot reset a forgotten password. / Карточка не сбрасывает забытый пароль.\n"
                + "Keep a copy outside the game folder. / Сохраните копию вне папки игры.\n"
                + "Progress is stored in the host's world, not in this card.\n"
                + "Прогресс хранится в мире хоста, а не в этой карточке.\n";
    }

    public static Path save(Path directory, AccountState state) throws IOException {
        if (state.accountId() == null) throw new IOException("Account has no recovery ID");
        Files.createDirectories(directory);
        Path destination = directory.resolve(state.accountId() + ".txt");
        Path temporary = Files.createTempFile(directory, "recovery-", ".tmp");
        try {
            Files.write(temporary, format(state).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
