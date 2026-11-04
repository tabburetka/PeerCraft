package net.peercraft.network.modsync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The "which of the host's mods is the joiner missing" computation. Pure and side-effect
 * free so it can be unit-tested without a game.
 *
 * <p>Run on the HOST from the joiner's advertised {@code {id -> version}} map (see
 * {@link ModSyncProtocol} {@code T_HELLO}); the joiner re-checks the result defensively via
 * {@link #sanitize}.
 */
public final class ModDiff {

    private ModDiff() {
    }

    /**
     * A host mod is "missing" for this joiner ONLY when the joiner has no mod with that id at
     * all. Version differences are deliberately ignored: mod-sync is for filling in mods the
     * joiner doesn't have, not for version-matching an existing install. Comparing versions
     * would flag almost everything on a Fabric-host/NeoForge-joiner or Sinytra setup (the
     * version strings never match: {@code 0.6.13} vs {@code 0.6.13+mc1.21.1} vs
     * {@code mc1.21.1-0.6.13-neoforge}) and risk replacing a working jar with an incompatible
     * variant. {@link ModSyncFilter}-excluded entries are dropped regardless.
     */
    public static List<ModEntry> missing(Map<String, String> joinerVersionsById, List<ModEntry> hostMods) {
        List<ModEntry> out = new ArrayList<>();
        for (ModEntry hostMod : hostMods) {
            if (ModSyncFilter.isExcluded(hostMod.id(), envString(hostMod.env()), false, hostMod.fileName())) {
                continue;
            }
            if (!joinerVersionsById.containsKey(hostMod.id())) {
                out.add(hostMod);
            }
        }
        return out;
    }

    /** Candidates include unknown versions so the client can explain why a replacement was skipped. */
    public static List<ModEntry> changes(Map<String, String> local, List<ModEntry> host, boolean replacements) {
        if (!replacements) return missing(local, host);
        List<ModEntry> out = new ArrayList<ModEntry>();
        for (ModEntry e : sanitize(host)) {
            String version = local.get(e.id());
            if (!local.containsKey(e.id()) || !knownVersion(version) || !knownVersion(e.version()) || !version.equals(e.version())) out.add(e);
        }
        return out;
    }

    /**
     * Host side: the joiner's mods the host has no jar for at all, in the joiner's order.
     * {@code hostIds} must be the host's COMPLETE id set — including mods withheld from the
     * offer (client-only in "required" mode) — or the joiner would be told to delete a mod the
     * host actually has. Hard-excluded ids (loader, PeerCraft itself) are never listed.
     */
    public static List<String> extras(Iterable<String> joinerIds, java.util.Set<String> hostIds) {
        List<String> out = new ArrayList<>();
        for (String id : joinerIds) {
            if (id == null || id.trim().isEmpty() || ModSyncFilter.HARD_EXCLUDED_IDS.contains(id)) continue;
            if (!hostIds.contains(id) && !out.contains(id)) out.add(id);
        }
        return out;
    }

    public static boolean knownVersion(String version) {
        return version != null && !version.trim().isEmpty() && !version.contains("${");
    }

    /**
     * Joiner-side guard: drop anything the host sent that mod-sync must never touch, and
     * anything with an unsafe file name. Never trusts the host to have filtered correctly.
     */
    public static List<ModEntry> sanitize(List<ModEntry> received) {
        List<ModEntry> out = new ArrayList<>();
        for (ModEntry e : received) {
            if (ModSyncFilter.isExcluded(e.id(), envString(e.env()), false, e.fileName())) {
                continue;
            }
            if (!e.hasSafeFileName()) {
                continue;
            }
            if (e.sha512() == null || e.sha512().length != 64) {
                continue;
            }
            if (e.sizeBytes() <= 0) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    private static String envString(ModEntry.Env env) {
        return switch (env) {
            case CLIENT -> "client";
            case SERVER -> "server";
            default -> "both";
        };
    }
}
