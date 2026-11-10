package net.peercraft.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDataMigrationTest {
    @TempDir Path world;
    @TempDir Path transfer;
    private final UUID vanilla = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID host = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private final UUID successor = UUID.fromString("30000000-0000-0000-0000-000000000003");

    private static MigrationNbt compound(Object... pairs) {
        Map<String, MigrationNbt> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (MigrationNbt) pairs[i + 1]);
        return new MigrationNbt(10, map);
    }

    private static MigrationNbt player(UUID id, int experience) {
        MigrationNbt tag = compound("XpTotal", new MigrationNbt(3, experience),
                "Pos", new MigrationNbt(9, Arrays.asList(new MigrationNbt(6, null),
                        new MigrationNbt(6, (double) experience), new MigrationNbt(6, 64.0), new MigrationNbt(6, -12.0))),
                "Inventory", new MigrationNbt(9, Arrays.asList(new MigrationNbt(10, null),
                        compound("id", new MigrationNbt(8, "minecraft:diamond"), "Count", new MigrationNbt(1, (byte) 7)))),
                "ModdedProgress", new MigrationNbt(12, new long[]{Long.MIN_VALUE, 42}));
        tag.setPlayerUuid(id);
        return tag;
    }

    private void level(MigrationNbt player) throws IOException {
        Files.write(world.resolve("level.dat"), compound("Data", compound("Player", player,
                "DataVersion", new MigrationNbt(3, 3955))).compressed());
    }

    private Path playerPath(UUID id) { return world.resolve("playerdata").resolve(id + ".dat"); }
    private void savePlayer(UUID id, int xp) throws IOException {
        Files.createDirectories(playerPath(id).getParent());
        Files.write(playerPath(id), player(id, xp).compressed());
    }
    private MigrationNbt readPlayer(UUID id) throws IOException {
        try (InputStream in = Files.newInputStream(playerPath(id))) { return MigrationNbt.compressed(in); }
    }

    @Test void lateLoginIsMigratedBeforeSuccessorClaimsSharedLauncher() throws Exception {
        level(player(vanilla, 150));
        PlayerDataMigration.prepare(world, vanilla, vanilla, false);
        savePlayer(successor, 900);
        byte[] before = Files.readAllBytes(playerPath(vanilla));
        Path region = world.resolve("entities/r.0.0.mca");
        writeRegion(region, petChunk(), 2, false);
        byte[] regionBefore = Files.readAllBytes(region);
        PlayerDataMigration.queueIdentity(world, vanilla, host);
        assertFalse(Files.exists(playerPath(host)));
        assertArrayEquals(before, Files.readAllBytes(playerPath(vanilla)));
        assertArrayEquals(regionBefore, Files.readAllBytes(region));
        assertEquals(host, PlayerDataMigration.rememberedIdentity(world, vanilla));
        PlayerDataMigration.prepare(world, vanilla, successor, true);
        assertEquals(150, readPlayer(host).get("XpTotal").value);
        assertEquals(900, readPlayer(successor).get("XpTotal").value);
        assertEquals(host, MigrationNbt.uuid(readRegion(region, false).get("Pet").get("Owner")));
        assertEquals(host, PlayerDataMigration.rememberedIdentity(world, vanilla));
        assertTrue(PlayerDataMigration.isBound(world, vanilla));
        Properties identities = new Properties();
        try (InputStream in = Files.newInputStream(world.resolve(PlayerDataMigration.IDENTITIES_FILE))) {
            identities.load(in);
        }
        assertNull(identities.getProperty("pending." + vanilla));
        PlayerDataMigration.prepare(world, vanilla, host, true);
        assertEquals(150, readPlayer(host).get("XpTotal").value);
    }

    @Test void licensedIdentityCannotBeClaimedByAnotherAccount() throws Exception {
        level(player(vanilla, 150));
        PlayerDataMigration.prepare(world, vanilla, vanilla, true);
        assertTrue(PlayerDataMigration.isBound(world, vanilla));
        PlayerDataMigration.prepare(world, vanilla, successor, true);
        assertFalse(Files.exists(playerPath(successor)));
        PlayerDataMigration.queueIdentity(world, vanilla, successor);
        assertEquals(vanilla, PlayerDataMigration.rememberedIdentity(world, vanilla));
    }

    @Test void anonymousIdentityCanBindWithoutChangingUuid() throws Exception {
        level(player(vanilla, 150));
        PlayerDataMigration.prepare(world, vanilla, vanilla, false);
        assertFalse(PlayerDataMigration.isBound(world, vanilla));
        PlayerDataMigration.prepare(world, vanilla, vanilla, true);
        assertTrue(PlayerDataMigration.isBound(world, vanilla));
        PlayerDataMigration.prepare(world, vanilla, successor, true);
        assertFalse(Files.exists(playerPath(successor)));
    }

    @Test void queuedFirstLoginCannotBeReassigned() throws Exception {
        level(player(vanilla, 150));
        PlayerDataMigration.prepare(world, vanilla, vanilla, false);
        PlayerDataMigration.queueIdentity(world, vanilla, host);
        assertThrows(IOException.class, () -> PlayerDataMigration.queueIdentity(world, vanilla, successor));
        PlayerDataMigration.prepare(world, vanilla, host, true);
        assertEquals(150, readPlayer(host).get("XpTotal").value);
    }

    @Test void embeddedProgressIsExportedWithUuidAndVanillaMetadataIntact() throws Exception {
        level(player(vanilla, 150));
        byte[] levelBefore = Files.readAllBytes(world.resolve("level.dat"));
        for (String folder : new String[]{"stats", "advancements"}) {
            Files.createDirectories(world.resolve(folder));
            Files.writeString(world.resolve(folder).resolve(vanilla + ".json"), "{\"progress\":42}");
        }
        PlayerDataMigration.prepare(world, vanilla, host);
        MigrationNbt migrated = readPlayer(host);
        assertEquals(host, migrated.playerUuid());
        assertEquals(150, migrated.get("XpTotal").value);
        assertEquals(3955, migrated.get("DataVersion").value);
        assertArrayEquals(new long[]{Long.MIN_VALUE, 42}, (long[]) migrated.get("ModdedProgress").value);
        assertArrayEquals(levelBefore, Files.readAllBytes(world.resolve("level.dat")), "Vanilla Data.Player is retained for uninstall");
        for (String folder : new String[]{"stats", "advancements"}) {
            assertArrayEquals(Files.readAllBytes(world.resolve(folder).resolve(vanilla + ".json")),
                    Files.readAllBytes(world.resolve(folder).resolve(host + ".json")));
        }
    }

    @Test void ordinarySingleplayerWithoutAccountExportsEmbeddedPlayer() throws Exception {
        level(player(vanilla, 120));
        PlayerDataMigration.prepare(world, vanilla, vanilla);
        assertEquals(120, readPlayer(vanilla).get("XpTotal").value);
    }

    @Test void existingDestinationInventoryAndStatsAreNeverOverwritten() throws Exception {
        level(player(vanilla, 100));
        savePlayer(host, 800);
        Files.createDirectories(world.resolve("stats"));
        Files.writeString(world.resolve("stats").resolve(host + ".json"), "new-progress");
        Files.writeString(world.resolve("stats").resolve(vanilla + ".json"), "old-progress");
        byte[] before = Files.readAllBytes(playerPath(host));
        PlayerDataMigration.prepare(world, vanilla, host);
        assertArrayEquals(before, Files.readAllBytes(playerPath(host)));
        assertEquals("new-progress", Files.readString(world.resolve("stats").resolve(host + ".json")));
    }

    @Test void successorNeverReceivesPreviousHostsEmbeddedInventory() throws Exception {
        level(player(host, 100));
        savePlayer(successor, 900);
        PlayerDataMigration.prepare(world, vanilla, successor);
        assertEquals(900, readPlayer(successor).get("XpTotal").value);
        assertEquals(100, readPlayer(host).get("XpTotal").value);
        assertFalse(Files.exists(playerPath(vanilla)));
    }

    @Test void successorWithoutPriorSaveStartsFreshAndPreservesPreviousHost() throws Exception {
        level(player(host, 100));
        PlayerDataMigration.prepare(world, vanilla, successor);
        assertFalse(Files.exists(playerPath(successor)), "A new player must not inherit the host's progress");
        assertEquals(100, readPlayer(host).get("XpTotal").value);
    }

    @Test void embeddedCurrentAccountBeatsStaleLauncherFileWhenDestinationIsMissing() throws Exception {
        level(player(host, 900));
        savePlayer(vanilla, 100);
        PlayerDataMigration.prepare(world, vanilla, host);
        assertEquals(900, readPlayer(host).get("XpTotal").value);
        assertEquals(100, readPlayer(vanilla).get("XpTotal").value);
    }

    @Test void conflictingDestinationStillPreservesEmbeddedOwnersOnlyCopy() throws Exception {
        level(player(vanilla, 100));
        savePlayer(host, 800);
        PlayerDataMigration.prepare(world, vanilla, host);
        assertEquals(800, readPlayer(host).get("XpTotal").value);
        assertEquals(100, readPlayer(vanilla).get("XpTotal").value);
    }

    @Test void handoffAndReturnKeepBothInventoriesAndDoNotRepeatMigration() throws Exception {
        level(player(vanilla, 100));
        PlayerDataMigration.prepare(world, vanilla, host);
        savePlayer(host, 200);
        savePlayer(successor, 900);
        level(player(host, 200));
        PlayerDataMigration.prepare(world, vanilla, successor); // same launcher, different PeerCraft account
        assertEquals(900, readPlayer(successor).get("XpTotal").value);
        level(player(successor, 950));
        savePlayer(successor, 950);
        PlayerDataMigration.prepare(world, vanilla, host);
        assertEquals(200, readPlayer(host).get("XpTotal").value);
        assertEquals(950, readPlayer(successor).get("XpTotal").value);
    }

    @Test void changingAccountCannotClaimSourceUuidTwice() throws Exception {
        level(player(vanilla, 100));
        PlayerDataMigration.prepare(world, vanilla, host);
        PlayerDataMigration.prepare(world, vanilla, successor);
        assertFalse(Files.exists(playerPath(successor)));
        assertEquals(100, readPlayer(host).get("XpTotal").value);
    }

    @Test void accountCanMigrateWorldPreviouslyOpenedWithoutAnAccount() throws Exception {
        level(player(vanilla, 100));
        PlayerDataMigration.prepare(world, vanilla, vanilla);
        PlayerDataMigration.prepare(world, vanilla, host);
        assertEquals(100, readPlayer(host).get("XpTotal").value);
        assertEquals(host, PlayerDataMigration.rememberedIdentity(world, vanilla));
        PlayerDataMigration.prepare(world, vanilla, successor);
        assertFalse(Files.exists(playerPath(successor)));
    }

    @Test void explicitGuestAssignmentPreservesSourceAndCreatesCompleteVerifiedBackup() throws Exception {
        level(player(host, 700)); savePlayer(vanilla, 150);
        Files.createDirectories(world.resolve("stats"));
        Files.writeString(world.resolve("stats/" + vanilla + ".json"), "{\"walk\":42}");
        Files.createDirectories(world.resolve("plugins/example"));
        Files.writeString(world.resolve("plugins/example/data.bin"), "extension-progress");
        byte[] original = Files.readAllBytes(playerPath(vanilla));
        Path backup = PlayerDataMigration.assignGuestInStoppedWorld(world, vanilla, successor);
        assertEquals(150, readPlayer(successor).get("XpTotal").value);
        assertArrayEquals(original, Files.readAllBytes(playerPath(vanilla)));
        assertEquals(successor, PlayerDataMigration.rememberedIdentity(world, vanilla));
        try (ZipFile zip = new ZipFile(backup.toFile())) {
            assertNotNull(zip.getEntry("plugins/example/data.bin"));
            assertNotNull(zip.getEntry("level.dat"));
            assertNotNull(zip.getEntry("playerdata/" + vanilla + ".dat"));
            assertNull(zip.getEntry("playerdata/" + successor + ".dat"));
        }
        try (java.util.stream.Stream<Path> entries = Files.list(world.resolve(".peercraft-backup"))) {
            assertEquals(1, entries.count(), "One full backup per explicit assignment");
        }
        assertThrows(IOException.class, () -> PlayerDataMigration.assignGuestInStoppedWorld(world, vanilla, host));
    }

    @Test void explicitGuestConflictChangesNeitherPlayerNorIdentity() throws Exception {
        level(player(host, 700)); savePlayer(vanilla, 150); savePlayer(successor, 900);
        byte[] source = Files.readAllBytes(playerPath(vanilla)), destination = Files.readAllBytes(playerPath(successor));
        assertThrows(IOException.class, () -> PlayerDataMigration.assignGuestInStoppedWorld(world, vanilla, successor));
        assertArrayEquals(source, Files.readAllBytes(playerPath(vanilla)));
        assertArrayEquals(destination, Files.readAllBytes(playerPath(successor)));
        assertFalse(Files.exists(world.resolve(PlayerDataMigration.IDENTITIES_FILE)));
    }

    @Test void explicitGuestAssignmentRefusesWorldWithNativeSessionLock() throws Exception {
        level(player(host, 700)); savePlayer(vanilla, 150);
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(world.resolve("session.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = channel.lock()) {
            assertThrows(IOException.class, () -> PlayerDataMigration.assignGuestInStoppedWorld(world, vanilla, successor));
        }
        assertFalse(Files.exists(playerPath(successor)));
    }

    @Test void verifiedGuestIsBoundWithoutChangingLiveProgressAndIsNotOfferedForAssignment() throws Exception {
        level(player(host, 700)); savePlayer(vanilla, 150); savePlayer(successor, 900);
        byte[] before = Files.readAllBytes(playerPath(vanilla));
        assertEquals(2, PlayerProgressCatalog.unassignedPlayers(world).size());
        PlayerDataMigration.markAuthenticatedGuest(world, vanilla);
        PlayerDataMigration.markAuthenticatedGuest(world, vanilla);
        assertArrayEquals(before, Files.readAllBytes(playerPath(vanilla)));
        assertTrue(PlayerDataMigration.isBound(world, vanilla));
        var choices = PlayerProgressCatalog.unassignedPlayers(world);
        assertEquals(1, choices.size()); assertEquals(successor, choices.get(0).id);
        assertEquals(900, choices.get(0).experience); assertEquals("minecraft:diamond", choices.get(0).inventory);
        assertThrows(IOException.class, () -> PlayerDataMigration.assignGuestInStoppedWorld(world, vanilla, host));
    }

    @Test void guestAssignmentRejectsSymlinkedSessionLock() throws Exception {
        level(player(host, 700)); savePlayer(vanilla, 150);
        Path target = world.resolve("unrelated-lock"); Files.writeString(target, "preserve");
        Files.createSymbolicLink(world.resolve("session.lock"), target);
        assertThrows(IOException.class, () -> PlayerDataMigration.assignGuestInStoppedWorld(world, vanilla, successor));
        assertEquals("preserve", Files.readString(target)); assertFalse(Files.exists(playerPath(successor)));
    }

    @Test void migratedGuestAndDistinctOwnerSurviveProductionArchiveInstallAndReturn() throws Exception {
        level(player(host, 700)); savePlayer(vanilla, 150);
        MigrationNbt guest = readPlayer(vanilla);
        guest.compound().put("EnderItems", new MigrationNbt(9, Arrays.asList(new MigrationNbt(10, null),
                compound("id", new MigrationNbt(8, "minecraft:emerald"), "Count", new MigrationNbt(1, (byte) 13)))));
        Files.write(playerPath(vanilla), guest.compressed());
        for (String folder : new String[]{"stats", "advancements"}) {
            Files.createDirectories(world.resolve(folder));
            Files.writeString(world.resolve(folder).resolve(vanilla + ".json"), "{\"progress\":150}");
            Files.writeString(world.resolve(folder).resolve(host + ".json"), "{\"progress\":700}");
        }
        Files.createDirectories(world.resolve("plugins/example"));
        Files.writeString(world.resolve("plugins/example/data"), "world-extension");
        Path petRegion = world.resolve("entities/r.0.0.mca"); writeRegion(petRegion, petChunk(), 2, false);
        PlayerDataMigration.prepare(world, host, host, true);
        net.peercraft.client.handoff.HandoffOwnerPolicy.write(world, host);
        PlayerDataMigration.assignGuestInStoppedWorld(world, vanilla, successor);
        byte[] owner = Files.readAllBytes(playerPath(host));
        byte[] assigned = Files.readAllBytes(playerPath(successor));
        byte[] metadata = Files.readAllBytes(world.resolve(PlayerDataMigration.IDENTITIES_FILE));
        byte[] originalLevel = Files.readAllBytes(world.resolve("level.dat"));
        Path newHost = transfer.resolve("new-host");
        installSnapshot(world, newHost, "outbound");
        PlayerDataMigration.prepare(newHost, vanilla, successor, true);
        Path returned = transfer.resolve("returned");
        installSnapshot(newHost, returned, "return");
        PlayerDataMigration.prepare(returned, host, host, true);
        for (Path installed : Arrays.asList(newHost, returned)) {
            assertArrayEquals(owner, Files.readAllBytes(installed.resolve("playerdata/" + host + ".dat")));
            assertArrayEquals(assigned, Files.readAllBytes(installed.resolve("playerdata/" + successor + ".dat")));
            assertArrayEquals(metadata, Files.readAllBytes(installed.resolve(PlayerDataMigration.IDENTITIES_FILE)));
            assertArrayEquals(originalLevel, Files.readAllBytes(installed.resolve("level.dat")));
            for (String folder : new String[]{"stats", "advancements"}) {
                assertEquals("{\"progress\":150}", Files.readString(installed.resolve(folder).resolve(successor + ".json")));
                assertEquals("{\"progress\":700}", Files.readString(installed.resolve(folder).resolve(host + ".json")));
            }
            assertEquals(successor, MigrationNbt.uuid(readRegion(installed.resolve("entities/r.0.0.mca"), false).get("Pet").get("Owner")));
            assertEquals("world-extension", Files.readString(installed.resolve("plugins/example/data")));
            assertEquals(host, net.peercraft.client.handoff.HandoffOwnerPolicy.read(installed));
            assertFalse(Files.exists(installed.resolve(".peercraft-backup")));
        }
    }

    private void installSnapshot(Path source, Path target, String phase) throws Exception {
        Path archive = transfer.resolve(phase + ".zip"), staging = transfer.resolve(phase + "-staging");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            net.peercraft.client.handoff.WorldArchiveFiles.write(source, zip);
        }
        net.peercraft.network.handoff.WorldInstall.unpack(archive, staging, 64 * 1024 * 1024L);
        net.peercraft.network.handoff.WorldInstall.replace(staging, target, transfer.resolve(phase + "-backup"),
                transfer.resolve(phase + "-journal"), true, true, () -> { });
    }

    @Test void successorAccountIsBoundEvenWhenLauncherIdentityBelongsToFormerHost() throws Exception {
        level(player(vanilla, 150));
        PlayerDataMigration.prepare(world, vanilla, host, true);
        byte[] formerHost = Files.readAllBytes(playerPath(host));
        PlayerDataMigration.prepare(world, vanilla, successor, true);
        assertTrue(PlayerDataMigration.isBound(world, successor));
        assertEquals(host, PlayerDataMigration.rememberedIdentity(world, vanilla));
        assertArrayEquals(formerHost, Files.readAllBytes(playerPath(host)));
        assertFalse(Files.exists(playerPath(successor)), "A new host never inherits former host inventory");
        savePlayer(successor, 900);
        assertTrue(PlayerProgressCatalog.unassignedPlayers(world).isEmpty(), "Verified successor is not an anonymous save");
    }

    @Test void legacyUuidMostLeastSurviveMigration() throws Exception {
        MigrationNbt old = player(vanilla, 120);
        old.compound().remove("UUID");
        old.compound().put("UUIDMost", new MigrationNbt(4, vanilla.getMostSignificantBits()));
        old.compound().put("UUIDLeast", new MigrationNbt(4, vanilla.getLeastSignificantBits()));
        level(old);
        PlayerDataMigration.prepare(world, vanilla, host);
        MigrationNbt migrated = readPlayer(host);
        assertEquals(host, migrated.playerUuid());
        assertNull(migrated.get("UUID"));
    }

    @Test void legacyOwnerWithoutVerifiableUuidFailsWithoutChangingWorld() throws Exception {
        MigrationNbt old = player(vanilla, 120); old.compound().remove("UUID");
        level(old);
        byte[] before = Files.readAllBytes(world.resolve("level.dat"));
        assertThrows(IOException.class, () -> PlayerDataMigration.prepare(world, vanilla, host));
        assertFalse(Files.exists(playerPath(host)));
        assertFalse(Files.exists(world.resolve(PlayerDataMigration.IDENTITIES_FILE)));
        assertArrayEquals(before, Files.readAllBytes(world.resolve("level.dat")));
    }

    @Test void regionsInAllDimensionsRewriteOnlyMatchingOwnerReferences() throws Exception {
        level(player(vanilla, 120));
        List<Path> files = Arrays.asList(world.resolve("region/r.0.0.mca"),
                world.resolve("DIM-1/region/r.0.0.mca"), world.resolve("DIM1/entities/r.0.0.mca"),
                world.resolve("dimensions/example/moon/entities/r.0.0.mca"));
        for (int i = 0; i < files.size(); i++) writeRegion(files.get(i), petChunk(), i % 3 + 1, false);
        PlayerDataMigration.prepare(world, vanilla, host);
        for (Path file : files) {
            MigrationNbt pet = readRegion(file, false).get("Pet");
            assertEquals(host, MigrationNbt.uuid(pet.get("Owner")));
            assertEquals(host, MigrationNbt.uuid(pet.get("OwnerUUID")));
            assertEquals(host, MigrationNbt.uuid(pet.get("OwnerUuid")));
            assertEquals(successor, MigrationNbt.uuid(pet.get("UnrelatedOwner")));
            assertEquals(vanilla, pet.playerUuid(), "The entity's own UUID must stay unchanged");
        }
    }

    @Test void externalEntityChunkIsRewrittenAndBackupIsRetained() throws Exception {
        level(player(vanilla, 120));
        Path region = world.resolve("entities/r.0.0.mca");
        writeRegion(region, petChunk(), 2, true);
        byte[] before = Files.readAllBytes(region.resolveSibling("c.0.0.mcc"));
        PlayerDataMigration.prepare(world, vanilla, host);
        assertEquals(host, MigrationNbt.uuid(readRegion(region, true).get("Pet").get("Owner")));
        try (var backups = Files.walk(world.resolve(".peercraft-player-migration"))) {
            assertTrue(backups.filter(p -> p.toString().endsWith(".original")).anyMatch(p -> {
                try { return Arrays.equals(before, Files.readAllBytes(p)); } catch (IOException e) { return false; }
            }));
        }
    }

    @Test void corruptRegionPreventsAnyPartialMigration() throws Exception {
        level(player(vanilla, 120));
        Path region = world.resolve("region/r.0.0.mca");
        Files.createDirectories(region.getParent()); Files.write(region, new byte[10]);
        assertThrows(IOException.class, () -> PlayerDataMigration.prepare(world, vanilla, host));
        assertFalse(Files.exists(playerPath(host)));
        assertFalse(Files.exists(world.resolve(PlayerDataMigration.IDENTITIES_FILE)));
        assertArrayEquals(new byte[10], Files.readAllBytes(region));
    }

    @Test void missingGzipTrailerIsRejectedBeforeAnyMigrationWrite() throws Exception {
        level(player(vanilla, 120));
        Path file = world.resolve("level.dat");
        byte[] bytes = Files.readAllBytes(file);
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 2);
        Files.write(file, truncated);
        assertThrows(IOException.class, () -> PlayerDataMigration.prepare(world, vanilla, host));
        assertFalse(Files.exists(playerPath(host)));
        assertFalse(Files.exists(world.resolve(PlayerDataMigration.IDENTITIES_FILE)));
        assertArrayEquals(truncated, Files.readAllBytes(file));
    }

    @Test void namedRegionNbtRootIsPreservedWhenEditingPetOwner() throws Exception {
        level(player(vanilla, 120));
        ByteArrayOutputStream ordinary = new ByteArrayOutputStream(); petChunk().write(ordinary);
        ByteArrayOutputStream named = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(named)) {
            out.writeByte(10); out.writeUTF("CustomEntities");
            out.write(ordinary.toByteArray(), 3, ordinary.size() - 3);
        }
        MigrationNbt chunk = MigrationNbt.read(new ByteArrayInputStream(named.toByteArray()));
        Path region = world.resolve("entities/r.0.0.mca");
        writeRegion(region, chunk, 2, false);
        PlayerDataMigration.prepare(world, vanilla, host);
        try (RandomAccessFile in = new RandomAccessFile(region.toFile(), "r")) {
            int location = in.readInt(); in.seek((location >>> 8) * 4096L);
            int length = in.readInt(); assertEquals(2, in.readUnsignedByte());
            byte[] bytes = new byte[length - 1]; in.readFully(bytes);
            try (DataInputStream nbt = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes)))) {
                assertEquals(10, nbt.readUnsignedByte()); assertEquals("CustomEntities", nbt.readUTF());
            }
        }
        assertEquals(host, MigrationNbt.uuid(readRegion(region, false).get("Pet").get("Owner")));
    }

    @Test void interruptedCommitRestoresOriginalsBeforeRetry() throws Exception {
        Path backup = world.resolve(".peercraft-player-migration/backup-test");
        Files.createDirectories(backup);
        Files.writeString(world.resolve("existing"), "partially-replaced");
        Files.writeString(world.resolve("created"), "partial-new-file");
        Files.writeString(backup.resolve("0.original"), "original");
        Files.writeString(backup.resolve("journal.properties"), "count=2\n0=existing\n0.existed=true\n1=created\n1.existed=false\n");
        Files.createFile(backup.resolve("pending"));
        MigrationTransaction.recover(world.toAbsolutePath().normalize());
        assertEquals("original", Files.readString(world.resolve("existing")));
        assertFalse(Files.exists(world.resolve("created")));
        assertFalse(Files.exists(backup.resolve("pending")));
    }

    private MigrationNbt petChunk() {
        return compound("Pet", compound("UUID", MigrationNbt.uuidTag(vanilla, 11),
                "Owner", MigrationNbt.uuidTag(vanilla, 11), "OwnerUUID", MigrationNbt.uuidTag(vanilla, 8),
                "OwnerUuid", MigrationNbt.uuidTag(vanilla, 11), "UnrelatedOwner", MigrationNbt.uuidTag(successor, 11)));
    }

    private static void writeRegion(Path file, MigrationNbt tag, int codec, boolean external) throws IOException {
        Files.createDirectories(file.getParent());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream out = codec == 1 ? new GZIPOutputStream(bytes) : codec == 2 ? new DeflaterOutputStream(bytes) : bytes;
        tag.write(out); out.close();
        byte[] payload = bytes.toByteArray();
        try (RandomAccessFile region = new RandomAccessFile(file.toFile(), "rw")) {
            region.setLength(12288); region.writeInt((2 << 8) | 1);
            region.seek(8192); region.writeInt(external ? 1 : payload.length + 1);
            region.writeByte(codec | (external ? 128 : 0));
            if (!external) region.write(payload);
        }
        if (external) Files.write(file.resolveSibling("c.0.0.mcc"), payload);
    }

    private static MigrationNbt readRegion(Path file, boolean external) throws IOException {
        byte[] bytes; int codec;
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            int location = in.readInt(); in.seek((location >>> 8) * 4096L);
            int length = in.readInt(); codec = in.readUnsignedByte() & 127;
            if (external) bytes = Files.readAllBytes(file.resolveSibling("c.0.0.mcc"));
            else { bytes = new byte[length - 1]; in.readFully(bytes); }
        }
        InputStream in = new ByteArrayInputStream(bytes);
        if (codec == 1) in = new GZIPInputStream(in);
        else if (codec == 2) in = new InflaterInputStream(in);
        try (InputStream decoded = in) { return MigrationNbt.read(decoded); }
    }
}
