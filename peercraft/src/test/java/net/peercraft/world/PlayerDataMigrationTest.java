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
