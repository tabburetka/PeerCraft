package net.peercraft.client.handoff;

import net.peercraft.network.handoff.*;
import net.peercraft.platform.Services;
import net.peercraft.platform.services.PlatformMod;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Non-native persistence and execution checks shared by all four real adapters. */
public abstract class SafeHandoffSupport {
    public abstract Path savesDirectory();
    public abstract boolean modernWorldLock();
    public abstract void render(Runnable action);
    public static final class Grant {
        public final UUID session; public final long epoch; public final byte[] key;
        Grant(UUID session, long epoch, byte[] key) { this.session = session; this.epoch = epoch; this.key = key; }
    }
    static Path stateDirectory() { return Services.PLATFORM.getConfigDir().resolve("peercraft/handoff-journals"); }
    private static Path grantFile(Path world) throws IOException {
        byte[] hash;
        try { hash = MessageDigest.getInstance("SHA-256").digest(world.toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
        StringBuilder name = new StringBuilder(); for (byte b : hash) name.append(String.format("%02x", b & 255));
        return stateDirectory().resolve(name + ".grant");
    }
    public Grant hostingGrant(Path world) throws IOException {
        Path file = grantFile(world); if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4096) throw new IOException("Invalid local hosting grant");
        Properties p = new Properties(); try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        try {
            if (!world.toAbsolutePath().normalize().toString().equals(p.getProperty("world"))) throw new IOException("Grant world changed");
            byte[] key = Base64.getDecoder().decode(p.getProperty("key")); long epoch = Long.parseLong(p.getProperty("epoch"));
            if (key.length != 32 || epoch < 0) throw new IOException("Invalid grant authority");
            return new Grant(UUID.fromString(p.getProperty("session")), epoch, key);
        } catch (RuntimeException corrupt) { throw new IOException("Unreadable local hosting grant", corrupt); }
    }
    public void forkStaleWorld(Path world) throws IOException {
        Files.deleteIfExists(grantFile(world));
        if (Files.isDirectory(stateDirectory())) HandoffFiles.forceDirectory(stateDirectory());
    }
    public void rememberGrant(Path world, UUID session, long epoch, byte[] key) throws IOException {
        Files.createDirectories(stateDirectory()); Path file = grantFile(world), tmp = Files.createTempFile(stateDirectory(), ".grant-", ".tmp");
        Properties p = new Properties(); p.setProperty("world", world.toAbsolutePath().normalize().toString());
        p.setProperty("session", session.toString()); p.setProperty("epoch", Long.toString(epoch)); p.setProperty("key", Base64.getEncoder().encodeToString(key));
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) { p.store(out, "PeerCraft active hosting session"); }
            try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.WRITE)) { out.force(true); }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); HandoffFiles.forceDirectory(stateDirectory());
        } finally { Files.deleteIfExists(tmp); }
    }
    public HostExecutionManifest manifest() throws IOException {
        List<PlatformMod> mods = Services.PLATFORM.getInstalledMods(); String minecraft = "", loader = "", version = "";
        for (PlatformMod mod : mods) {
            if (mod.id().equalsIgnoreCase("minecraft")) minecraft = mod.version();
            if (mod.id().equalsIgnoreCase("fabricloader") || mod.id().equalsIgnoreCase("forge") || mod.id().equalsIgnoreCase("neoforge")) {
                loader = mod.id().toLowerCase(Locale.ROOT); version = mod.version();
            }
        }
        if (minecraft.isEmpty() || loader.isEmpty() || version.isEmpty()) throw new IOException("Execution platform is incomplete");
        Path rules = Services.PLATFORM.getConfigDir().resolve("peercraft/handoff-external-configs.txt");
        List<String> external = Collections.emptyList();
        if (Files.exists(rules)) {
            if (!Files.isRegularFile(rules, LinkOption.NOFOLLOW_LINKS) || Files.size(rules) > 65536) throw new IOException("Invalid external config rules");
            external = new ArrayList<>();
            for (String line : Files.readAllLines(rules, java.nio.charset.StandardCharsets.UTF_8)) {
                String path = line.trim(); if (!path.isEmpty() && !path.startsWith("#")) external.add(path);
            }
        }
        return HostManifestCapture.capture(minecraft, loader, version, mods, Services.PLATFORM.getConfigDir(), external);
    }
    public void recoverJournals() { HandoffNetworkRecovery.start(stateDirectory(), savesDirectory(), this::rememberGrant, modernWorldLock()); }
}
