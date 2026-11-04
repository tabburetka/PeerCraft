package net.peercraft.network.diagnostics;

import java.util.*;

/** One HTTP download. The label is a mod id/file name; URLs and local paths are never shared. */
public final class DownloadSample implements TransferRegistry.Source, AutoCloseable {
    private final String name;
    private volatile long remaining;
    private volatile boolean active=true;
    public DownloadSample(String name,long total) {this.name=name;remaining=Math.max(0,total);TransferRegistry.register(this);}
    public void progress(long received,long total) {remaining=Math.max(0,total-received);}
    @Override public List<TransferRegistry.Entry> diagnosticTransfers() {
        return active?Collections.singletonList(new TransferRegistry.Entry(name,remaining)):Collections.emptyList();
    }
    @Override public void close() {active=false;}
}
