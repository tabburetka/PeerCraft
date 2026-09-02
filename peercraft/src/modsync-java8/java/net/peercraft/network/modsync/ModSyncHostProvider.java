package net.peercraft.network.modsync;

// Java 8 twin of src/main/.../network/modsync/ModSyncHostProvider.java — verbatim. Keep in sync.

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * What the host side of {@link ModSyncCoordinator} needs from the loader/client layer to
 * answer a joiner: the running loader, the filtered+hashed list of mods it's offering, and
 * a stream of any one of their jars. Built by the client-layer {@code HostModSyncProviderImpl}
 * from {@code OpenToLanMixin} — this interface itself stays free of {@code net.minecraft.*}.
 *
 * <p>A {@code null} provider (passed to {@code P2PBridge.startHostViaRendezvous}) means the
 * host doesn't participate: joiners get no MANIFEST and connect as before.
 */
public interface ModSyncHostProvider {

    ModSyncProtocol.Loader loader();

    /**
     * The mods this host offers, already run through {@link ModSyncFilter} and with real
     * {@code sizeBytes}/{@code sha512} filled in. May block briefly the first time if
     * hashing is still in flight; must not block indefinitely.
     */
    List<ModEntry> hostMods();

    /**
     * A fresh stream of the jar for {@code modId} (one of {@link #hostMods()}), or throws if
     * it can't be opened. The caller closes it.
     */
    InputStream openJar(String modId) throws IOException;

    /** Scratch directory the host coordinator may copy a jar into while serving it (chunk re-reads need random access). */
    Path servingTempDir();
}
