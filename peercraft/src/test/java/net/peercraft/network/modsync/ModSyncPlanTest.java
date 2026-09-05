package net.peercraft.network.modsync;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModSyncPlanTest {

    private static ModSyncPlan.PlannedMod mod(String id, long size) {
        ModEntry e = new ModEntry(id, "1.0", size, new byte[64], id + ".jar", ModEntry.Env.BOTH, "", "");
        return ModSyncPlan.PlannedMod.p2p(e);
    }

    @Test
    void excludingDropsByIdAndRecomputesTotal() {
        ModSyncPlan plan = ModSyncPlan.of(List.of(mod("a", 100), mod("b", 200), mod("c", 300)));
        assertEquals(600, plan.totalBytes());

        ModSyncPlan filtered = plan.excluding(Set.of("b"));

        assertEquals(List.of("a", "c"), filtered.mods().stream().map(m -> m.entry().id()).toList());
        assertEquals(400, filtered.totalBytes());
    }

    @Test
    void excludingNothingKeepsEverything() {
        ModSyncPlan plan = ModSyncPlan.of(List.of(mod("a", 100), mod("b", 200)));
        assertEquals(2, plan.excluding(Set.of()).count());
        assertEquals(300, plan.excluding(Set.of()).totalBytes());
    }

    @Test
    void excludingUnknownIdIsANoop() {
        ModSyncPlan plan = ModSyncPlan.of(List.of(mod("a", 100)));
        assertEquals(1, plan.excluding(Set.of("zzz")).count());
    }

    @Test
    void excludingEveryIdLeavesAnEmptyPlan() {
        ModSyncPlan plan = ModSyncPlan.of(List.of(mod("a", 100), mod("b", 200)));
        ModSyncPlan empty = plan.excluding(Set.of("a", "b"));
        assertTrue(empty.mods().isEmpty());
        assertEquals(0, empty.totalBytes());
    }
}
