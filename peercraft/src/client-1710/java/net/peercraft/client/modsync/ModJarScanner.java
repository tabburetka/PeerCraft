package net.peercraft.client.modsync;

// Forge 1.7.10 backport of src/main/.../client/modsync/ModJarScanner.java. Deltas vs the
// src/client-1122 twin:
//   * 1.7.10 mods declare themselves in mcmod.info (a JSON array, or an object with a
//     "modList" array), not fabric.mod.json / mods.toml — read {modid, version} from there.
//     1.7.10 metadata has no client/server split, so env is always Env.BOTH.
//   * fabric.mod.json / mods.toml parsing is kept only as a harmless fallback for oddball jars.
//   * `record ScannedJar` -> static final class; InputStream.readAllBytes() -> readFully();
//     Gson 2.2.4 (bundled by MC 1.7.10) has only the instance JsonParser API.
// Keep in sync with the original where the shared behaviour is unchanged.

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.peercraft.network.modsync.ModEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lists the actual {@code *.jar} files sitting directly in the {@code mods/} folder and reads
 * each one's declared mod id / version straight from {@code mcmod.info} inside the jar —
 * WITHOUT going through the loader's runtime mod list (so it matches how the host enumerates
 * and survives coremod/JiJ quirks).
 */
public final class ModJarScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final Pattern TOML_STR = Pattern.compile("(?m)^\\s*%s\\s*=\\s*\"([^\"]*)\"");

    private ModJarScanner() {
    }

    public static final class ScannedJar {
        private final Path jarPath;
        private final String id;
        private final String version;
        private final ModEntry.Env env;
        private final String fileName;

        public ScannedJar(Path jarPath, String id, String version, ModEntry.Env env, String fileName) {
            this.jarPath = jarPath;
            this.id = id;
            this.version = version;
            this.env = env;
            this.fileName = fileName;
        }

        public Path jarPath() {
            return jarPath;
        }

        public String id() {
            return id;
        }

        public String version() {
            return version;
        }

        public ModEntry.Env env() {
            return env;
        }

        public String fileName() {
            return fileName;
        }
    }

    /** Every {@code *.jar} directly in {@code modsDir}, one {@link ScannedJar} each. */
    public static List<ScannedJar> scan(Path modsDir) {
        List<ScannedJar> out = new ArrayList<ScannedJar>();
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path jar : ds) {
                if (Files.isRegularFile(jar)) {
                    out.add(read(jar));
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[ModSync] Не удалось прочитать папку mods {}: {}", modsDir, e.toString());
        }
        return out;
    }

    private static ScannedJar read(Path jar) {
        String fileName = jar.getFileName().toString();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ScannedJar fromMcmod = fromMcmodInfo(zf, jar, fileName);
            if (fromMcmod != null) {
                return fromMcmod;
            }
            ScannedJar fromFabric = fromFabricJson(zf, jar, fileName);
            if (fromFabric != null) {
                return fromFabric;
            }
            ScannedJar fromToml = fromModsToml(zf, jar, fileName);
            if (fromToml != null) {
                return fromToml;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[ModSync] {} — не удалось прочитать метаданные: {}", fileName, e.toString());
        }
        return new ScannedJar(jar, stem(fileName), "", ModEntry.Env.BOTH, fileName);
    }

    /**
     * {@code mcmod.info}: either a bare JSON array of mod objects, or {@code {"modList":[...]}}
     * (modListVersion 2). Take the first entry's {@code modid} + {@code version}. 1.7.10 has no
     * per-side metadata, so {@link ModEntry.Env#BOTH}.
     */
    private static ScannedJar fromMcmodInfo(ZipFile zf, Path jar, String fileName) throws IOException {
        ZipEntry e = zf.getEntry("mcmod.info");
        if (e == null) {
            return null;
        }
        try (Reader r = new InputStreamReader(zf.getInputStream(e), StandardCharsets.UTF_8)) {
            JsonElement root = new JsonParser().parse(r);
            JsonArray list = null;
            if (root.isJsonArray()) {
                list = root.getAsJsonArray();
            } else if (root.isJsonObject() && root.getAsJsonObject().has("modList")
                    && root.getAsJsonObject().get("modList").isJsonArray()) {
                list = root.getAsJsonObject().getAsJsonArray("modList");
            }
            if (list == null || list.size() == 0 || !list.get(0).isJsonObject()) {
                return null;
            }
            JsonObject o = list.get(0).getAsJsonObject();
            String id = str(o, "modid");
            if (id.isEmpty()) {
                return null;
            }
            return new ScannedJar(jar, id, sanitizeVersion(str(o, "version")), ModEntry.Env.BOTH, fileName);
        }
    }

    private static ScannedJar fromFabricJson(ZipFile zf, Path jar, String fileName) throws IOException {
        ZipEntry e = zf.getEntry("fabric.mod.json");
        if (e == null) {
            return null;
        }
        try (Reader r = new InputStreamReader(zf.getInputStream(e), StandardCharsets.UTF_8)) {
            JsonElement root = new JsonParser().parse(r);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject o = root.getAsJsonObject();
            String id = str(o, "id");
            if (id.isEmpty()) {
                return null;
            }
            return new ScannedJar(jar, id, sanitizeVersion(str(o, "version")), ModEntry.Env.BOTH, fileName);
        }
    }

    private static ScannedJar fromModsToml(ZipFile zf, Path jar, String fileName) throws IOException {
        for (String path : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
            ZipEntry e = zf.getEntry(path);
            if (e == null) {
                continue;
            }
            String toml;
            try (InputStream in = zf.getInputStream(e)) {
                toml = new String(readFully(in), StandardCharsets.UTF_8);
            }
            String id = tomlValue(toml, "modId");
            if (id.isEmpty()) {
                continue;
            }
            return new ScannedJar(jar, id, sanitizeVersion(tomlValue(toml, "version")), ModEntry.Env.BOTH, fileName);
        }
        return null;
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static String tomlValue(String toml, String key) {
        Matcher m = Pattern.compile(String.format(TOML_STR.pattern(), Pattern.quote(key))).matcher(toml);
        return m.find() ? m.group(1) : "";
    }

    /** A build-time placeholder like {@code ${version}} isn't a real version — treat as unknown. */
    private static String sanitizeVersion(String v) {
        return (v == null || v.contains("${")) ? "" : v;
    }

    private static String stem(String fileName) {
        String s = fileName.toLowerCase();
        if (s.endsWith(".jar")) {
            s = s.substring(0, s.length() - 4);
        }
        return s.isEmpty() ? "mod" : s;
    }
}
