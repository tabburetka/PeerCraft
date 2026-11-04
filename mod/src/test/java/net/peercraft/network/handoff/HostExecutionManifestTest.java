package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HostExecutionManifestTest {
    private static HostExecutionManifest.Mod mod(String id, String parent, int hash) {
        byte[] digest = new byte[64]; digest[0] = (byte) hash; return new HostExecutionManifest.Mod(id, "1", parent, digest);
    }
    private static HostExecutionManifest manifest(String loader, HostExecutionManifest.Mod... mods) {
        return new HostExecutionManifest("1.21.1", loader, "1", Arrays.asList(mods), Collections.emptyMap());
    }
    @Test void additionalServerModsAndChangedHashesAreRejected() {
        HostExecutionManifest source = manifest("fabric", mod("server", "", 1));
        assertFalse(source.differences(manifest("fabric", mod("server", "", 2)), HostExecutionManifest.Profile.strict()).isEmpty());
        assertFalse(source.differences(manifest("fabric", mod("server", "", 1), mod("extra", "", 1)), HostExecutionManifest.Profile.strict()).isEmpty());
    }
    @Test void clientDifferencesRequireAnExplicitProfile() {
        HostExecutionManifest source = manifest("fabric", mod("client", "", 1));
        HostExecutionManifest target = manifest("fabric");
        assertFalse(source.differences(target, HostExecutionManifest.Profile.strict()).isEmpty());
        assertTrue(source.differences(target, new HostExecutionManifest.Profile(Collections.singleton("client"), Collections.emptySet())).isEmpty());
    }
    @Test void unknownNestedParentIsNotSilentlySkipped() {
        assertThrows(IllegalArgumentException.class, () -> manifest("fabric", mod("nested", "missing", 1)));
        HostExecutionManifest source = manifest("fabric", mod("parent", "", 1), mod("nested", "parent", 1));
        assertFalse(source.differences(manifest("fabric", mod("parent", "", 1), mod("nested", "", 1)), HostExecutionManifest.Profile.strict()).isEmpty());
    }
    @Test void loaderTransferIsStrictUnlessProfileAllowsDirection() {
        assertFalse(manifest("fabric").differences(manifest("neoforge"), HostExecutionManifest.Profile.strict()).isEmpty());
        assertTrue(manifest("fabric").differences(manifest("neoforge"), new HostExecutionManifest.Profile(Collections.emptySet(), Collections.singleton("fabric->neoforge"))).isEmpty());
    }
    @Test void reorderedBlocksAndDuplicatesPreserveTheFullManifest() throws Exception {
        List<HostExecutionManifest.Mod> mods = new ArrayList<>();
        for (int i = 0; i < 60; i++) mods.add(mod("mod" + i, "", i));
        HostExecutionManifest source = new HostExecutionManifest("1", "fabric", "1", mods, Collections.emptyMap());
        byte[] encoded = source.encode(); ManifestBlocks incoming = new ManifestBlocks(encoded.length, ManifestBlocks.hash(encoded));
        int count = (encoded.length + ManifestBlocks.BLOCK_BYTES - 1) / ManifestBlocks.BLOCK_BYTES;
        for (int i = count - 1; i >= 0; i--) { incoming.accept(i, ManifestBlocks.block(encoded, i)); incoming.accept(i, ManifestBlocks.block(encoded, i)); }
        assertTrue(incoming.complete()); assertTrue(source.differences(incoming.verified(), HostExecutionManifest.Profile.strict()).isEmpty());
        byte[] corrupted = ManifestBlocks.block(encoded, 0); corrupted[0] ^= 1;
        assertThrows(IOException.class, () -> incoming.accept(0, corrupted));
    }
    @Test void unsafeConfigPathsAndOversizedInputAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HostExecutionManifest("1", "fabric", "1", Collections.emptyList(), Collections.singletonMap("../outside", new byte[64])));
        assertThrows(IOException.class, () -> new ManifestBlocks(HostExecutionManifest.MAX_BYTES + 1, new byte[64]));
    }
}
