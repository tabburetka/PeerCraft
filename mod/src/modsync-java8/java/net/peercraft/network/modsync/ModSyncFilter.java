package net.peercraft.network.modsync;

// Java 8 twin of src/main/.../network/modsync/ModSyncFilter.java — Set.of ->
// unmodifiable HashSet, String.isBlank() -> trim().isEmpty(). Keep in sync with the original.

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * What mod-sync never ships. Since both sides enumerate the actual {@code *.jar} files in
 * {@code mods/} (see {@code ModJarScanner}), jar-in-jar'd sub-modules never appear here in the
 * first place — so this list only needs to cover the loader/game itself and PeerCraft.
 */
public final class ModSyncFilter {

    public static final Set<String> HARD_EXCLUDED_IDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "peercraft",
            "minecraft",
            "java",
            "neoforge",
            "forge",
            "fabricloader",
            "fabric-loader"
    )));

    private ModSyncFilter() {
    }

    /** True when this mod must be left out of mod-sync altogether. */
    public static boolean isExcluded(String id, String environment, boolean nested, String jarFileName) {
        if (id == null || id.trim().isEmpty()) {
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
