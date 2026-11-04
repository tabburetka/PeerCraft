package net.peercraft.network.diagnostics;

import java.lang.ref.WeakReference;
import java.util.*;

/** Live transfer providers are weakly held; completed/cancelled transfers report no entries. */
public final class TransferRegistry {
    public interface Source { List<Entry> diagnosticTransfers(); }
    public static final class Entry {
        public final String name;
        public final long remaining;
        public Entry(String name,long remaining) { this.name=name;this.remaining=Math.max(0,remaining); }
    }
    private static final List<WeakReference<Source>> sources=new ArrayList<WeakReference<Source>>();
    public static synchronized void register(Source source) { sources.add(new WeakReference<Source>(source)); }
    public static synchronized List<Entry> snapshot() {
        List<Entry> result=new ArrayList<Entry>();
        Iterator<WeakReference<Source>> it=sources.iterator();
        while(it.hasNext()) {
            Source s=it.next().get();if(s==null) it.remove();else result.addAll(s.diagnosticTransfers());
        }
        return result;
    }
    private TransferRegistry() { }
}
