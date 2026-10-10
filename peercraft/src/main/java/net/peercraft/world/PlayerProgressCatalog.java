package net.peercraft.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Read-only choices for a host; names and inventory previews are not ownership proofs. */
public final class PlayerProgressCatalog {
    private PlayerProgressCatalog() { }
    public static final class World {
        public final Path directory; public final String name;
        World(Path directory, String name) { this.directory = directory; this.name = name; }
    }
    public static final class Player {
        public final UUID id; public final int experience; public final String inventory;
        Player(UUID id, int experience, String inventory) { this.id = id; this.experience = experience; this.inventory = inventory; }
    }
    public static List<World> worlds(Path saves) throws IOException {
        List<World> worlds = new ArrayList<World>();
        if (!Files.isDirectory(saves)) return worlds;
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(saves)) {
            for (Path directory : directories) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || directory.getFileName().toString().startsWith(".")) continue;
                Path level = directory.resolve("level.dat");
                if (!Files.isRegularFile(level, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = directory.getFileName().toString();
                try (InputStream input = Files.newInputStream(level)) {
                    MigrationNbt data = MigrationNbt.compressed(input).get("Data");
                    MigrationNbt title = data == null ? null : data.get("LevelName");
                    if (title != null && title.type == 8) name = (String) title.value;
                }
                worlds.add(new World(directory, name));
            }
        }
        Collections.sort(worlds, Comparator.comparing(world -> world.name)); return worlds;
    }
    @SuppressWarnings("unchecked")
    public static List<Player> unassignedPlayers(Path world) throws IOException {
        Properties identities = new Properties();
        Path marker = world.resolve(PlayerDataMigration.IDENTITIES_FILE);
        if (Files.exists(marker)) try (InputStream input = Files.newInputStream(marker)) { identities.load(input); }
        List<Player> players = new ArrayList<Player>(); Path playerdata = world.resolve("playerdata");
        if (!Files.isDirectory(playerdata)) return players;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(playerdata, "*.dat")) {
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid player save file");
                UUID id;
                try { String name = file.getFileName().toString(); id = UUID.fromString(name.substring(0, name.length() - 4)); }
                catch (IllegalArgumentException invalid) { continue; }
                String assigned = identities.getProperty(id.toString());
                if (assigned != null && (!assigned.equals(id.toString()) || Boolean.parseBoolean(identities.getProperty("bound." + id)))) continue;
                MigrationNbt player;
                try (InputStream input = Files.newInputStream(file)) { player = MigrationNbt.compressed(input); }
                UUID savedId = player.playerUuid();
                if (savedId != null && !id.equals(savedId)) throw new IOException("Player UUID differs from save file");
                MigrationNbt xp = player.get("XpTotal"), inventory = player.get("Inventory");
                List<String> items = new ArrayList<String>();
                if (inventory != null && inventory.type == 9) {
                    for (MigrationNbt item : (List<MigrationNbt>) inventory.value) {
                        if (item.type != 10 || item.value == null) continue;
                        MigrationNbt name = item.get("id");
                        if (name != null && name.type == 8 && items.size() < 3) items.add(name.value.toString());
                    }
                }
                players.add(new Player(id, xp != null && xp.value instanceof Number ? ((Number) xp.value).intValue() : 0, String.join(", ", items)));
            }
        }
        Collections.sort(players, Comparator.comparing(player -> player.id.toString())); return players;
    }
}
