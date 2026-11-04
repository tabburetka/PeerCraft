package net.peercraft.network.handoff;
import net.peercraft.client.handoff.WorldTargetPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class WorldTargetPlanTest {
    @TempDir Path root;
    Path world(String name) throws Exception {
        Path dir = Files.createDirectory(root.resolve(name)); Files.write(dir.resolve("level.dat"), new byte[]{1});
        Files.write(dir.resolve("peercraft-world.json"), "{\"worldId\":\"world\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); return dir;
    }
    @Test void backupsAndStagingCannotDisplaceTheRealReturnWorld() throws Exception {
        Path original = world("main"); world("main (до возврата yesterday)"); world(".peercraft-handoff-staging-3");
        Path marked = world("backup"); Files.write(marked.resolve(".peercraft-backup"), new byte[0]);
        assertEquals(java.util.Collections.singletonList(original), WorldTargetPlan.candidates(root, "world"));
        world("independent"); assertEquals(2, WorldTargetPlan.candidates(root, "world").size());
    }
    @Test void changedOrNewlyOccupiedDestinationIsRejectedBeforeReplacement() throws Exception {
        Path target = world("main"); WorldTargetPlan selection = WorldTargetPlan.selected(root, target, true, "world");
        Files.write(target.resolve("level.dat"), new byte[]{2}); assertThrows(IOException.class, selection::revalidate);
        WorldTargetPlan fresh = WorldTargetPlan.fresh(root, "world", "world"); Files.createDirectory(fresh.target);
        assertThrows(IOException.class, fresh::revalidate);
    }
    @Test void anotherWorldCannotBeSelectedAndOversizedArchiveCannotPassPreflight() throws Exception {
        Path target = world("main"); assertThrows(IOException.class, () -> WorldTargetPlan.selected(root, target, true, "other"));
        WorldTargetPlan fresh = WorldTargetPlan.fresh(root, "world", "world");
        assertThrows(IOException.class, () -> fresh.requireSpace(WorldTargetPlan.UNPACK_LIMIT + 1));
    }
}
