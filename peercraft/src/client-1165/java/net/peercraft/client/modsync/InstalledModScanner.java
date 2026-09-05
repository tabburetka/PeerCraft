package net.peercraft.client.modsync;

// Minecraft 1.16.5 Fabric backport of src/main/.../client/modsync/InstalledModScanner.java —
// List.of() -> Collections.emptyList(); otherwise verbatim. Keep in sync with the original.

import net.peercraft.network.modsync.ModSyncProtocol;
import net.peercraft.platform.Services;
import net.peercraft.platform.services.PlatformMod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The joiner's own mod inventory, for the {@code T_HELLO} it sends the host: the primary mod
 * id of every {@code *.jar} directly in its {@code mods/} folder. Jar-file based (not the
 * loader's mod list) so it matches how the host enumerates.
 */
public final class InstalledModScanner {

    private InstalledModScanner() {
    }

    /** All loaded mods per the platform — used only to read PeerCraft's own version. */
    public static List<PlatformMod> allInstalled() {
        try {
            return Services.PLATFORM.getInstalledMods();
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    /** {id, version} for every jar the joiner already has in mods/ — what it advertises in HELLO. */
    public static List<ModSyncProtocol.ModRef> localModRefs() {
        List<ModSyncProtocol.ModRef> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            for (ModJarScanner.ScannedJar sj : ModJarScanner.scan(Services.PLATFORM.getModsDir())) {
                if (seen.add(sj.id())) {
                    out.add(new ModSyncProtocol.ModRef(sj.id(), sj.version()));
                }
            }
        } catch (RuntimeException e) {
            // fall back to whatever the loader reports
            for (PlatformMod pm : allInstalled()) {
                if (seen.add(pm.id())) {
                    out.add(new ModSyncProtocol.ModRef(pm.id(), pm.version()));
                }
            }
        }
        return out;
    }
}
