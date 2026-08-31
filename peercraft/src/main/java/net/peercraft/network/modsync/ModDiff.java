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
