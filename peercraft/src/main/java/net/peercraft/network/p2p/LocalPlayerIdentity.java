package net.peercraft.network.p2p;

import net.peercraft.network.account.AccountClient;
import net.peercraft.world.PlayerDataMigration;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Identity is captured before world load, not read again from a mutable login session. */
public final class LocalPlayerIdentity {
    private static volatile UUID playingId;
    private static UUID originalId;
    private static Path preparedWorld;
    private static boolean allowLateBinding;

    private LocalPlayerIdentity() { }

    public static void prepare(Path world, UUID vanillaId, String vanillaName) throws IOException {
        UUID id = vanillaId != null ? vanillaId
                : UUID.nameUUIDFromBytes(("OfflinePlayer:" + vanillaName).getBytes(StandardCharsets.UTF_8));
        prepare(world, id);
    }

    public static void prepare(Path world, UUID vanillaId) throws IOException {
        playingId = null;
        if (vanillaId == null) throw new IOException("Local player has no UUID");
        AccountClient.AccountSession session = AccountClient.INSTANCE.getCurrentSession();
        UUID id = session == null ? PlayerDataMigration.rememberedIdentity(world, vanillaId) : session.accountId();
        PlayerDataMigration.prepare(world, vanillaId, id, session != null);
        originalId = vanillaId;
        preparedWorld = world.toAbsolutePath().normalize();
        allowLateBinding = session == null && id.equals(vanillaId)
                && !PlayerDataMigration.isBound(preparedWorld, vanillaId);
        playingId = id;
    }

    /** Called after saving, before the identity record is included in a handoff archive. */
    public static void prepareForArchive(Path world) throws IOException {
        UUID current = playingId;
        if (current == null || !allowLateBinding
                || !world.toAbsolutePath().normalize().equals(preparedWorld)) return;
        AccountClient.AccountSession session = AccountClient.INSTANCE.getCurrentSession();
        if (session == null) return;
        PlayerDataMigration.queueIdentity(preparedWorld, originalId, session.accountId());
        allowLateBinding = false;
    }

    public static UUID current() { return playingId; }
}
