package net.peercraft.network.diagnostics;

import com.google.gson.Gson;
import net.peercraft.network.p2p.PeerAddress;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Diagnostics run outside the render and UDP processing threads. No Minecraft dependency. */
public final class SessionMonitor implements AutoCloseable {
    public static final byte MAGIC = (byte) 0xE9, MESH_MAGIC = (byte) 0xEA;
    private static final Gson JSON = new Gson();
    public interface Wire { void send(PeerAddress peer, byte[] bytes); }
    public static final class Peer {
        public final PeerAddress address;
        public final String id;
        public final boolean secure, eligible;
        public final PeerAddress endpoint;
        public Peer(PeerAddress address, String id, boolean secure, boolean eligible) {
            this(address,id,secure,eligible,address);
        }
        public Peer(PeerAddress address, String id, boolean secure, boolean eligible, PeerAddress endpoint) {
            this.address=address; this.id=id; this.secure=secure; this.eligible=eligible;this.endpoint=endpoint;
        }
    }
    public static final class State {
        public String name = "";
        public double tick = -1, benchmark = -1;
        public int transfers;
        public long remaining;
        public String[] files = new String[0];
        public int limitKiB;
    }
    public static final class View {
        public String id, name;
        public boolean eligible, supported;
        public double tick=-1, benchmark=-1, rtt=-1, jitter=-1, loss=-1, upload, download;
        public int samples, queue, transfers;
        public long remaining, retries;
        public String[] files = new String[0];
    }
    public static final class Pair {
        public String a,b;
        public double rtt=-1, jitter=-1, loss=-1;
        public int samples;
    }
    private static final class Packet {
        int type;
        long nonce;
        State state;
        View[] views;
        Pair[] pairs;
        String token, a, b, ip;
        int port;
        boolean testing;
        int validFor;
        double rtt, jitter, loss;
        int samples;
    }
    private static final class Remote {
        final LinkMetrics link = new LinkMetrics();
        volatile State state;
        volatile long seen, lastProbe;
    }
    private static final class Mesh {
        String token, a,b;
        PeerAddress target;
        long until;
        final LinkMetrics link = new LinkMetrics();
    }
    private final Wire route, raw;
    private final Supplier<List<Peer>> peers;
    private final Supplier<Boolean> hosting;
    private final ConcurrentMap<PeerAddress, Remote> remotes = new ConcurrentHashMap<PeerAddress, Remote>();
    private final ConcurrentMap<String, Mesh> mesh = new ConcurrentHashMap<String, Mesh>();
    private final Map<String, Pair> pairs = new LinkedHashMap<String, Pair>();
    private final java.util.concurrent.atomic.AtomicLong pairRevision = new java.util.concurrent.atomic.AtomicLong();
    private long publishedRevision = -1;
    private volatile long comparisonId, comparisonExpires;
    private final Map<String, Pair> clientPairs = new ConcurrentHashMap<String, Pair>();
    private final Map<String, Map<PeerAddress, Packet>> directionalReports = new HashMap<String, Map<PeerAddress, Packet>>();
    private final Map<String, Set<PeerAddress>> reporters = new HashMap<String, Set<PeerAddress>>();
    private volatile List<View> published = Collections.emptyList();
    private volatile List<Pair> publishedPairs = Collections.emptyList();
    private volatile State local = new State();
    private volatile String stage = "idle";
    private volatile long stageStarted = System.nanoTime(), testUntil, nextTest;
    private volatile double benchmark=-1;
    private volatile boolean benchmarkRequested;
    private long lastCycle;
    private volatile long reportSeen;
    private volatile Set<String> roster = new HashSet<String>();
    private final ScheduledExecutorService executor;
    @Override public void close() { executor.shutdownNow(); }
    public void reset() {remotes.clear();mesh.clear();published=Collections.emptyList();publishedPairs=Collections.emptyList(); synchronized(pairs) {pairs.clear();reporters.clear();directionalReports.clear();} testUntil=0;nextTest=0;comparisonExpires=0;benchmark=-1;benchmarkRequested=false;local=new State();clientPairs.clear();roster=Collections.emptySet();pairRevision.incrementAndGet();stage("idle");}
    public SessionMonitor(Wire route, Wire raw, Supplier<List<Peer>> peers, Supplier<Boolean> hosting) {
        this.route=route; this.raw=raw; this.peers=peers; this.hosting=hosting;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PeerCraft-Session-Monitor"); t.setDaemon(true); return t;
        });
        executor.scheduleWithFixedDelay(() -> { try { cycle(); } catch (RuntimeException ignored) { /* A failed sample must not stop later measurements. */ } }, 1, 1, TimeUnit.SECONDS);
    }
    public void publish(String name, double tick, int transfers, long remaining, String[] files) {
        State s = new State(); s.name = name == null ? "" : name.substring(0, Math.min(name.length(), 64));
        s.tick = tick; s.benchmark = benchmark; s.transfers = transfers; s.remaining = remaining;
        s.files=files; s.limitKiB=TransferTraffic.limitKiB(); local=s;
    }
    public State local() { return local; }
    public void stage(String value) { stage=value; stageStarted=System.nanoTime(); }
    public String stage() { return stage; }
    public long stageSeconds() { return (System.nanoTime()-stageStarted)/1_000_000_000L; }
    public List<View> views() { return published; }
    public List<Pair> pairs() { return publishedPairs; }
    public boolean testing() { return System.nanoTime() < testUntil; }
    public boolean comparisonCurrent() { return System.nanoTime() < comparisonExpires; }
    public void traffic(PeerAddress address, boolean out, int bytes) { Remote r=remotes.get(address); if(r!=null) r.link.traffic(out,bytes); }
    public void queued(PeerAddress address, int delta) { Remote r=remotes.get(address); if(r!=null) r.link.queued(delta); }
    public void retry(PeerAddress address) { Remote r=remotes.get(address); if(r!=null) r.link.retry(); }
    private List<Peer> current() {
        List<Peer> all=peers.get(); return all.size()>32 ? new ArrayList<Peer>(all.subList(0,32)) : all;
    }
    private Peer find(PeerAddress address) { for(Peer p:current()) if(p.address.equals(address)) return p; return null; }
    private void send(PeerAddress peer, Packet p) {
        byte[] body=JSON.toJson(p).getBytes(StandardCharsets.UTF_8);
        if(body.length>24000) return;
        byte[] bytes=new byte[body.length+2]; bytes[0]=MAGIC; bytes[1]=1; System.arraycopy(body,0,bytes,2,body.length); route.send(peer,bytes);
    }
    public void receive(PeerAddress from, byte[] bytes, int length) {
        if(length<3 || length>bytes.length || length>24002 || bytes[0]!=MAGIC || bytes[1]!=1 || find(from)==null) return;
        Packet p;
        try { p=JSON.fromJson(new String(bytes,2,length-2,StandardCharsets.UTF_8),Packet.class); }
        catch(RuntimeException bad) { return; }
        if(p==null) return;
        long now=System.nanoTime();
        Remote r=remotes.computeIfAbsent(from,k -> new Remote());
        if(p.type==1) {
            Packet reply=new Packet(); reply.type=2; reply.nonce=p.nonce; reply.state=local;
            // The reply contains only the last published local sample.
            send(from,reply);
        } else if(p.type==2) {
            r.link.reply(p.nonce,now);
            if(valid(p.state)) { r.state=p.state; r.seen=now; }
        } else if(p.type==3 && !hosting.get()) {
            reportSeen=now;comparisonExpires=now+Math.max(0,Math.min(60,p.validFor))*1_000_000_000L;
            if(p.views!=null && p.views.length<=33 && validViews(p.views)) published=Collections.unmodifiableList(Arrays.asList(p.views));
            if(comparisonId!=p.nonce) {comparisonId=p.nonce;clientPairs.clear();publishedPairs=Collections.emptyList();}
            if(p.testing) testUntil=now+3_000_000_000L;
        } else if(p.type==4 && !hosting.get() && p.token!=null && p.token.matches("[a-f0-9]{32}") && find(from).secure
                && p.a!=null && p.b!=null && p.ip!=null && p.ip.matches("[0-9a-fA-F:.]+") && p.port>0 && p.port<=65535 && mesh.size()<496) {
            try {
                Mesh m=new Mesh(); m.token=p.token; m.a=p.a; m.b=p.b;
                m.target=new PeerAddress(java.net.InetAddress.getByName(p.ip),p.port); m.until=now+12_000_000_000L;
                mesh.putIfAbsent(p.token,m); benchmarkRequested=true; testUntil=m.until;
            } catch(Exception bad) { return; }
        } else if(p.type==5 && hosting.get()) {
            synchronized(pairs) {
                Set<PeerAddress> allowed=reporters.get(p.token);
                Pair pair=pairs.get(p.token);
                if(pair!=null && allowed!=null && allowed.contains(from) && now < testUntil+5_000_000_000L
                        && p.samples>=0 && p.samples<=30 && finite(p.rtt,-1,3000) && finite(p.loss,-1,100) && finite(p.jitter,-1,3000)) {
                    // Keep the worse direction; both peers probe independently.
                    Map<PeerAddress,Packet> directions=directionalReports.computeIfAbsent(p.token,k->new HashMap<PeerAddress,Packet>());
                    directions.put(from,p);
                    if(directions.size()==2) {
                        pair.samples=30;pair.rtt=0;pair.jitter=0;pair.loss=0;
                        boolean complete=true;
                        for(Packet direction:directions.values()) {
                            pair.samples=Math.min(pair.samples,direction.samples);pair.rtt=Math.max(pair.rtt,direction.rtt);
                            pair.jitter=Math.max(pair.jitter,direction.jitter);pair.loss=Math.max(pair.loss,direction.loss);
                            if(direction.rtt<0)complete=false;
                        }
                        if(!complete)pair.rtt=-1;
                        pairRevision.incrementAndGet();
                    }
                }
            }
        } else if(p.type==6 && hosting.get()) requestComparison();
        else if(p.type==7) benchmarkRequested=true;
        else if(p.type==8 && !hosting.get() && p.nonce==comparisonId && p.pairs!=null && p.pairs.length<=32) {
            for(Pair pair:p.pairs) if(pair!=null && pair.a!=null && pair.b!=null && clientPairs.size()<496)
                clientPairs.put(pair.a.compareTo(pair.b)<0?pair.a+"/"+pair.b:pair.b+"/"+pair.a,pair);
            publishedPairs=Collections.unmodifiableList(new ArrayList<Pair>(clientPairs.values()));
        }
    }
    private static boolean validViews(View[] views) {
        for(View v:views) if(v==null || v.id==null || v.name==null || v.name.length()>64 || v.files==null || v.files.length>32) return false;
        return true;
    }
    private static boolean finite(double v,double min,double max) { return !Double.isNaN(v)&&!Double.isInfinite(v)&&v>=min&&v<=max; }
    private static boolean valid(State s) {
        return s!=null && s.name!=null && s.name.length()<=64 && s.files!=null && s.files.length<=32
                && finite(s.tick,-1,60000) && finite(s.benchmark,-1,60000) && s.transfers>=0 && s.transfers<=10000 && s.remaining>=0;
    }
    /** Fixed-size token-bound probes never create a route or authorize Minecraft traffic. */
    public void receiveMesh(PeerAddress from, byte[] bytes, int length) {
        if(length!=58 || length>bytes.length || bytes[0]!=MESH_MAGIC || bytes[1]!=1) return;
        String token=new String(bytes,2,32,StandardCharsets.US_ASCII);
        Mesh m=mesh.get(token);
        if(m==null || System.nanoTime()>m.until || !m.target.equals(from)) return;
        ByteBuffer b=ByteBuffer.wrap(bytes); long nonce=b.getLong(34); byte type=b.get(42);
        if(type==1) { byte[] reply=Arrays.copyOf(bytes,length); reply[42]=2; raw.send(from,reply); }
        else if(type==2) m.link.reply(nonce,System.nanoTime());
    }
    public void requestComparison() {
        if(!hosting.get()) { for(Peer peer:current()) { Packet p=new Packet();p.type=6;send(peer.address,p); } return; }
        long now=System.nanoTime(); if(now<nextTest) return;
        comparisonId=ThreadLocalRandom.current().nextLong();pairRevision.incrementAndGet();
        comparisonExpires=now+60_000_000_000L;nextTest=now+30_000_000_000L; testUntil=now+12_000_000_000L; benchmarkRequested=true;
        List<Peer> all=current();
        Set<String> ids=new HashSet<String>();for(Peer peer:all)ids.add(peer.id);roster=ids;
        synchronized(pairs) {
            pairs.clear(); reporters.clear(); directionalReports.clear();
            for(Peer a:all) { Packet p=new Packet();p.type=7;send(a.address,p); }
            for(int i=0;i<all.size();i++) for(int j=i+1;j<all.size();j++) {
                Peer a=all.get(i),b=all.get(j);
                // Endpoints and bearer tokens are exchanged only on authenticated encrypted routes.
                if(!a.secure || !b.secure || a.endpoint==null || b.endpoint==null) continue;
                String token=UUID.randomUUID().toString().replace("-", "");
                Pair pair=new Pair();pair.a=a.id;pair.b=b.id;pairs.put(token,pair);
                reporters.put(token,new HashSet<PeerAddress>(Arrays.asList(a.address,b.address)));
                assign(a,b,token); assign(b,a,token);
            }
        }
    }
    private void assign(Peer a,Peer b,String token) {
        Packet p=new Packet();p.type=4;p.token=token;p.a=a.id;p.b=b.id;p.ip=b.endpoint.ip();p.port=b.endpoint.port();send(a.address,p);
    }
    private void cycle() {
        long now=System.nanoTime();
        List<Peer> all=current(); Set<PeerAddress> alive=new HashSet<PeerAddress>();
        for(Peer p:all) {
            alive.add(p.address); Remote r=remotes.computeIfAbsent(p.address,k -> new Remote());
            if(r.state==null && r.lastProbe!=0 && now-r.lastProbe<8_000_000_000L) continue;
            r.lastProbe=now; Packet ping=new Packet();ping.type=1;ping.nonce=ThreadLocalRandom.current().nextLong();r.link.sent(ping.nonce,now);send(p.address,ping);
        }
        remotes.keySet().retainAll(alive);
        Set<String> currentRoster=new HashSet<String>();for(Peer p:all)currentRoster.add(p.id);
        if(!currentRoster.equals(roster)) { synchronized(pairs) {pairs.clear();reporters.clear();directionalReports.clear();pairRevision.incrementAndGet();} mesh.clear();publishedPairs=Collections.emptyList();testUntil=0;comparisonExpires=0;roster=currentRoster; }
        if(!hosting.get() && reportSeen!=0 && now-reportSeen>5_000_000_000L) published=Collections.emptyList();
        if(all.isEmpty()) { mesh.clear(); clientPairs.clear(); published=Collections.emptyList();publishedPairs=Collections.emptyList(); }
        for(Mesh m:mesh.values()) {
            if(now<m.until-3_000_000_000L) {
                long nonce=ThreadLocalRandom.current().nextLong();m.link.sent(nonce,now);
                ByteBuffer bytes=ByteBuffer.allocate(58);bytes.put(MESH_MAGIC).put((byte)1).put(m.token.getBytes(StandardCharsets.US_ASCII)).putLong(nonce).put((byte)1);
                raw.send(m.target,bytes.array());
            } else if(now>=m.until) {
                LinkMetrics.Snapshot s=m.link.snapshot(now);
                Packet p=new Packet();p.type=5;p.token=m.token;p.rtt=s.rtt;p.jitter=s.jitter;p.loss=s.loss;p.samples=s.samples;
                for(Peer host:all) send(host.address,p);
                if(now>=m.until+2_000_000_000L)mesh.remove(m.token,m);
            }
        }
        if(hosting.get()) {
            List<View> views=new ArrayList<View>();
            views.add(view("host",true,local,null));
            for(Peer p:all) {
                Remote r=remotes.get(p.address); State state=now-r.seen<5_000_000_000L ? r.state : null;
                views.add(view(p.id,p.eligible,state,r.link.snapshot(now)));
            }
            published=Collections.unmodifiableList(views);
            synchronized(pairs) { publishedPairs=Collections.unmodifiableList(new ArrayList<Pair>(pairs.values())); }
            Packet p=new Packet();p.type=3;p.views=views.toArray(new View[0]);p.nonce=comparisonId;p.testing=testing();p.validFor=(int)Math.max(0,(comparisonExpires-now)/1_000_000_000L);
            for(Peer peer:all) send(peer.address,p);
            long revision=pairRevision.get();
            if(revision!=publishedRevision) {
                publishedRevision=revision;
                for(int start=0;start<publishedPairs.size();start+=32) {
                    Packet matrix=new Packet();matrix.type=8;matrix.nonce=comparisonId;
                    matrix.pairs=publishedPairs.subList(start,Math.min(start+32,publishedPairs.size())).toArray(new Pair[0]);
                    for(Peer peer:all) send(peer.address,matrix);
                }
            }
        } else if(all.size()==1 && now-lastCycle>=1_000_000_000L && published.isEmpty()) {
            Remote r=remotes.get(all.get(0).address);
            published=Collections.singletonList(view("host",true,r.state,r.link.snapshot(now)));
        }
        lastCycle=now;
        if(benchmarkRequested && !all.isEmpty()) {
            benchmarkRequested=false;
            // A bounded common CPU task provides a relative estimate, never a claimed world replay.
            double[] times=new double[7];long value=0x13579BDFL;
            for(int pass=0;pass<10;pass++) {
                long start=System.nanoTime();
                for(int i=0;i<500000;i++) {value^=value<<13;value^=value>>>7;value^=value<<17;}
                benchmarkSink=value;
                if(pass>=3)times[pass-3]=(System.nanoTime()-start)/1_000_000.0;
            }
            Arrays.sort(times);benchmark=times[3];
        }
    }
    private static volatile long benchmarkSink;
    private static View view(String id,boolean eligible,State state,LinkMetrics.Snapshot s) {
        View v=new View();v.id=id;v.eligible=eligible;v.supported=state!=null;v.name=state==null || state.name.isEmpty()?id:state.name;
        if(state!=null) {v.tick=state.tick;v.benchmark=state.benchmark;v.transfers=state.transfers;v.remaining=state.remaining;v.files=Arrays.copyOf(state.files,Math.min(4,state.files.length));}
        if(s!=null) {v.rtt=s.rtt;v.jitter=s.jitter;v.loss=s.loss;v.samples=s.samples;v.upload=s.upload;v.download=s.download;v.queue=s.queuedBytes;v.retries=s.retries;}
        return v;
    }
}
