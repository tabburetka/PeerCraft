package net.peercraft.network.modsync;

import java.util.Set;

/**
 * What mod-sync never ships. Since both sides enumerate the actual {@code *.jar} files in
 * {@code mods/} (see {@code ModJarScanner}), jar-in-jar'd sub-modules never appear here in the
 * first place — so this list only needs to cover the loader/game itself and PeerCraft. Fabric
 * API, Kotlin For Forge, Sinytra Connector, client-only mods etc. are all legitimate whole
 * jars a fresh joiner needs, so they ARE synced.
 */
public final class ModSyncFilter {

    public static final Set<String> HARD_EXCLUDED_IDS = Set.of(
            "peercraft",
            "minecraft",
            "java",
            "neoforge",
            "forge",
            "fabricloader",
            "fabric-loader"
    );

    private ModSyncFilter() {
    }

    /** True when this mod must be left out of mod-sync altogether. */
    public static boolean isExcluded(String id, String environment, boolean nested, String jarFileName) {
        if (id == null || id.isBlank()) {
            return true;
        }
        if (HARD_EXCLUDED_IDS.contains(id)) {
            return true;
        }
        if (nested) {
            return true;
        }
        return jarFileName == null || !jarFileName.endsWith(".jar");
    }
}
