package net.peercraft.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Runs before the integrated server loads any chunks or constructs player stats/advancements. */
public final class PlayerDataMigration {
    public static final String IDENTITIES_FILE = "peercraft-player-identities.properties";

    private PlayerDataMigration() { }

    /** Keep the world's established local identity if remembered login has not finished yet. */
    public static UUID rememberedIdentity(Path world, UUID vanillaId) throws IOException {
        world = world.toAbsolutePath().normalize();
        MigrationTransaction.recover(world);
        Path marker = world.resolve(IDENTITIES_FILE);
        if (!Files.exists(marker)) return vanillaId;
        Properties identities = new Properties();
        try (InputStream in = Files.newInputStream(marker)) { identities.load(in); }
        String saved = identities.getProperty("pending." + vanillaId, identities.getProperty(vanillaId.toString()));
        if (saved == null) return vanillaId;
        try { return UUID.fromString(saved); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid migrated player identity", invalid); }
    }

    private static Properties identities(Path world) throws IOException {
        Properties result = new Properties();
        Path marker = world.resolve(IDENTITIES_FILE);
        if (Files.exists(marker)) {
            try (InputStream in = Files.newInputStream(marker)) { result.load(in); }
        }
        return result;
    }

    public static boolean isBound(Path world, UUID source) throws IOException {
        return isBound(identities(world.toAbsolutePath().normalize()), source.toString());
    }

    private static boolean isBound(Properties identities, String source) {
        String assigned = identities.getProperty(source);
        return assigned != null && (!assigned.equals(source)
                || Boolean.parseBoolean(identities.getProperty("bound." + source)));
    }

    /** Only records intent; live player and region files are left untouched. */
    public static void queueIdentity(Path world, UUID source, UUID account) throws IOException {
        world = world.toAbsolutePath().normalize();
        Properties identities = identities(world);
        String key = source.toString();
        if (isBound(identities, key)) return;
        String pending = identities.getProperty("pending." + key);
        if (pending != null && !pending.equals(account.toString()))
            throw new IOException("Local player identity is already pending for another account");
        identities.setProperty("pending." + key, account.toString());
        MigrationTransaction tx = new MigrationTransaction(world);
        stageIdentities(tx, world, identities);
        tx.commit();
    }

    public static void prepare(Path world, UUID originalLocalId, UUID playingId) throws IOException {
        prepare(world, originalLocalId, playingId, !originalLocalId.equals(playingId));
    }

    public static void prepare(Path world, UUID originalLocalId, UUID playingId, boolean accountBound) throws IOException {
        world = world.toAbsolutePath().normalize();
        MigrationTransaction.recover(world);
        Properties identities = identities(world);
        // Finish the old host's late login BEFORE deciding whether the new opener may
        // claim a launcher identity. This runs offline, before any chunks are loaded.
        for (String key : new TreeSet<String>(identities.stringPropertyNames())) {
            if (!key.startsWith("pending.")) continue;
            UUID source, target;
            try {
                source = UUID.fromString(key.substring("pending.".length()));
                target = UUID.fromString(identities.getProperty(key));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid pending player identity", invalid);
            }
            migrate(world, source, target, true, identities, key);
        }
        migrate(world, originalLocalId, playingId, accountBound, identities, null);
    }

    private static void migrate(Path world, UUID originalLocalId, UUID playingId,
                                boolean accountBound, Properties identities, String pendingKey) throws IOException {
        String sourceKey = originalLocalId.toString();
        String previouslyAssigned = identities.getProperty(sourceKey);
        if (isBound(identities, sourceKey)) {
            if (pendingKey != null) {
                if (!playingId.toString().equals(previouslyAssigned))
                    throw new IOException("Pending migration conflicts with established identity");
                identities.remove(pendingKey);
                MigrationTransaction tx = new MigrationTransaction(world);
                stageIdentities(tx, world, identities);
                tx.commit();
            }
            return;
        }
        if (previouslyAssigned != null && playingId.equals(originalLocalId)) {
            if (accountBound || pendingKey != null) {
                if (accountBound) identities.setProperty("bound." + sourceKey, "true");
                if (pendingKey != null) identities.remove(pendingKey);
                MigrationTransaction tx = new MigrationTransaction(world);
                stageIdentities(tx, world, identities);
                tx.commit();
            }
            return;
        }

        MigrationNbt level = null, data = null, embedded = null;
        Path levelFile = world.resolve("level.dat");
        if (Files.exists(levelFile)) {
            try (InputStream in = Files.newInputStream(levelFile)) { level = MigrationNbt.compressed(in); }
            data = level.get("Data");
            embedded = data == null ? null : data.get("Player");
        }
        MigrationTransaction tx = new MigrationTransaction(world);
        UUID embeddedId = embedded == null ? null : embedded.playerUuid();
        if (embedded != null && embedded.type == 10 && embeddedId == null)
            throw new IOException("Data.Player has no verifiable UUID; refusing to guess its owner");

        // A transferred world's embedded Player belongs to the PREVIOUS host. Preserve it
        // under that UUID if necessary, never assign it by matching a name/current opener.
        boolean destinationAlreadyStaged = false;
        if (embeddedId != null) {
            Path previous = world.resolve("playerdata").resolve(embeddedId + ".dat");
            if (!Files.exists(previous) && !identities.containsKey(embeddedId.toString())) {
                inheritDataVersion(embedded, data);
                tx.stage(previous, embedded.compressed());
                destinationAlreadyStaged = embeddedId.equals(playingId);
            }
        }

        Path oldPlayer = world.resolve("playerdata").resolve(sourceKey + ".dat");
        Path newPlayer = world.resolve("playerdata").resolve(playingId + ".dat");
        boolean ownsEmbedded = originalLocalId.equals(embeddedId);
        boolean hasOldPlayer = Files.exists(oldPlayer);
        if (!Files.exists(newPlayer) && !destinationAlreadyStaged && (ownsEmbedded || hasOldPlayer)) {
            MigrationNbt player;
            // Prefer the same source vanilla would have loaded, but only after UUID proof.
            if (ownsEmbedded) {
                player = embedded;
                inheritDataVersion(player, data);
            } else {
                try (InputStream in = Files.newInputStream(oldPlayer)) { player = MigrationNbt.compressed(in); }
                UUID savedId = player.playerUuid();
                if (savedId != null && !savedId.equals(originalLocalId))
                    throw new IOException("Source playerdata UUID differs from its file name");
            }
            player.setPlayerUuid(playingId);
            player.replaceOwners(originalLocalId, playingId); // e.g. RootVehicle passengers
            tx.stage(newPlayer, player.compressed());
        }
        if (!originalLocalId.equals(playingId) && (ownsEmbedded || hasOldPlayer)) {
            for (String folder : new String[]{"stats", "advancements"}) {
                Path from = world.resolve(folder).resolve(sourceKey + ".json");
                Path to = world.resolve(folder).resolve(playingId + ".json");
                if (Files.exists(from) && !Files.exists(to)) {
                    Path staged = tx.temporary();
                    Files.copy(from, staged, StandardCopyOption.REPLACE_EXISTING);
                    tx.stage(to, staged);
                }
            }
            // Traverse all dimensions (DIM-1/DIM1 and dimensions/<namespace>/<id>), including
            // entities/ since 1.17. Never descend into migration backups or symlinked trees.
            final Path root = world;
            List<Path> regions = new ArrayList<Path>();
            Files.walkFileTree(world, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    return !dir.equals(root) && dir.getFileName().toString().startsWith(".peercraft")
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String parent = file.getParent().getFileName().toString();
                    String name = file.getFileName().toString();
                    if (attrs.isRegularFile() && (parent.equals("region") || parent.equals("entities"))
                            && (name.endsWith(".mca") || name.endsWith(".mcr"))) regions.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
            for (Path region : regions) MigrationRegions.stage(region, originalLocalId, playingId, tx);
        }
        identities.setProperty(sourceKey, playingId.toString());
        if (accountBound) identities.setProperty("bound." + sourceKey, "true");
        if (pendingKey != null) identities.remove(pendingKey);
        stageIdentities(tx, world, identities);
        tx.commit();
    }

    private static void stageIdentities(MigrationTransaction tx, Path world, Properties identities) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        identities.store(bytes, "PeerCraft UUID migration: source -> account; travels with host handoff");
        tx.stage(world.resolve(IDENTITIES_FILE), bytes.toByteArray());
    }

    private static void inheritDataVersion(MigrationNbt player, MigrationNbt data) {
        if (player.get("DataVersion") == null && data != null && data.get("DataVersion") != null)
            player.compound().put("DataVersion", data.get("DataVersion"));
    }
}
