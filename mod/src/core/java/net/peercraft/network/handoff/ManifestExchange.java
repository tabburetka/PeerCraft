package net.peercraft.network.handoff;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Two independent bounded block streams over the authenticated control channel. */
public final class ManifestExchange implements AutoCloseable {
    public interface Sender { void send(int type, byte[] payload); }
    private final Sender sender;
    private final CompletableFuture<HostExecutionManifest> incoming = new CompletableFuture<>();
    private final CompletableFuture<Void> outgoing = new CompletableFuture<>();
    private final ScheduledExecutorService timer;
    private ManifestBlocks blocks;
    private byte[] incomingHash, encoded, header;
    private int incomingSize;
    private BitSet acknowledged;
    private boolean headerAcknowledged, closed;
    public ManifestExchange(Sender sender) {
        this.sender = sender;
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "PeerCraft-Handoff-Manifest"); thread.setDaemon(true); return thread;
        });
    }
    public CompletableFuture<HostExecutionManifest> incoming() { return incoming; }
    public synchronized CompletableFuture<Void> send(HostExecutionManifest manifest) throws IOException {
        if (closed || encoded != null) throw new IOException("Manifest exchange already started/closed");
        encoded = manifest.encode(); acknowledged = new BitSet((encoded.length + ManifestBlocks.BLOCK_BYTES - 1) / ManifestBlocks.BLOCK_BYTES);
        byte[] hash = ManifestBlocks.hash(encoded);
        header = ByteBuffer.allocate(72).putInt(-1).putInt(encoded.length).put(hash).array();
        timer.scheduleWithFixedDelay(this::resend, 0, 200, TimeUnit.MILLISECONDS); return outgoing;
    }
    private void resend() {
        List<byte[]> send = new ArrayList<>();
        synchronized (this) {
            if (closed || outgoing.isDone()) return;
            if (!headerAcknowledged) send.add(header.clone());
            int count = (encoded.length + ManifestBlocks.BLOCK_BYTES - 1) / ManifestBlocks.BLOCK_BYTES;
            for (int index = acknowledged.nextClearBit(0); index < count && send.size() < 65; index = acknowledged.nextClearBit(index + 1)) {
                try {
                    byte[] block = ManifestBlocks.block(encoded, index);
                    send.add(ByteBuffer.allocate(4 + block.length).putInt(index).put(block).array());
                } catch (IOException invalid) { outgoing.completeExceptionally(invalid); return; }
            }
        }
        try { for (byte[] payload : send) sender.send(HandoffControlProtocol.MANIFEST, payload); }
        catch (RuntimeException failure) { outgoing.completeExceptionally(failure); }
    }
    /** Call only after channel identity, operation and phase validation. */
    public void receive(int type, byte[] payload) {
        try {
            int ack = -2; HostExecutionManifest complete = null;
            synchronized (this) {
                if (closed) return;
                if (payload.length < 4) throw new IOException("Truncated manifest control payload");
                ByteBuffer data = ByteBuffer.wrap(payload); int index = data.getInt();
                if (type == HandoffControlProtocol.MANIFEST_ACK) {
                    if (payload.length != 4 || encoded == null) return;
                    if (index == -1) headerAcknowledged = true;
                    else if (index >= 0 && index < (encoded.length + ManifestBlocks.BLOCK_BYTES - 1) / ManifestBlocks.BLOCK_BYTES) acknowledged.set(index);
                    if (headerAcknowledged && acknowledged.cardinality() == (encoded.length + ManifestBlocks.BLOCK_BYTES - 1) / ManifestBlocks.BLOCK_BYTES) outgoing.complete(null);
                    return;
                }
                if (type != HandoffControlProtocol.MANIFEST) return;
                if (index == -1) {
                    if (payload.length != 72) throw new IOException("Invalid manifest header");
                    int size = data.getInt(); byte[] hash = new byte[64]; data.get(hash);
                    if (blocks == null) { blocks = new ManifestBlocks(size, hash); incomingSize = size; incomingHash = hash; }
                    else if (incomingSize != size || !MessageDigest.isEqual(incomingHash, hash)) throw new IOException("Manifest changed within attempt");
                    ack = -1;
                } else if (blocks != null) {
                    byte[] block = new byte[data.remaining()]; data.get(block); blocks.accept(index, block); ack = index;
                    if (blocks.complete() && !incoming.isDone()) complete = blocks.verified();
                }
            }
            if (ack >= -1) sender.send(HandoffControlProtocol.MANIFEST_ACK, ByteBuffer.allocate(4).putInt(ack).array());
            if (complete != null) incoming.complete(complete);
        } catch (IOException | RuntimeException invalid) { incoming.completeExceptionally(invalid); outgoing.completeExceptionally(invalid); }
    }
    @Override public void close() {
        synchronized (this) {
            if (closed) return; closed = true;
            IOException ended = new IOException("Manifest exchange closed");
            incoming.completeExceptionally(ended); outgoing.completeExceptionally(ended);
        }
        timer.shutdownNow();
    }
}
