package net.peercraft.client.modsync;

import net.peercraft.platform.services.PlatformMod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ModJarScannerTest {
    @TempDir Path dir;
    Path jar(String name, Map<String, String> contents) throws Exception {
        Path file = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> e : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey())); zip.write(e.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return file;
    }
    @Test void resolvesAnnotationOnlyLegacyJarByUnambiguousLoaderOrigin() throws Exception {
        Path file = jar("legacy.jar", Map.of("dummy.txt", "no metadata"));
        PlatformMod mod = new PlatformMod("legacy", "1", file, "both", "", "", false);
        ModJarScanner.ScannedJar scanned = ModJarScanner.scan(dir, List.of(mod)).get(0);
        assertEquals("legacy", scanned.id()); assertEquals("1", scanned.version()); assertTrue(scanned.replaceable());
        PlatformMod other = new PlatformMod("other", "2", file, "both", "", "", false);
        assertFalse(ModJarScanner.scan(dir, List.of(mod, other)).get(0).replaceable());
    }

    @Test void resolvesForgePlaceholderUsingSamePhysicalJarThenManifest() throws Exception {
        Path file = jar("example.jar", Map.of("META-INF/mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"example\"\nversion=\"${file.jarVersion}\"\n",
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Version: 2.0\n\n"));
        PlatformMod same = new PlatformMod("example", "3.0", file, "both", "", "", false);
        assertEquals("3.0", ModJarScanner.scan(dir, List.of(same)).get(0).version());
        PlatformMod wrong = new PlatformMod("example", "9.0", dir.resolve("another.jar"), "both", "", "", false);
        assertEquals("2.0", ModJarScanner.scan(dir, List.of(wrong)).get(0).version());
    }
    @Test void readsLegacyMcmodInfoAndRefusesMultiModReplacement() throws Exception {
        jar("legacy.jar", Map.of("mcmod.info", "[{\"modid\":\"legacy\",\"version\":\"1.0\"},{\"modid\":\"second\",\"version\":\"2\"}]"));
        ModJarScanner.ScannedJar e = ModJarScanner.scan(dir).get(0);
        assertEquals("legacy", e.id()); assertEquals("1.0", e.version()); assertFalse(e.replaceable());
    }
    @Test void doesNotReadDependencyVersionAsModVersion() throws Exception {
        jar("example.jar", Map.of("META-INF/mods.toml", "[[mods]]\nmodId='example'\n[[dependencies.example]]\nmodId='forge'\nversion='99'\n"));
        assertEquals("", ModJarScanner.scan(dir).get(0).version());
    }
}
