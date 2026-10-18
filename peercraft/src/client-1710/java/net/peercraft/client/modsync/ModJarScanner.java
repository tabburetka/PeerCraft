package net.peercraft.client.modsync;

import com.google.gson.*;
import net.peercraft.network.modsync.ModEntry;
import net.peercraft.network.modsync.ModDiff;
import net.peercraft.platform.services.PlatformMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.Manifest;
import java.util.regex.*;
import java.util.zip.*;

/** Physical top-level jars are the unit of installation. Shared Java 8 implementation across all clients. */
public final class ModJarScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private ModJarScanner() { }

    public static final class ScannedJar {
        private final Path jarPath;
        private final String id, version, fileName;
        private final ModEntry.Env env;
        private final boolean replaceable;
        public ScannedJar(Path path, String id, String version, ModEntry.Env env, String fileName) {
            this(path, id, version, env, fileName, true);
        }
        public ScannedJar(Path path, String id, String version, ModEntry.Env env, String fileName, boolean replaceable) {
            this.jarPath = path; this.id = id; this.version = version; this.env = env;
            this.fileName = fileName; this.replaceable = replaceable;
        }
        public Path jarPath() { return jarPath; }
        public String id() { return id; }
        public String version() { return version; }
        public ModEntry.Env env() { return env; }
        public String fileName() { return fileName; }
        public boolean replaceable() { return replaceable; }
    }

    public static List<ScannedJar> scan(Path modsDir) { return scan(modsDir, Collections.<PlatformMod>emptyList()); }

    public static List<ScannedJar> scan(Path modsDir, List<PlatformMod> loaded) {
        List<ScannedJar> out = new ArrayList<ScannedJar>();
        if (modsDir == null || !Files.isDirectory(modsDir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path jar : ds) if (Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) out.add(read(jar, loaded));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect mod inventory: " + modsDir, e);
        }
        Collections.sort(out, Comparator.comparing(ScannedJar::fileName));
        return out;
    }

    private static ScannedJar read(Path jar, List<PlatformMod> loaded) {
        String name = jar.getFileName().toString();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ScannedJar entry = fabric(zip, jar, name);
            if (entry == null) entry = toml(zip, jar, name);
            if (entry == null) entry = mcmod(zip, jar, name);
            if (entry != null) {
                String version = entry.version();
                if (!ModDiff.knownVersion(version)) {
                    Set<String> versions = new LinkedHashSet<String>();
                    for (PlatformMod mod : loaded) {
                        if (!mod.nested() && mod.jarPath() != null && mod.id().equals(entry.id())
                                && mod.jarPath().toAbsolutePath().normalize().equals(jar.toAbsolutePath().normalize())
                                && ModDiff.knownVersion(mod.version())) versions.add(mod.version());
                    }
                    if (versions.size() == 1) version = versions.iterator().next();
                    if (!ModDiff.knownVersion(version)) {
                        ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
                        if (manifest != null) try (InputStream in = zip.getInputStream(manifest)) {
                            version = clean(new Manifest(in).getMainAttributes().getValue("Implementation-Version"));
                        }
                    }
                }
                return new ScannedJar(jar, entry.id(), version, entry.env(), name, entry.replaceable());
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[ModSync] Cannot read {}: {}", name, e.toString());
        }
        // Older Forge mods can use annotations without mcmod.info. Resolve only an unambiguous physical origin.
        List<PlatformMod> matches = new ArrayList<PlatformMod>();
        for (PlatformMod mod : loaded) if (!mod.nested() && mod.jarPath() != null
                && mod.jarPath().toAbsolutePath().normalize().equals(jar.toAbsolutePath().normalize())) matches.add(mod);
        if (!matches.isEmpty()) {
            PlatformMod mod = matches.get(0);
            return new ScannedJar(jar, mod.id(), clean(mod.version()), ModEntry.Env.BOTH, name, matches.size() == 1);
        }
        // Filename fallback is useful for legacy additions, but never authorizes replacing a file.
        return new ScannedJar(jar, name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT), "", ModEntry.Env.BOTH, name, false);
    }

    private static ScannedJar fabric(ZipFile z, Path path, String name) throws IOException {
        ZipEntry e = z.getEntry("fabric.mod.json");
        if (e == null) return null;
        JsonElement root = json(z, e);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        String id = str(o, "id");
        if (id.isEmpty()) return null;
        String environment = str(o, "environment");
        ModEntry.Env env = "client".equals(environment) ? ModEntry.Env.CLIENT : "server".equals(environment) ? ModEntry.Env.SERVER : ModEntry.Env.BOTH;
        // Nested modules and aliases are not independent replacement targets.
        return new ScannedJar(path, id, clean(str(o, "version")), env, name);
    }

    private static ScannedJar toml(ZipFile z, Path path, String name) throws IOException {
        for (String metadata : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
            ZipEntry e = z.getEntry(metadata);
            if (e == null) continue;
            String text;
            try (InputStream in = z.getInputStream(e)) { text = new String(bytes(in), StandardCharsets.UTF_8); }
            // Only read the [[mods]] block, never a dependency's version or modId.
            Matcher blocks = Pattern.compile("(?ms)^\\s*\\[\\[mods\\]\\]\\s*$(.*?)(?=^\\s*\\[|\\z)").matcher(text);
            String first = null; int count = 0;
            while (blocks.find()) { if (first == null) first = blocks.group(1); count++; }
            if (first == null) continue;
            String id = value(first, "modId");
            if (!id.isEmpty()) return new ScannedJar(path, id, clean(value(first, "version")), ModEntry.Env.BOTH, name, count == 1);
        }
        return null;
    }

    private static ScannedJar mcmod(ZipFile z, Path path, String name) throws IOException {
        ZipEntry e = z.getEntry("mcmod.info");
        if (e == null) return null;
        JsonElement root = json(z, e);
        if (root.isJsonObject() && root.getAsJsonObject().has("modList")) root = root.getAsJsonObject().get("modList");
        if (!root.isJsonArray() || root.getAsJsonArray().size() == 0) return null;
        JsonArray mods = root.getAsJsonArray();
        if (!mods.get(0).isJsonObject()) return null;
        JsonObject o = mods.get(0).getAsJsonObject();
        String id = str(o, "modid");
        return id.isEmpty() ? null : new ScannedJar(path, id, clean(str(o, "version")), ModEntry.Env.BOTH, name, mods.size() == 1);
    }

    private static JsonElement json(ZipFile z, ZipEntry e) throws IOException {
        try (Reader r = new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8)) { return new JsonParser().parse(r); }
    }
    private static byte[] bytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        return out.toByteArray();
    }
    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key); return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
    private static String value(String block, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*[\"']([^\"']*)[\"']").matcher(block);
        return m.find() ? m.group(1) : "";
    }
    private static String clean(String s) { return ModDiff.knownVersion(s) ? s : ""; }
}
