package net.peercraft.network.modsync;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What the joiner is about to do once the player confirms: for every missing {@link ModEntry},
 * where its jar will come from (an HTTP mirror URL the joiner resolved by hash, or a
 * peer-to-peer stream from the host) and the running total. Built on the joiner after the
 * MANIFEST arrives; shown on the confirm screen; consumed by the download step.
 */
public record ModSyncPlan(List<PlannedMod> mods, long totalBytes) {

    public enum Source { HTTP, P2P }

    public record PlannedMod(ModEntry entry, Source source, String httpUrl) {
        public static PlannedMod http(ModEntry e, String url) {
            return new PlannedMod(e, Source.HTTP, url);
        }

        public static PlannedMod p2p(ModEntry e) {
            return new PlannedMod(e, Source.P2P, null);
        }
    }

    public static ModSyncPlan of(List<PlannedMod> mods) {
        long total = 0;
        for (PlannedMod m : mods) {
            total += Math.max(0, m.entry().sizeBytes());
        }
        return new ModSyncPlan(List.copyOf(mods), total);
    }

    public int count() {
        return mods.size();
    }

    /**
     * A copy of this plan with every mod whose id is in {@code modIds} left out and
     * {@code totalBytes} recomputed. Used by the confirm screen to drop the client-side mods
     * the player unchecked before handing the plan to the download step.
     */
    public ModSyncPlan excluding(Set<String> modIds) {
        List<PlannedMod> kept = new ArrayList<>();
        for (PlannedMod m : mods) {
            if (!modIds.contains(m.entry().id())) {
                kept.add(m);
            }
        }
        return ModSyncPlan.of(kept);
    }

    public List<PlannedMod> http() {
        List<PlannedMod> out = new ArrayList<>();
        for (PlannedMod m : mods) {
            if (m.source() == Source.HTTP) out.add(m);
        }
        return out;
    }

    public List<PlannedMod> p2p() {
        List<PlannedMod> out = new ArrayList<>();
        for (PlannedMod m : mods) {
            if (m.source() == Source.P2P) out.add(m);
        }
        return out;
    }
}
