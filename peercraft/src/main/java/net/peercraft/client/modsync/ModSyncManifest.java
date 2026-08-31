package net.peercraft.client.modsync;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-disk record of what mod-sync has installed on this client
 * ({@code config/peercraft/modsync-installed.json}). Not load-bearing for the feature —
 * it's for debugging, cleanup and a possible future "undo". Persisted by
 * {@link ModSyncManifestStore} with the same Gson + atomic-write idiom as
 * {@code AccountStorage}.
 */
public final class ModSyncManifest {

    public int version = 1;
    public List<Install> installs = new ArrayList<>();

    public static final class Install {
        public String modId;
        public String modVersion;
        public String fileName;
        public String sha512;
        public String source;      // "http" | "p2p"
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
