package net.peercraft.client.modsync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * each one's declared mod id / version straight from {@code fabric.mod.json} /
 * {@code META-INF/neoforge.mods.toml} inside the jar — WITHOUT going through the loader's
 * runtime mod list.
 *
 * <p>This is what makes "clone the host's whole modpack" work: the loader's {@code ModList}
 * hides jar-in-jar'd sub-modules and, on a Sinytra Connector setup, doesn't point at the real
 * jar file for relocated mods (Sodium, Kotlin For Forge). The set of jar files in {@code mods/}
 * is exactly what a fresh joiner needs.
 */
public final class ModJarScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final Pattern TOML_STR = Pattern.compile("(?m)^\\s*%s\\s*=\\s*\"([^\"]*)\"");

    private ModJarScanner() {
    }

    /**
     * @param jarPath    the jar on disk
     * @param id         primary mod id (from metadata, or the filename stem if unreadable)
     * @param version    version string, or {@code ""} if unknown / a build placeholder
     * @param clientOnly {@code fabric.mod.json} {@code "environment": "client"}
     * @param fileName   {@code jarPath.getFileName()} — the name the joiner writes it back as
     */
    public record ScannedJar(Path jarPath, String id, String version, boolean clientOnly, String fileName) {
    }

    /** Every {@code *.jar} directly in {@code modsDir}, one {@link ScannedJar} each. */
    public static List<ScannedJar> scan(Path modsDir) {
        List<ScannedJar> out = new ArrayList<>();
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
        return new ScannedJar(jar, stem(fileName), "", false, fileName);
    }

    private static ScannedJar fromFabricJson(ZipFile zf, Path jar, String fileName) throws IOException {
        ZipEntry e = zf.getEntry("fabric.mod.json");
        if (e == null) {
            return null;
        }
        try (Reader r = new InputStreamReader(zf.getInputStream(e), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject o = root.getAsJsonObject();
            String id = str(o, "id");
            if (id.isEmpty()) {
                return null;
            }
            String env = str(o, "environment");
            return new ScannedJar(jar, id, sanitizeVersion(str(o, "version")), "client".equalsIgnoreCase(env), fileName);
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
                toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String id = tomlValue(toml, "modId");
            if (id.isEmpty()) {
                continue;
            }
            return new ScannedJar(jar, id, sanitizeVersion(tomlValue(toml, "version")), false, fileName);
        }
        return null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static String tomlValue(String toml, String key) {
        Matcher m = Pattern.compile(String.format(TOML_STR.pattern(), Pattern.quote(key))).matcher(toml);
        return m.find() ? m.group(1) : "";
    }

    /** A build-time placeholder like {@code ${file.jarVersion}} isn't a real version — treat as unknown. */
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
