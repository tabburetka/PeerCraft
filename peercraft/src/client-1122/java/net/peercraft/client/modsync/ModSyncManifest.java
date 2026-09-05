package net.peercraft.client.modsync;

// Forge 1.12.2 backport of src/main/.../client/modsync/ModSyncManifest.java — verbatim
// (diamond + Collection.removeIf are Java 8). Kept as a twin only because the whole
// client/modsync package is excluded from the shared tree in peercraft-forge-1122/build.gradle.

import java.util.ArrayList;
import java.util.List;

/**
 * The on-disk record of what mod-sync has installed on this client
 * ({@code config/peercraft/modsync-installed.json}). Not load-bearing for the feature.
 */
public final class ModSyncManifest {

    public int version = 1;
    public List<Install> installs = new ArrayList<>();

    public static final class Install {
        public String modId;
        public String modVersion;
        public String fileName;
        public String sha512;
        public String source;      // "http" | "p2p"  (1.12.2 always "p2p" — Modrinth path not ported)
        public String url;         // populated for http
        public long sizeBytes;
        public long installedAt;   // epoch millis
        public String hostRoomCode;
        public String supersededPath;
    }

    /** Adds (or replaces, by modId) an install record. */
    public void record(Install install) {
        installs.removeIf(i -> i.modId != null && i.modId.equals(install.modId));
        installs.add(install);
    }
}
