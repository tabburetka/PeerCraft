package net.peercraft.client.handoff;

import net.minecraft.server.MinecraftServer;
import net.peercraft.network.handoff.HandoffCoordinator;
import net.peercraft.network.handoff.WorldTransfer;
import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.network.p2p.PeerAddress;
import net.peercraft.platform.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Builds the {@link HandoffCoordinator.Transfer} step the host runs once the successor accepts:
 * archive the running world ({@link WorldArchiver}), ship it over {@link WorldTransfer}, then
 * report done / failed back to the coordinator (which then broadcasts MIGRATE).
 */
public final class HostHandoffTransfer {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    public interface Progress {
        void archiving();

        void sending(long sentBytes, long totalBytes);
    }

    private HostHandoffTransfer() {
    }

    public static HandoffCoordinator.Transfer build(MinecraftServer server, long offerId,
                                                    PeerAddress successor, Progress progress) {
        return (onDone, onFail) -> new Thread(() -> run(server, offerId, successor, progress, onDone, onFail),
                "PeerCraft-Handoff-Archive").start();
    }

    private static void run(MinecraftServer server, long offerId, PeerAddress successor,
                            Progress progress, Runnable onDone, Consumer<String> onFail) {
        Path tmpDir = Services.PLATFORM.getConfigDir().resolve("peercraft").resolve("handoff-tmp");
        WorldArchiver.Result archive;
        try {
            progress.archiving();
            archive = WorldArchiver.archive(server, tmpDir);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Handoff] Не удалось заархивировать мир: {}", e.toString());
            onFail.accept("peercraft.handoff.abort.transfer_failed");
            return;
        }

        final Path zip = archive.zip();
        WorldTransfer wt = WorldTransfer.host(offerId, zip, archive.size(), archive.sha512(),
                P2PBridge.INSTANCE::sendRawDatagram, successor.host(), successor.port(),
                new WorldTransfer.HostCallbacks() {
                    @Override
                    public void onProgress(long acked, long total) {
                        progress.sending(acked, total);
                    }

                    @Override
                    public void onComplete() {
                        cleanup(zip);
                        P2PBridge.INSTANCE.setHostWorldTransfer(null);
                        onDone.run();
                    }

                    @Override
                    public void onFailed(String reasonKey) {
                        cleanup(zip);
                        P2PBridge.INSTANCE.setHostWorldTransfer(null);
                        onFail.accept(reasonKey);
                    }
                });
        P2PBridge.INSTANCE.setHostWorldTransfer(wt);
        wt.startHost();
    }

    private static void cleanup(Path zip) {
        try {
            Files.deleteIfExists(zip);
        } catch (IOException ignored) {
        }
    }
}
