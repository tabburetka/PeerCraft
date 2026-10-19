package net.peercraft.network.modsync;

// Java 8 twin of src/main/.../network/modsync/ModSyncPlan.java — records hand-lowered
// to Java 8 final classes with the same accessor names; List.copyOf -> unmodifiable ArrayList.
// Keep in sync with the original.

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * What the joiner is about to do once the player confirms: for every missing {@link ModEntry},
 * where its jar will come from and the running total.
 */
public final class ModSyncPlan {

    public static final class Replacement {
        private final String fileName, version, sha512;
        public Replacement(String fileName, String version, String sha512) {
            this.fileName = fileName; this.version = version; this.sha512 = sha512;
        }
        public String fileName() { return fileName; }
        public String version() { return version; }
        public String sha512() { return sha512; }
    }

    public boolean hasReplacements() {
        for (PlannedMod mod : mods()) if (mod.replacement() != null) return true;
        return false;
    }

    public boolean canAutoAccept(boolean enabled, boolean reofferDeclined) {
        if (!enabled || reofferDeclined || hasReplacements()) return false;
        for (PlannedMod mod : mods()) if (mod.catalogStatus() != CatalogStatus.PUBLISHED) return false;
        return true;
    }

    public enum Source { HTTP, P2P }

    public enum CatalogStatus { PUBLISHED, NOT_FOUND, UNAVAILABLE }

    public static final class PlannedMod {
        private final ModEntry entry;
        private final Source source;
        private final String httpUrl;
        private final CatalogStatus catalogStatus;
        private final Replacement replacement;

        public PlannedMod(ModEntry entry, Source source, String httpUrl) {
            this(entry, source, httpUrl, CatalogStatus.UNAVAILABLE);
        }

        public PlannedMod(ModEntry entry, Source source, String httpUrl, CatalogStatus catalogStatus) { this(entry, source, httpUrl, catalogStatus, null); }
        public PlannedMod(ModEntry entry, Source source, String httpUrl, CatalogStatus catalogStatus, Replacement replacement) {
            this.replacement = replacement;
            this.entry = entry;
            this.source = source;
            this.httpUrl = httpUrl;
            this.catalogStatus = catalogStatus;
        }

        public Replacement replacement() { return replacement; }
        public PlannedMod withReplacement(Replacement replacement) { return new PlannedMod(entry, source, httpUrl, catalogStatus, replacement); }
        public ModEntry entry() {
            return entry;
        }

        public Source source() {
            return source;
        }

        public String httpUrl() {
            return httpUrl;
        }

        public CatalogStatus catalogStatus() {
            return catalogStatus;
        }

        public PlannedMod withCatalogStatus(CatalogStatus status) {
            return new PlannedMod(entry, source, httpUrl, status, replacement);
        }

        public static PlannedMod http(ModEntry e, String url) {
            return new PlannedMod(e, Source.HTTP, url);
        }

        public static PlannedMod p2p(ModEntry e) {
            return new PlannedMod(e, Source.P2P, null);
        }
    }

    private final List<PlannedMod> mods;
    private final long totalBytes;

    public ModSyncPlan(List<PlannedMod> mods, long totalBytes) {
        this.mods = mods;
        this.totalBytes = totalBytes;
    }

    public List<PlannedMod> mods() {
        return mods;
    }

    public long totalBytes() {
        return totalBytes;
    }

    public static ModSyncPlan of(List<PlannedMod> mods) {
        long total = 0;
        for (PlannedMod m : mods) {
            total += Math.max(0, m.entry().sizeBytes());
        }
        return new ModSyncPlan(Collections.unmodifiableList(new ArrayList<PlannedMod>(mods)), total);
    }

    public int count() {
        return mods.size();
    }

    /**
     * A copy of this plan with every mod whose id is in {@code modIds} left out and
     * {@code totalBytes} recomputed.
     */
    public ModSyncPlan excluding(Set<String> modIds) {
        List<PlannedMod> kept = new ArrayList<PlannedMod>();
        for (PlannedMod m : mods) {
            if (!modIds.contains(m.entry().id())) {
                kept.add(m);
            }
        }
        return ModSyncPlan.of(kept);
    }

    public List<PlannedMod> http() {
        List<PlannedMod> out = new ArrayList<PlannedMod>();
        for (PlannedMod m : mods) {
            if (m.source() == Source.HTTP) out.add(m);
        }
        return out;
    }

    public List<PlannedMod> p2p() {
        List<PlannedMod> out = new ArrayList<PlannedMod>();
        for (PlannedMod m : mods) {
            if (m.source() == Source.P2P) out.add(m);
        }
        return out;
    }
}
