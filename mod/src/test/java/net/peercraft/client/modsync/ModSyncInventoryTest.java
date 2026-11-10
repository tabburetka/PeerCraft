package net.peercraft.client.modsync;

import net.peercraft.network.modsync.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ModSyncInventoryTest {
    @TempDir Path dir;
    ModJarScanner.ScannedJar jar(String file, String id, String version) throws Exception {
        Path path = Files.writeString(dir.resolve(file), file);
        return new ModJarScanner.ScannedJar(path, id, version, ModEntry.Env.BOTH, file);
    }
    ModEntry entry(String id, String version, String file) { return new ModEntry(id, version, 10, new byte[64], file, ModEntry.Env.BOTH, "", ""); }
    @Test void annotatesUpgradeAndDowngradeAndKeepsAdditionDistinct() throws Exception {
        ModSyncInventory inv = new ModSyncInventory(List.of(jar("old.jar", "mod", "2")));
        List<ModEntry> candidates = inv.candidates(List.of(entry("mod", "1", "new.jar"), entry("extra", "1", "extra.jar")), true);
        ModSyncPlan plan = inv.annotate(ModSyncPlan.of(candidates.stream().map(ModSyncPlan.PlannedMod::p2p).toList()));
        assertTrue(plan.hasReplacements()); assertEquals("2", plan.mods().get(0).replacement().version());
        assertNull(plan.mods().get(1).replacement());
        assertTrue(plan.excluding(Set.of("mod")).hasReplacements() == false);
    }
    @Test void skipsUnknownDuplicatesAndUnconfirmedCompatibility() throws Exception {
        ModSyncInventory inv = new ModSyncInventory(List.of(jar("a.jar", "a", ""), jar("b.jar", "b", "1"), jar("b2.jar", "b", "2")));
        assertTrue(inv.candidates(List.of(entry("a", "2", "a-new.jar"), entry("b", "3", "b-new.jar")), true).isEmpty());
        assertEquals(List.of("unknown", "ambiguous"), inv.issues.stream().map(i -> i.reason).toList());
        ModSyncInventory single = new ModSyncInventory(List.of(jar("c.jar", "c", "1")));
        assertTrue(single.candidates(List.of(entry("c", "2", "c-new.jar")), false).isEmpty());
        assertEquals("incompatible", single.issues.get(0).reason);
    }
    @Test void sameVersionIgnoresDifferentHashAndProtectsFilenameCollision() throws Exception {
        ModSyncInventory inv = new ModSyncInventory(List.of(jar("a.jar", "a", "1")));
        assertTrue(inv.candidates(List.of(entry("a", "1", "different.jar")), true).isEmpty());
        assertTrue(inv.candidates(List.of(entry("other", "2", "a.jar")), true).isEmpty());
        assertEquals("collision", inv.issues.get(0).reason);
    }
}
