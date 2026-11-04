package net.peercraft.modsync.install;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.swing.*;

/** Standalone Java 8 installer. No loader, Minecraft, Gson, or logging dependencies. */
public final class ModSyncInstaller {
    public static final String PLAN = "plan.properties";
    private ModSyncInstaller() { }

    public static final class Entry {
        public final String id, version, fileName, sha512, oldFileName, oldVersion, oldSha512, source;
        public Entry(String id, String version, String name, String hash, String oldName, String oldVersion, String oldHash, String source) {
            this.id = id; this.version = version; this.fileName = name; this.sha512 = hash;
            this.oldFileName = oldName; this.oldVersion = oldVersion; this.oldSha512 = oldHash; this.source = source;
        }
        /** Removes {@code oldFileName} without installing anything in its place. */
        public static Entry removal(String id, String oldName, String oldVersion, String oldHash) {
            return new Entry(id, "", "", "", oldName, oldVersion, oldHash, "remove");
        }
        public boolean isRemoval() { return fileName.isEmpty(); }
    }

    public static Path prepare(Path operations, Path mods, List<Entry> entries, List<Path> verified, String room, String language) throws IOException {
        return prepare(operations, mods, entries, verified, room, language, inventory(mods));
    }

    public static Map<String, String> inventory(Path mods) throws IOException {
        Map<String, String> files = new TreeMap<String, String>();
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path jar : jars) files.put(jar.getFileName().toString(), hash(jar));
        }
        return files;
    }

    public static Path prepare(Path operations, Path mods, List<Entry> entries, List<Path> verified, String room, String language,
                               Map<String, String> expectedInventory) throws IOException {
        int installs = 0; boolean removals = false;
        for (Entry e : entries) {
            if (!e.isRemoval()) installs++;
            else if (e.oldFileName.isEmpty()) throw new IOException("Removal without a source jar: " + e.id);
            else removals = true;
        }
        if (entries.isEmpty() || installs != verified.size()) throw new IOException("Empty or incomplete install plan");
        Files.createDirectories(operations);
        operations = operations.toRealPath();
        Path transaction = Files.createDirectory(operations.resolve(UUID.randomUUID().toString()));
        Files.createDirectory(transaction.resolve("staged"));
        Files.createDirectory(transaction.resolve("backups"));
        Properties plan = new Properties();
        plan.setProperty("format", removals ? "2" : "1"); plan.setProperty("state", "PREPARED");
        plan.setProperty("mods", mods.toRealPath().toString());
        plan.setProperty("count", Integer.toString(entries.size()));
        plan.setProperty("room", room == null ? "" : room);
        plan.setProperty("language", language == null ? "en" : language);
        plan.setProperty("createdAt", Long.toString(System.currentTimeMillis()));
        plan.setProperty("inventory.count", Integer.toString(expectedInventory.size()));
        int inventoryIndex = 0;
        for (Map.Entry<String, String> file : expectedInventory.entrySet()) {
            plan.setProperty("inventory." + inventoryIndex + ".file", name(file.getKey()));
            plan.setProperty("inventory." + inventoryIndex + ".sha512", file.getValue()); inventoryIndex++;
        }
        int next = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i); String p = i + ".";
            plan.setProperty(p + "id", e.id); plan.setProperty(p + "version", e.version);
            plan.setProperty(p + "file", e.fileName); plan.setProperty(p + "sha512", e.sha512);
            plan.setProperty(p + "oldFile", e.oldFileName); plan.setProperty(p + "oldVersion", e.oldVersion);
            plan.setProperty(p + "oldSha512", e.oldSha512); plan.setProperty(p + "source", e.source);
            if (e.isRemoval()) { plan.setProperty(p + "sizeBytes", "0"); continue; }
            Path file = verified.get(next++);
            plan.setProperty(p + "sizeBytes", Long.toString(Files.size(file)));
            if (!e.sha512.equals(hash(file))) throw new IOException("Downloaded file changed: " + e.id);
            Files.copy(file, transaction.resolve("staged").resolve(i + ".jar"));
            force(transaction.resolve("staged").resolve(i + ".jar"));
        }
        save(transaction.resolve(PLAN), plan);
        preflight(transaction, plan);
        StringBuilder restore = new StringBuilder("PeerCraft Mod Sync\n\nClose Minecraft before restoring files. / Закройте Minecraft перед восстановлением.\n");
        restore.append("mods: ").append(mods.toRealPath()).append("\n\n");
        for (Entry e : entries) {
            if (e.isRemoval()) {
                restore.append("Removed / Удалён: backups/").append(e.oldFileName).append(" SHA-512 ").append(e.oldSha512).append('\n');
                continue;
            }
            restore.append("Installed / Установлен: ").append(e.fileName).append(" SHA-512 ").append(e.sha512).append('\n');
            if (!e.oldFileName.isEmpty()) restore.append("Original / Исходный: backups/").append(e.oldFileName).append(" SHA-512 ").append(e.oldSha512).append('\n');
        }
        restore.append("\nTo restore: remove only the listed installed files if their hashes still match, then COPY the original backups to mods using their original names. Keep backups.\n")
                .append("Для восстановления удалите только перечисленные установленные JAR, если их хеши не изменились, затем СКОПИРУЙТЕ исходные JAR из backups в mods с прежними именами. Сохраните резервные копии.\n")
                .append("Check plan.properties state first. APPLIED means installed; ROLLED_BACK means the originals were already restored. PREPARED/ARMED/CANCELLED/FAILED may have changed nothing.\n")
                .append("Сначала проверьте state в plan.properties. APPLIED — установлено; ROLLED_BACK — исходные файлы уже восстановлены. PREPARED/ARMED/CANCELLED/FAILED могут означать отсутствие изменений.\n");
        Files.write(transaction.resolve("RESTORE.txt"), restore.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        return transaction;
    }

    public static Properties read(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        return p;
    }
    public static void save(Path file, Properties p) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
            p.store(out, "PeerCraft Mod Sync - retain for recovery"); out.getFD().sync();
        }
        try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    public static String hash(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file: " + file);
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-512"); } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) != -1) md.update(b, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : md.digest()) out.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return out.toString();
    }
    public static String name(String value) throws IOException {
        if (value == null || value.isEmpty() || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.contains("..") || !value.endsWith(".jar") || value.indexOf(':') >= 0)
            throw new IOException("Unsafe jar filename: " + value);
        return value;
    }
    private static Path mods(Properties plan) throws IOException {
        Path root = Paths.get(plan.getProperty("mods", "")).toAbsolutePath().normalize();
        if (!root.equals(root.toRealPath())) throw new IOException("Mod directory changed or contains symbolic links: " + root);
        return root;
    }
    private static int count(Properties plan) throws IOException {
        String format = plan.getProperty("format");
        if (!"1".equals(format) && !"2".equals(format)) throw new IOException("Unknown transaction format");
        try {
            int n = Integer.parseInt(plan.getProperty("count", "0"));
            if (n < 1 || n > 4096) throw new IOException("Invalid transaction size");
            return n;
        } catch (NumberFormatException e) { throw new IOException(e); }
    }
    private static String prop(Properties p, int i, String key) { return p.getProperty(i + "." + key, ""); }
    /** Format 2 entry with no target: the old jar is removed and nothing is installed. */
    private static boolean removal(Properties p, int i) { return "2".equals(p.getProperty("format")) && prop(p, i, "file").isEmpty(); }
    private static void digest(String hash) throws IOException {
        if (!hash.matches("[0-9a-f]{128}")) throw new IOException("Invalid SHA-512");
    }
    public static void preflight(Path transaction, Properties plan) throws IOException {
        Path root = mods(plan); int n = count(plan);
        if (!transaction.toAbsolutePath().normalize().equals(transaction.toRealPath())
                || Files.isSymbolicLink(transaction.resolve("staged")) || Files.isSymbolicLink(transaction.resolve("backups")))
            throw new IOException("Unsafe transaction directory");
        Map<String, String> expected = new TreeMap<String, String>();
        int inventoryCount;
        try { inventoryCount = Integer.parseInt(plan.getProperty("inventory.count", "-1")); }
        catch (NumberFormatException e) { throw new IOException("Invalid inventory", e); }
        if (inventoryCount < 0 || inventoryCount > 10000) throw new IOException("Invalid inventory size");
        for (int i = 0; i < inventoryCount; i++) {
            String file = name(plan.getProperty("inventory." + i + ".file"));
            String hash = plan.getProperty("inventory." + i + ".sha512", ""); digest(hash);
            if (expected.put(file, hash) != null) throw new IOException("Duplicate inventory file");
        }
        if (!expected.equals(inventory(root))) throw new IOException("The mods folder changed after confirmation; review synchronization again");
        Set<String> targets = new HashSet<String>(), oldNames = new HashSet<String>(), ids = new HashSet<String>();
        for (int i = 0; i < n; i++) {
            String old = prop(plan, i, "oldFile");
            if (removal(plan, i)) {
                name(old); digest(prop(plan, i, "oldSha512"));
                if (!ids.add(prop(plan, i, "id")) || !oldNames.add(old.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate mod or source jar");
                if (!prop(plan, i, "oldSha512").equals(hash(root.resolve(old)))) throw new IOException("Installed file changed: " + old);
                continue;
            }
            String target = name(prop(plan, i, "file"));
            String sha = prop(plan, i, "sha512"); digest(sha);
            if (!ids.add(prop(plan, i, "id")) || !targets.add(target.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate mod or target");
            if (!sha.equals(hash(transaction.resolve("staged").resolve(i + ".jar")))) throw new IOException("Staged file changed: " + target);
            if (!old.isEmpty()) {
                name(old); digest(prop(plan, i, "oldSha512"));
                if (!oldNames.add(old.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate source jar");
                if (!prop(plan, i, "oldSha512").equals(hash(root.resolve(old)))) throw new IOException("Installed file changed: " + old);
            }
            Path dst = root.resolve(target);
            if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS) && !target.equals(old)) throw new IOException("Target already exists: " + target);
        }
        // No transaction may install over a different operation's original file.
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) if (i != j && !removal(plan, i)
                && prop(plan, i, "file").equalsIgnoreCase(prop(plan, j, "oldFile"))) throw new IOException("Overlapping operations");
    }

    /** Full batch validation and backups precede the first mutation of mods/. */
    public static void apply(Path transaction) throws IOException {
        Properties p = read(transaction.resolve(PLAN));
        String state = p.getProperty("state", "");
        if ("APPLYING".equals(state) || "RECOVERY_REQUIRED".equals(state)) { rollback(transaction, p); return; }
        if (!"ARMED".equals(state) && !"PREPARED".equals(state)) throw new IOException("Transaction is not ready: " + state);
        boolean mutating = false;
        try {
            preflight(transaction, p);
            Path root = mods(p); int n = count(p);
            for (int i = 0; i < n; i++) {
                String old = prop(p, i, "oldFile");
                if (old.isEmpty()) continue;
                Path backup = transaction.resolve("backups").resolve(name(old));
                // CREATE_NEW: no overwrite of a previous copy, including a manually recovered file.
                Files.copy(root.resolve(old), backup);
                force(backup);
                if (!prop(p, i, "oldSha512").equals(hash(backup))) throw new IOException("Backup verification failed: " + old);
            }
            p.setProperty("state", "APPLYING"); save(transaction.resolve(PLAN), p); mutating = true;
            for (int i = 0; i < n; i++) {
                String old = prop(p, i, "oldFile"); if (old.isEmpty()) continue;
                if (!prop(p, i, "oldSha512").equals(hash(root.resolve(old)))) throw new IOException("Original changed before removal: " + old);
                p.setProperty(i + ".remove", "intent"); save(transaction.resolve(PLAN), p);
                Files.delete(root.resolve(old));
            }
            for (int i = 0; i < n; i++) {
                if (removal(p, i)) continue;
                String target = name(prop(p, i, "file"));
                p.setProperty(i + ".install", "intent"); save(transaction.resolve(PLAN), p);
                Path tmp = root.resolve(".peercraft-" + transaction.getFileName() + "-" + i + ".tmp");
                Files.copy(transaction.resolve("staged").resolve(i + ".jar"), tmp);
                force(tmp);
                if (!prop(p, i, "sha512").equals(hash(tmp))) throw new IOException("Install verification failed: " + target);
                if (Files.exists(root.resolve(target), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Target appeared: " + target);
                // No REPLACE_EXISTING, and no ATOMIC_MOVE (on some OSes it implicitly replaces an existing target).
                Files.move(tmp, root.resolve(target));
            }
            p.setProperty("state", "APPLIED"); p.setProperty("completedAt", Long.toString(System.currentTimeMillis()));
            save(transaction.resolve(PLAN), p);
        } catch (IOException | RuntimeException failure) {
            p.setProperty("error", failure.toString());
            if (mutating) {
                try { rollback(transaction, p); } catch (IOException restore) { failure.addSuppressed(restore); }
            } else { p.setProperty("state", "FAILED"); save(transaction.resolve(PLAN), p); }
            throw new IOException("Mod Sync failed; transaction retained at " + transaction, failure);
        }
    }

    /** Idempotent: only removes hash-matching files created by this transaction. */
    public static void rollback(Path transaction, Properties p) throws IOException {
        Path root = mods(p); int n = count(p);
        IOException failure = null;
        for (int i = n - 1; i >= 0; i--) {
            try {
                Path dst = removal(p, i) ? null : root.resolve(name(prop(p, i, "file")));
                if (dst != null && p.containsKey(i + ".install") && Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) {
                    String actual = hash(dst);
                    if (actual.equals(prop(p, i, "sha512"))) Files.delete(dst);
                    else if (!prop(p, i, "file").equals(prop(p, i, "oldFile")) || !actual.equals(prop(p, i, "oldSha512")))
                        throw new IOException("Preserving changed target: " + dst);
                }
                String old = prop(p, i, "oldFile");
                if (!old.isEmpty() && p.containsKey(i + ".remove")) {
                    Path original = root.resolve(name(old));
                    if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                        if (!prop(p, i, "oldSha512").equals(hash(original))) throw new IOException("Preserving changed original: " + original);
                    } else {
                        Path backup = transaction.resolve("backups").resolve(name(old));
                        if (!prop(p, i, "oldSha512").equals(hash(backup))) throw new IOException("Backup damaged: " + backup);
                        Files.copy(backup, original);
                        force(original);
                    }
                }
            } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        p.setProperty("state", failure == null ? "ROLLED_BACK" : "RECOVERY_REQUIRED");
        if (failure != null) p.setProperty("recoveryError", failure.toString());
        save(transaction.resolve(PLAN), p);
        if (failure != null) throw failure;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("transaction-directory nonce");
        Path transaction = Paths.get(args[0]).toRealPath();
        Properties p = read(transaction.resolve(PLAN));
        if (!args[1].equals(p.getProperty("nonce"))) throw new IOException("Wrong transaction nonce");
        Path operations = transaction.getParent();
        // This lock remains open in the game JVM. It is deliberately never closed by a shutdown hook.
        try (FileChannel game = FileChannel.open(operations.resolve("game.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            Properties ready = new Properties(); ready.setProperty("nonce", args[1]);
            save(transaction.resolve("ready.properties"), ready);
            try (FileLock released = game.lock()) {
                p = read(transaction.resolve(PLAN));
                if ("CANCELLED".equals(p.getProperty("state"))) return;
                if (!"ARMED".equals(p.getProperty("state")) && !"APPLYING".equals(p.getProperty("state")) && !"RECOVERY_REQUIRED".equals(p.getProperty("state"))) return;
                try { apply(transaction); }
                catch (Exception e) { e.printStackTrace(); }
            }
        }
        p = read(transaction.resolve(PLAN));
        boolean ok = "APPLIED".equals(p.getProperty("state"));
        boolean ru = p.getProperty("language", "en").startsWith("ru");
        String message = ru
                ? (ok ? "Моды обновлены. Запустите Minecraft через лаунчер." : "Синхронизация не применена. Проверьте журнал; при необходимости восстановите JAR из резервных копий.")
                : (ok ? "Mods updated. Start Minecraft through your launcher." : "Mod Sync was not applied. Check the journal; restore JAR backups if needed.");
        message += "\n\n" + (ru ? "Резервные копии и журнал: " : "Backups and journal: ") + transaction;
        System.out.println(message);
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            final String text = message;
            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null, text, "PeerCraft Mod Sync", ok ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE));
        }
    }
}
