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
public final class ModSyncPlan {
    private final List<PlannedMod> mods;
    private final long totalBytes;
    private final List<Removal> removals;

    public ModSyncPlan(List<PlannedMod> mods, long totalBytes, List<Removal> removals) {
        this.mods = mods;
        this.totalBytes = totalBytes;
        this.removals = removals;
    }

    public List<PlannedMod> mods() {
        return mods;
    }

    public long totalBytes() {
        return totalBytes;
    }

    public List<Removal> removals() {
        return removals;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModSyncPlan)) return false;
        ModSyncPlan other = (ModSyncPlan) o;
        return java.util.Objects.equals(mods, other.mods)
                && totalBytes == other.totalBytes
                && java.util.Objects.equals(removals, other.removals);
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + java.util.Objects.hashCode(mods);
        h = 31 * h + Long.hashCode(totalBytes);
        h = 31 * h + java.util.Objects.hashCode(removals);
        return h;
    }

    @Override
    public String toString() {
        return "ModSyncPlan[mods=" + mods + ", totalBytes=" + totalBytes + ", removals=" + removals + "]";
    }

    public ModSyncPlan(List<PlannedMod> mods, long totalBytes) {
        this(mods, totalBytes, java.util.Collections.emptyList());
    }

    public static final class Replacement {
        private final String fileName, version, sha512;
        public Replacement(String fileName, String version, String sha512) {
            this.fileName = fileName; this.version = version; this.sha512 = sha512;
        }
        public String fileName() { return fileName; }
        public String version() { return version; }
        public String sha512() { return sha512; }
    }

    /** A local jar the host doesn't have, to be removed (backed up) after the game closes. */
    public static final class Removal {
        private final String id, fileName, version, sha512;
        private final ModEntry.Env env;
        public Removal(String id, String fileName, String version, String sha512, ModEntry.Env env) {
            this.id = id; this.fileName = fileName; this.version = version; this.sha512 = sha512; this.env = env;
        }
        public String id() { return id; }
        public String fileName() { return fileName; }
        public String version() { return version; }
        public String sha512() { return sha512; }
        public ModEntry.Env env() { return env; }
    }

    /** Replacements and removals both touch jars the running game has loaded. */
    public boolean needsRestart() {
        return hasReplacements() || !removals().isEmpty();
    }

    public boolean isEmpty() {
        return mods().isEmpty() && removals().isEmpty();
    }

    public boolean hasReplacements() {
        for (PlannedMod mod : mods()) if (mod.replacement() != null) return true;
        return false;
    }

    public boolean canAutoAccept(boolean enabled, boolean reofferDeclined) {
        if (!enabled || reofferDeclined || needsRestart()) return false;
        for (PlannedMod mod : mods()) if (mod.catalogStatus() != CatalogStatus.PUBLISHED) return false;
        return true;
    }

    public enum Source { HTTP, P2P }

    /** A catalog lookup is evidence of publication, never a malware scan. */
    public enum CatalogStatus { PUBLISHED, NOT_FOUND, UNAVAILABLE }

    public static final class PlannedMod {
        private final ModEntry entry;
        private final Source source;
        private final String httpUrl;
        private final CatalogStatus catalogStatus;
        private final Replacement replacement;

        public PlannedMod(ModEntry entry, Source source, String httpUrl, CatalogStatus catalogStatus, Replacement replacement) {
            this.entry = entry;
            this.source = source;
            this.httpUrl = httpUrl;
            this.catalogStatus = catalogStatus;
            this.replacement = replacement;
        }

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

        public Replacement replacement() {
            return replacement;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PlannedMod)) return false;
            PlannedMod other = (PlannedMod) o;
            return java.util.Objects.equals(entry, other.entry)
                    && java.util.Objects.equals(source, other.source)
                    && java.util.Objects.equals(httpUrl, other.httpUrl)
                    && java.util.Objects.equals(catalogStatus, other.catalogStatus)
                    && java.util.Objects.equals(replacement, other.replacement);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(entry);
            h = 31 * h + java.util.Objects.hashCode(source);
            h = 31 * h + java.util.Objects.hashCode(httpUrl);
            h = 31 * h + java.util.Objects.hashCode(catalogStatus);
            h = 31 * h + java.util.Objects.hashCode(replacement);
            return h;
        }

        @Override
        public String toString() {
            return "PlannedMod[entry=" + entry + ", source=" + source + ", httpUrl=" + httpUrl + ", catalogStatus=" + catalogStatus + ", replacement=" + replacement + "]";
        }

        public PlannedMod(ModEntry entry, Source source, String httpUrl, CatalogStatus status) { this(entry, source, httpUrl, status, null); }
        public PlannedMod withReplacement(Replacement replacement) { return new PlannedMod(entry, source, httpUrl, catalogStatus, replacement); }
        public static PlannedMod http(ModEntry e, String url) {
            return new PlannedMod(e, Source.HTTP, url, CatalogStatus.UNAVAILABLE);
        }

        public static PlannedMod p2p(ModEntry e) {
            return new PlannedMod(e, Source.P2P, null, CatalogStatus.UNAVAILABLE);
        }

        public PlannedMod withCatalogStatus(CatalogStatus status) {
            return new PlannedMod(entry, source, httpUrl, status, replacement);
        }
    
    }

    public static ModSyncPlan of(List<PlannedMod> mods) {
        long total = 0;
        for (PlannedMod m : mods) {
            total += Math.max(0, m.entry().sizeBytes());
        }
        return new ModSyncPlan(java.util.Collections.unmodifiableList(new java.util.ArrayList<>(mods)), total);
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
        // Removal ids are never host ids, so one deselection set covers both lists.
        List<Removal> keptRemovals = new ArrayList<>();
        for (Removal r : removals) {
            if (!modIds.contains(r.id())) keptRemovals.add(r);
        }
        return ModSyncPlan.of(kept).withRemovals(keptRemovals);
    }

    public ModSyncPlan withRemovals(List<Removal> removals) {
        return new ModSyncPlan(mods, totalBytes, java.util.Collections.unmodifiableList(new java.util.ArrayList<>(removals)));
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
