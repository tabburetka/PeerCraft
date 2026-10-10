package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

public class LegacyModSourceTest {
    @TempDir Path dir;

    @Test void syntheticContainerResolvesItsActualJar() throws Exception {
        Path jar = dir.resolve("core mod.jar");
        String name = LegacyModSource.class.getName();
        String entry = name.replace('.', '/') + ".class";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             InputStream in = LegacyModSource.class.getResourceAsStream("/" + entry)) {
            out.putNextEntry(new JarEntry(entry));
            byte[] bytes = new byte[4096]; int n;
            while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            out.closeEntry();
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            Class<?> container = loader.loadClass(name);
            assertEquals(jar, LegacyModSource.resolve(dir.resolve("minecraft.jar"), container));
            assertEquals(jar, LegacyModSource.resolve(null, container));
            Path reported = Files.write(dir.resolve("reported.jar"), new byte[]{1});
            assertEquals(reported, LegacyModSource.resolve(reported, container));
        }
    }

    @Test void unresolvedSourceRemainsMandatory() {
        Path missing = dir.resolve("minecraft.jar");
        assertEquals(missing, LegacyModSource.resolve(missing, LegacyModSourceTest.class));
        assertNull(LegacyModSource.resolve(null, LegacyModSourceTest.class));
    }
}
