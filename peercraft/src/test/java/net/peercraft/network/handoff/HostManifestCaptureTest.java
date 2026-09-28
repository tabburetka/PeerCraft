package net.peercraft.network.handoff;

import net.peercraft.platform.services.PlatformMod;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
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
    @Test void loaderOwnedNestedModulesDoNotRequireAStandaloneJar() throws Exception {
        Path jar = Files.write(dir.resolve("user-mod.jar"), new byte[]{1, 2, 3});
        List<PlatformMod> mods = Arrays.asList(
                new PlatformMod("fabricloader", "0.19.3", null, "both", "", "", false),
                new PlatformMod("mixinextras", "0.5.4", null, "both", "", "", true, "fabricloader"),
                new PlatformMod("user-mod", "1", jar, "both", "", "", false));
        HostExecutionManifest actual = HostManifestCapture.capture("1.21.1", "fabricloader", "0.19.3", mods, dir, Collections.emptySet());
        HostExecutionManifest expected = HostManifestCapture.capture("1.21.1", "fabricloader", "0.19.3",
                Collections.singletonList(mods.get(2)), dir, Collections.emptySet());
        assertTrue(actual.differences(expected, HostExecutionManifest.Profile.strict()).isEmpty());
    }
    @Test void clientOnlyModsAndTheirNestedModulesDoNotBlockDifferentClients() throws Exception {
        Path serverJar = Files.write(dir.resolve("server.jar"), new byte[]{1});
        Path clientJar = Files.write(dir.resolve("client.jar"), new byte[]{2});
        List<PlatformMod> hostMods = Arrays.asList(
                new PlatformMod("server", "1", serverJar, "both", "", "", false),
                new PlatformMod("appleskin", "1", clientJar, "both", "", "", false),
                new PlatformMod("cloth-config", "1", null, "client", "", "", true, "appleskin"));
        HostExecutionManifest host = HostManifestCapture.capture("1.21.1", "fabricloader", "1", hostMods,
                dir, Collections.emptySet(), Collections.singleton("appleskin"));
        HostExecutionManifest successor = HostManifestCapture.capture("1.21.1", "fabricloader", "1",
                Collections.singletonList(hostMods.get(0)), dir, Collections.emptySet());
        assertTrue(host.differences(successor, HostExecutionManifest.Profile.strict()).isEmpty());
        HostExecutionManifest successorWithOtherClientMods = HostManifestCapture.capture("1.21.1", "fabricloader", "1",
                Arrays.asList(hostMods.get(0), hostMods.get(1), hostMods.get(2)),
                dir, Collections.emptySet(), Collections.singleton("appleskin"));
        assertTrue(successor.differences(successorWithOtherClientMods, HostExecutionManifest.Profile.strict()).isEmpty());
        HostExecutionManifest extraServer = HostManifestCapture.capture("1.21.1", "fabricloader", "1",
                Arrays.asList(hostMods.get(0), new PlatformMod("extra-server", "1", clientJar, "both", "", "", false)),
                dir, Collections.emptySet());
        assertFalse(host.differences(extraServer, HostExecutionManifest.Profile.strict()).isEmpty());
    }
    @Test void sideClassificationUsesLoaderMetadataAndOptionalServerVerdict() throws Exception {
        Path jar = Files.write(dir.resolve("client.jar"), new byte[]{3});
        Path serverJar = Files.write(dir.resolve("server.jar"), new byte[]{4});
        List<PlatformMod> mods = Arrays.asList(
                new PlatformMod("client-declared", "1", jar, "client", "", "", false),
                new PlatformMod("appleskin", "1", jar, "both", "", "", false),
                new PlatformMod("client-remote", "1", jar, "both", "", "", false),
                new PlatformMod("server-required", "1", serverJar, "both", "", "", false));
        Set<String> client = HandoffClientOnlyMods.classify(mods, path -> path.equals(jar));
        assertTrue(client.containsAll(Arrays.asList("client-declared", "appleskin", "client-remote")));
        assertFalse(client.contains("server-required"));
        Set<String> unavailable = HandoffClientOnlyMods.classify(mods, path -> false);
        assertFalse(unavailable.contains("server-required"));
        assertTrue(unavailable.contains("appleskin"));
    }
    @Test void modrinthHashLookupClassifiesOptionalServerSide() throws Exception {
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> side = new AtomicReference<>("optional");
        api.createContext("/v2/version_file", request -> {
            byte[] body = "{\"project_id\":\"project123\"}".getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream out = request.getResponseBody()) { out.write(body); }
        });
        api.createContext("/v2/project/project123", request -> {
            byte[] body = ("{\"server_side\":\"" + side.get() + "\"}").getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream out = request.getResponseBody()) { out.write(body); }
        });
        api.start();
        try {
            String base = "http://127.0.0.1:" + api.getAddress().getPort() + "/v2/";
            assertTrue(HandoffClientOnlyMods.modrinthOptionalForServer(Files.write(dir.resolve("optional.jar"), new byte[]{6}), base));
            side.set("required");
            assertFalse(HandoffClientOnlyMods.modrinthOptionalForServer(Files.write(dir.resolve("required.jar"), new byte[]{7}), base));
        } finally { api.stop(0); }
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
