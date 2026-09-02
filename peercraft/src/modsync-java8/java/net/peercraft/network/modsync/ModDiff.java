package net.peercraft.network.modsync;

// Java 8 twin of src/main/.../network/modsync/ModDiff.java — arrow-switch
// expression lowered to a colon switch. Keep in sync with the original.

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The "which of the host's mods is the joiner missing" computation. Pure and side-effect
 * free so it can be unit-tested without a game.
 *
 * <p>A host mod is "missing" for this joiner ONLY when the joiner has no mod with that id at
 * all. Version differences are deliberately ignored.
 */
public final class ModDiff {

    private ModDiff() {
    }

    public static List<ModEntry> missing(Map<String, String> joinerVersionsById, List<ModEntry> hostMods) {
        List<ModEntry> out = new ArrayList<ModEntry>();
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
        List<ModEntry> out = new ArrayList<ModEntry>();
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
        switch (env) {
            case CLIENT:
                return "client";
            case SERVER:
                return "server";
            default:
                return "both";
        }
    }
}
