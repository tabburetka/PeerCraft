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

    /** Captured before stopping the server so account changes cannot alter the closed snapshot. */
    public static final class ArchiveIdentity {
        private final Path world;
        private final UUID source, account;
        private ArchiveIdentity(Path world, UUID source, UUID account) {
            this.world = world; this.source = source; this.account = account;
        }
        public void prepare(Path closedWorld) throws IOException {
            if (!closedWorld.toAbsolutePath().normalize().equals(world))
                throw new IOException("Archive identity belongs to another world");
            if (source == null || account == null) return;
            PlayerDataMigration.queueIdentity(world, source, account);
            if (world.equals(preparedWorld) && source.equals(originalId)) allowLateBinding = false;
        }
    }
    public static ArchiveIdentity captureForArchive(Path world) {
        Path path = world.toAbsolutePath().normalize();
        UUID current = playingId;
        AccountClient.AccountSession session = AccountClient.INSTANCE.getCurrentSession();
        boolean binding = current != null && allowLateBinding && path.equals(preparedWorld) && session != null;
        return new ArchiveIdentity(path, binding ? originalId : null, binding ? session.accountId() : null);
    }
    /** Compatibility entry point for callers that still archive a live server. */
    public static void prepareForArchive(Path world) throws IOException { captureForArchive(world).prepare(world); }

    public static UUID current() { return playingId; }

    public static void bindAuthenticatedGuest(UUID account) throws IOException {
        if (playingId == null || preparedWorld == null) throw new IOException("Host world identity unavailable");
        PlayerDataMigration.markAuthenticatedGuest(preparedWorld, account);
    }
}
