package net.peercraft.network.handoff;

import net.peercraft.platform.services.PlatformMod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class HostManifestCaptureTest {
    @TempDir Path dir;
    @Test void nestedModsUseTheirParentJarAndClientModsRemainMandatory() throws Exception {
        Path jar = Files.write(dir.resolve("parent.jar"), new byte[]{1, 2, 3});
        List<PlatformMod> mods = Arrays.asList(
                new PlatformMod("parent", "1", jar, "client", "", "", false),
                new PlatformMod("nested", "2", null, "client", "", "", true, "parent"));
        HostExecutionManifest source = HostManifestCapture.capture("1", "fabric", "1", mods, dir, Collections.emptySet());
        HostExecutionManifest target = HostManifestCapture.capture("1", "fabric", "1", Collections.emptyList(), dir, Collections.emptySet());
        assertEquals(2, source.differences(target, HostExecutionManifest.Profile.strict()).size());
        assertTrue(source.differences(HostExecutionManifest.decode(source.encode()), HostExecutionManifest.Profile.strict()).isEmpty());
    }
    @Test void unresolvedNestedModuleRejectsPreflight() {
        assertThrows(IOException.class, () -> HostManifestCapture.capture("1", "fabric", "1",
                Collections.singletonList(new PlatformMod("nested", "1", null, "both", "", "", true)), dir, Collections.emptySet()));
    }
    @Test void externalConfigsAreComparedWithoutOverwritingSuccessorFiles() throws Exception {
        Files.createDirectories(dir.resolve("source")); Files.createDirectories(dir.resolve("successor"));
        Files.write(dir.resolve("source/mod.toml"), new byte[]{1}); Files.write(dir.resolve("successor/mod.toml"), new byte[]{2});
        HostExecutionManifest source = HostManifestCapture.capture("1", "forge", "1", Collections.emptyList(), dir.resolve("source"), Collections.singleton("mod.toml"));
        HostExecutionManifest target = HostManifestCapture.capture("1", "forge", "1", Collections.emptyList(), dir.resolve("successor"), Collections.singleton("mod.toml"));
        assertFalse(source.differences(target, HostExecutionManifest.Profile.strict()).isEmpty());
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(dir.resolve("successor/mod.toml")));
    }
}
