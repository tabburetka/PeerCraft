package net.peercraft.client.handoff;

import net.peercraft.network.handoff.*;
import net.peercraft.network.p2p.*;
import net.peercraft.platform.Services;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static net.peercraft.network.handoff.HandoffControlProtocol.*;

/** The production handoff path. Native actions are supplied by the four actual client adapters. */
public final class SafeHandoffSession implements P2PBridge.HandoffControlReceiver {
    public static final SafeHandoffSession INSTANCE = new SafeHandoffSession();
    private final P2PBridge bridge = P2PBridge.INSTANCE;
    private volatile Context current;
    private final java.util.concurrent.atomic.AtomicBoolean admitting = new java.util.concurrent.atomic.AtomicBoolean();
    private final HandoffWorkers admission = new HandoffWorkers();
    private SafeHandoffSession() { }
    public void register() {
        bridge.setHandoffControlReceiver(this);
        bridge.enableSafeHandoff();
        SafeHandoffPlatform.INSTANCE.recoverJournals();
    }
    public boolean active() { return current != null; }
    private static Path journals() { return Services.PLATFORM.getConfigDir().resolve("peercraft/handoff-journals"); }
    private static Path journalPath(UUID sid, long offer) { return journals().resolve(sid + "-" + Long.toHexString(offer) + ".journal"); }
    private static final class Peer {
        final InetSocketAddress endpoint; final byte[] control, authority; final boolean successor;
        final CompletableFuture<Boolean> consent = new CompletableFuture<>();
        final CompletableFuture<Void> preflight = new CompletableFuture<>(), prepared = new CompletableFuture<>();
        byte[] bootstrap;
        Peer(InetSocketAddress endpoint, byte[] control, byte[] authority, boolean successor) {
            this.endpoint = endpoint; this.control = control; this.authority = authority; this.successor = successor;
        }
    }
    private final class Context {
        final HandoffProtocol.Offer offer;
        final HandoffJournal journal;
        final HandoffOperation operation;
        final boolean source, successor;
        final Map<InetSocketAddress, Peer> peers = new ConcurrentHashMap<>();
        final HandoffWorkers io = new HandoffWorkers(), authorityIo = new HandoffWorkers();
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PeerCraft-Safe-Handoff-Control"); t.setDaemon(true); return t;
        });
        final AtomicBoolean closed = new AtomicBoolean(), stopped = new AtomicBoolean();
        final CompletableFuture<Void> prepared = new CompletableFuture<>();
        final CompletableFuture<byte[]> received = new CompletableFuture<>(), verified = new CompletableFuture<>();
        final CompletableFuture<Void> installed = new CompletableFuture<>();
        final CompletableFuture<String> ready = new CompletableFuture<>();
        volatile long lastSourceHeartbeat = System.nanoTime();
        volatile WorldTransfer transfer;
        volatile WorldTargetPlan plan;
        volatile Path archive, staging;
        volatile ManifestExchange manifest;
        volatile SafeHandoffPlatform.Start launch;
        volatile HandoffSourceFlow sourceFlow;
        volatile HandoffSuccessorFlow successorFlow;
        volatile HandoffObserverFlow observerFlow;
        volatile boolean consented, preflighted, frozen, beginSent;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile Boolean consentDecision;
        Object sourceServer;
        Path sourcePath;
        LocalPlayerIdentity.ArchiveIdentity identity;
        HandoffCoordinator.Callbacks callbacks;
        Context(HandoffProtocol.Offer offer, HandoffJournal journal, boolean source, boolean successor) throws IOException {
            this.offer = offer; this.journal = journal; this.source = source; this.successor = successor;
            operation = new HandoffOperation(bridge.handoffAuthority()::call, journal);
        }
        void send(Peer peer, int type, byte[] payload) {
            byte[] packet = HandoffControlProtocol.encode(new HandoffControlProtocol.Message(type, journal.session,
                    offer.offerId(), initialEpoch, peer.control, payload));
            bridge.sendRawDatagram(peer.endpoint.getAddress(), peer.endpoint.getPort(), packet);
        }
        long initialEpoch;
        void retain() {
            bridge.retainHandoffTransport(offer.offerId(), new HashSet<>(peers.keySet()), endpoint -> {
                Peer p = peers.get(endpoint);
                return HandoffControlProtocol.encode(new HandoffControlProtocol.Message(HEARTBEAT, journal.session,
                        offer.offerId(), initialEpoch, p.control, new byte[0]));
            });
        }
        CompletableFuture<Void> stopWorkers() {
            CompletableFuture<Void> result = new CompletableFuture<>();
            try {
                if (stopped.compareAndSet(false, true)) {
                    timer.shutdownNow(); if (manifest != null) manifest.close();
                    if (transfer != null) transfer.stopAndAwait(30_000);
                    authorityIo.stopAndAwait(30_000); io.stopAndAwait(30_000);
                }
                result.complete(null);
            } catch (IOException failure) { stopped.set(false); result.completeExceptionally(failure); }
            return result;
        }
        CompletableFuture<Void> cleanup() {
            CompletableFuture<Void> result = new CompletableFuture<>();
            try {
                if (!stopped.get()) throw new IOException("Attempt workers are not stopped");
                if (staging != null) WorldInstall.delete(staging);
                if (archive != null) Files.deleteIfExists(archive);
                Files.deleteIfExists(journalPath(journal.session, offer.offerId())); result.complete(null);
            } catch (IOException failure) { result.completeExceptionally(failure); }
            return result;
        }
        void terminal(String error, boolean newHost) {
            if (!closed.compareAndSet(false, true)) return;
            bridge.releaseHandoffTransport(offer.offerId());
            bridge.setHandoffAdmissionClosed(false);
            if (source && !newHost && !error.isEmpty()) bridge.cancelRendezvous();
            synchronized (SafeHandoffSession.this) { if (current == this) current = null; }
            bridge.setHandoffControlReceiver(SafeHandoffSession.this);
            SafeHandoffPlatform.INSTANCE.dismissConsent();
            if (!error.isEmpty()) SafeHandoffPlatform.INSTANCE.error(error);
        }
    }
    /** Called by the actual player picker instead of starting the legacy coordinator. */
    public synchronized boolean begin(Object server, PeerAddress chosen, HandoffProtocol.Offer offer, HandoffCoordinator.Callbacks callbacks) {
        if (current != null || server == null || !bridge.isHostingViaRendezvous()) return false;
        try {
            Path source = SafeHandoffPlatform.INSTANCE.worldPath(server);
            SafeHandoffPlatform.Grant grant = SafeHandoffPlatform.INSTANCE.hostingGrant(source);
            UUID sid = grant == null ? UUID.randomUUID() : grant.session;
            byte[] hostKey = grant == null ? HandoffAuthorityClient.newKey() : grant.key;
            long epoch = grant == null ? 0 : grant.epoch;
            HandoffJournal journal = new HandoffJournal(journalPath(sid, offer.offerId()), sid, offer.offerId(), epoch, hostKey);
            journal.role = "SOURCE"; journal.source = source.toString(); journal.authorityHost = bridge.handoffAuthorityHost();
            journal.authorityPort = net.peercraft.config.PeerCraftConfig.rendezvousPort();
            Context c = new Context(offer, journal, true, false); c.initialEpoch = epoch;
            c.callbacks = callbacks; c.sourceServer = server; c.sourcePath = source;
            c.identity = LocalPlayerIdentity.captureForArchive(source);
            byte[] successorKey = HandoffAuthorityClient.newKey(), observerKey = HandoffAuthorityClient.newKey();
            for (P2PBridge.HandoffCandidate player : bridge.connectedJoiners()) {
                boolean successor = player.peer().equals(chosen);
                InetSocketAddress endpoint = new InetSocketAddress(player.peer().host(), player.peer().port());
                c.peers.put(endpoint, new Peer(endpoint, HandoffAuthorityClient.newKey(), successor ? successorKey : observerKey, successor));
            }
            Peer successor = c.peers.get(new InetSocketAddress(chosen.host(), chosen.port()));
            if (successor == null || c.peers.size() > 1024) throw new IOException("Chosen player is no longer connected");
            journal.save(); current = c;
            new Thread(() -> sourceStart(c, successor, successorKey, observerKey), "PeerCraft-Safe-Handoff-Source-Begin").start();
            return true;
        } catch (IOException | RuntimeException failure) { callbacks.onAborted("peercraft.handoff.abort.transfer_failed"); return false; }
    }
    private void sourceStart(Context c, Peer successor, byte[] successorKey, byte[] observerKey) {
        try {
            bridge.requireHandoffCapabilities(new HashSet<>(c.peers.keySet()));
            if (c.cancelled.get()) throw new IOException("Attempt cancelled before BEGIN");
            HandoffAuthorityClient authority = bridge.handoffAuthority();
            byte[] proof = authority.roomProof(bridge.registeredRoomCode());
            HandoffAuthorityProtocol.Message begin = HandoffAuthorityClient.request(HandoffAuthorityProtocol.BEGIN,
                    c.journal.session, c.offer.offerId(), c.initialEpoch, c.journal.key);
            begin.successorKey = successorKey; begin.observerKey = observerKey; begin.room = bridge.registeredRoomCode();
            System.arraycopy(proof, 0, begin.digest, 0, 32);
            HandoffAuthorityProtocol.Message result;
            c.beginSent = true;
            try { result = authority.call(begin); } catch (IOException lost) { result = c.operation.query(); }
            if (result.state != HandoffAuthorityProtocol.PENDING || !result.ownsCurrentEpoch || !result.isCurrentAttempt)
                throw new IOException("Attempt authority is not confirmed");
            for (Peer p : c.peers.values()) p.bootstrap = bootstrap(c.offer, p);
            c.retain();
            c.timer.scheduleWithFixedDelay(() -> {
                if (c.closed.get()) return;
                for (Peer p : c.peers.values()) {
                    if (!p.consent.isDone()) c.send(p, OFFER, p.bootstrap);
                    if (c.frozen && !p.prepared.isDone()) c.send(p, PREPARE, new byte[0]);
                }
            }, 0, 300, TimeUnit.MILLISECONDS);
            c.manifest = new ManifestExchange((type, payload) -> c.send(successor, type, payload));
            c.sourceFlow = new HandoffSourceFlow(new HandoffSourceFlow.Steps() {
                public CompletableFuture<Void> capabilities() { return CompletableFuture.completedFuture(null); }
                public CompletableFuture<Boolean> consent() { return successor.consent; }
                public CompletableFuture<Void> preflight() {
                    c.callbacks.onAccepted();
                    return c.io.submit(() -> {
                        for (Peer p : c.peers.values()) if (!p.consent.get(20, TimeUnit.SECONDS)) throw new IOException("Participant declined");
                        c.manifest.send(SafeHandoffPlatform.INSTANCE.manifest()).get(10, TimeUnit.MINUTES);
                        successor.preflight.get(10, TimeUnit.MINUTES);
                        requireSameParticipants(c); return null;
                    });
                }
                public CompletableFuture<Void> prepareParticipants() {
                    return c.io.submit(() -> {
                        HandoffAuthorityProtocol.Message quiesce = HandoffAuthorityClient.request(HandoffAuthorityProtocol.QUIESCE,
                                c.journal.session, c.offer.offerId(), c.initialEpoch, c.journal.key);
                        quiesce.room = begin.room; System.arraycopy(proof, 0, quiesce.digest, 0, 32);
                        HandoffAuthorityProtocol.Message answer = authority.call(quiesce);
                        if (answer.state != HandoffAuthorityProtocol.PENDING || !answer.isCurrentAttempt) throw new IOException("Room quiesce failed");
                        bridge.setHandoffAdmissionClosed(true); requireSameParticipants(c); bridge.prepareSourceStop(c.offer.offerId()); c.frozen = true;
                        for (Peer p : c.peers.values()) c.send(p, PREPARE, new byte[0]);
                        for (Peer p : c.peers.values()) p.prepared.get(30, TimeUnit.SECONDS); return null;
                    });
                }
                public CompletableFuture<Void> saveAndStop() { return c.io.submit(() -> { SafeHandoffPlatform.INSTANCE.saveAndStop(c.sourceServer); return null; }); }
                public CompletableFuture<HandoffSourceFlow.Snapshot> archiveClosedWorld() {
                    return c.io.submit(() -> {
                        c.identity.prepare(c.sourcePath);
                        WorldArchiver.Result archive = WorldArchiver.archiveClosed(c.sourcePath, journals(), c.journal.session + "-" + Long.toHexString(c.offer.offerId()));
                        c.archive = archive.zip(); c.journal.archive = c.archive.toString(); c.journal.digest = archive.sha512(); c.journal.save();
                        return new HandoffSourceFlow.Snapshot(archive.zip(), archive.sha512());
                    });
                }
                public CompletableFuture<Void> transfer(HandoffSourceFlow.Snapshot snapshot) {
                    CompletableFuture<Void> sent = new CompletableFuture<>();
                    try {
                        c.transfer = WorldTransfer.host(c.offer.offerId(), snapshot.archive, Files.size(snapshot.archive), snapshot.digest,
                                bridge::sendRawDatagram, successor.endpoint.getAddress(), successor.endpoint.getPort(), new WorldTransfer.HostCallbacks() {
                            public void onProgress(long bytes, long total) { c.callbacks.onStatus("peercraft.handoff.status.transferring"); }
                            public void onComplete() { sent.complete(null); }
                            public void onFailed(String key) { sent.completeExceptionally(new IOException(key)); }
                        }); c.transfer.startHost();
                    } catch (IOException e) { sent.completeExceptionally(e); }
                    return sent;
                }
                public CompletableFuture<byte[]> verifiedStaging() { return poll(c, HandoffAuthorityProtocol.STAGED).thenApply(m -> m.digest); }
                public void committed(long epoch) {
                    PeercraftWorldMeta.markHandedOff(c.sourcePath, c.offer.worldLabel());
                    c.send(successor, START, new byte[0]);
                }
                public CompletableFuture<Void> installedSuccessor() { return poll(c, HandoffAuthorityProtocol.INSTALLED).thenApply(m -> null); }
                public CompletableFuture<String> registeredSuccessorRoom() { return poll(c, HandoffAuthorityProtocol.ROOM_READY).thenApply(m -> m.room); }
                public CompletableFuture<Void> stopAttemptWorkers() { return c.stopWorkers(); }
                public CompletableFuture<Void> cleanupConfirmedAbort() { return c.cleanup(); }
                public CompletableFuture<HandoffSuccessorFlow.RegisteredRoom> restoreSource() {
                    return SafeHandoffPlatform.INSTANCE.restore(c.sourceServer, c.sourcePath, c.offer, c.journal.session);
                }
                public CompletableFuture<Void> stopFailedRestore() { return SafeHandoffPlatform.INSTANCE.stopFailedRestore(); }
                public void terminal(HandoffSourceFlow.Outcome outcome, String room) {
                    if (outcome == HandoffSourceFlow.Outcome.READY) { c.callbacks.onSuccessorReady(); SafeHandoffPlatform.INSTANCE.sourceDone(); c.terminal("", false); bridge.cancelRendezvous(); }
                    else {
                        c.callbacks.onAborted("peercraft.handoff.abort.transfer_failed");
                        // A restored source is live again; do not remove its registered room.
                        c.terminal(outcome == HandoffSourceFlow.Outcome.ABORTED || outcome == HandoffSourceFlow.Outcome.DECLINED ? "" : "peercraft.handoff.abort.recovery_failed", false);
                    }
                }
            }, c.operation, HandoffSourceFlow.Limits.defaults(), phase -> c.callbacks.onStatus("peercraft.handoff.status.transferring"));
            c.sourceFlow.start(); if (c.cancelled.get()) c.sourceFlow.cancel();
        } catch (Exception failure) {
            try {
                if (!c.beginSent) { c.stopWorkers().get(); c.cleanup().get(); }
                else if (c.operation.abort()) { c.stopWorkers().get(); c.cleanup().get(); }
            } catch (Exception unresolved) { /* An unknown BEGIN/ABORT retains its journal. */ }
            c.stopWorkers(); c.callbacks.onAborted("peercraft.handoff.abort.transfer_failed"); c.terminal("", false);
        }
    }
    private void requireSameParticipants(Context c) throws IOException {
        Set<InetSocketAddress> connected = new HashSet<>();
        for (P2PBridge.HandoffCandidate player : bridge.connectedJoiners())
            connected.add(new InetSocketAddress(player.peer().host(), player.peer().port()));
        if (!connected.equals(c.peers.keySet())) throw new IOException("Participant roster changed during preflight");
    }
    private CompletableFuture<HandoffAuthorityProtocol.Message> poll(Context c, int wanted) {
        CompletableFuture<HandoffAuthorityProtocol.Message> future = new CompletableFuture<>();
        c.authorityIo.submit(() -> {
            long end = System.nanoTime() + TimeUnit.MINUTES.toNanos(wanted == HandoffAuthorityProtocol.STAGED ? 10 : 13);
            try {
                while (!c.closed.get() && !c.stopped.get() && System.nanoTime() < end) {
                    HandoffAuthorityProtocol.Message m = c.operation.query();
                    if (!m.isCurrentAttempt || m.state == HandoffAuthorityProtocol.UNKNOWN || m.state == HandoffAuthorityProtocol.DENIED || m.state == HandoffAuthorityProtocol.ABORTED)
                        throw new IOException("Authority outcome is unavailable");
                    if ((wanted == HandoffAuthorityProtocol.STAGED && m.state == wanted)
                            || (wanted == HandoffAuthorityProtocol.INSTALLED && m.installed)
                            || (wanted == HandoffAuthorityProtocol.ROOM_READY && m.state == wanted)) { future.complete(m); return null; }
                    Thread.sleep(1000);
                }
                throw new IOException("Authority phase expired");
            } catch (Exception e) { future.completeExceptionally(e); }
            return null;
        });
        return future;
    }
    private static byte[] bootstrap(HandoffProtocol.Offer offer, Peer peer) throws IOException {
        byte[] summary = HandoffProtocol.encodeOffer(offer.offerId(), offer.worldLabel(), offer.estArchiveBytes(), offer.maxPlayers(), offer.flags(), Collections.emptyList(), offer.worldId());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeBoolean(peer.successor); out.write(peer.authority); out.writeShort(summary.length); out.write(summary);
        if (bytes.size() > MAX_PAYLOAD) throw new IOException("Oversized offer summary"); return bytes.toByteArray();
    }
    public void onPacket(byte[] bytes, int length, InetSocketAddress sender) {
        try {
            HandoffControlProtocol.Message m = HandoffControlProtocol.decode(bytes, length);
            Context c = current;
            if (c == null) {
                if (m.type != OFFER) return;
                PeerAddress host = bridge.currentHostPeer();
                if (host == null || !host.host().equals(sender.getAddress()) || host.port() != sender.getPort()) return;
                if (admitting.compareAndSet(false, true)) admission.submit(() -> {
                    try { receiveOffer(m, sender); }
                    finally { admitting.set(false); }
                    return null;
                });
                return;
            }
            if (!c.journal.session.equals(m.session) || c.offer.offerId() != m.offer || c.initialEpoch != m.epoch || c.closed.get()) return;
            Peer p = c.peers.get(sender);
            if (p == null || !MessageDigest.isEqual(p.control, m.authority)) return;
            if (!c.source) {
                c.lastSourceHeartbeat = System.nanoTime();
                if (m.type == OFFER) { if (c.consentDecision != null) c.send(p, c.consented ? ACCEPT : DECLINE, new byte[0]); }
                else if (m.type == MANIFEST || m.type == MANIFEST_ACK) { if (c.successor && c.manifest != null && c.consented) c.manifest.receive(m.type, m.payload); }
                else if (m.type == PREPARE && (!c.successor || c.preflighted)) {
                    if (!c.frozen) { c.frozen = true; bridge.prepareSourceStop(c.offer.offerId()); SafeHandoffPlatform.INSTANCE.waiting(); c.prepared.complete(null); if (!c.successor) observe(c); }
                    c.send(p, PREPARED, new byte[0]);
                }
                // START is a hint. The successor flow resolves COMMIT independently.
            } else {
                if (m.type == ACCEPT) p.consent.complete(true);
                else if (m.type == DECLINE) p.consent.complete(false);
                else if (m.type == MANIFEST_ACK && p.successor && c.manifest != null) c.manifest.receive(m.type, m.payload);
                else if (m.type == PREFLIGHT && p.successor && p.consent.getNow(false)) p.preflight.complete(null);
                else if (m.type == PREPARED && c.frozen) p.prepared.complete(null);
            }
        } catch (IOException | RuntimeException invalid) { org.slf4j.LoggerFactory.getLogger("peercraft").debug("[Handoff] Ignored invalid control packet", invalid); }
    }
    public boolean onWorldPacket(byte[] bytes, int length, InetSocketAddress sender) {
        Context c = current; if (c == null || c.transfer == null) return false;
        Peer p = c.peers.get(sender); if (p == null || (c.source && !p.successor)) return false;
        c.transfer.onPacket(bytes, length, sender.getAddress(), sender.getPort()); return true;
    }
    private synchronized void receiveOffer(HandoffControlProtocol.Message m, InetSocketAddress sender) throws IOException {
        if (current != null) return;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(m.payload));
        boolean successor = in.readBoolean(); byte[] key = new byte[32]; in.readFully(key);
        int size = in.readUnsignedShort(); if (size != in.available()) throw new IOException("Invalid bootstrap summary");
        byte[] summary = new byte[size]; in.readFully(summary);
        HandoffProtocol.Offer offer = HandoffProtocol.decodeOffer(summary, summary.length);
        if (offer == null || offer.offerId() != m.offer || offer.protoVersion() != HandoffProtocol.PROTO_VERSION) throw new IOException("Invalid bootstrap offer");
        if (Files.exists(journalPath(m.session, m.offer), LinkOption.NOFOLLOW_LINKS)) {
            bridge.sendRawDatagram(sender.getAddress(), sender.getPort(), HandoffControlProtocol.encode(
                    new HandoffControlProtocol.Message(DECLINE, m.session, m.offer, m.epoch, m.authority, new byte[0])));
            return; // A late OFFER must not replace an unresolved or completed attempt.
        }
        HandoffAuthorityProtocol.Message permission = bridge.handoffAuthority().call(
                HandoffAuthorityClient.request(HandoffAuthorityProtocol.QUERY, m.session, m.offer, m.epoch, key));
        if (!permission.isCurrentAttempt || permission.epoch != m.epoch
                || (permission.state != HandoffAuthorityProtocol.PENDING && permission.state != HandoffAuthorityProtocol.STAGED))
            throw new IOException("Offer authority is unconfirmed");
        HandoffJournal journal = new HandoffJournal(journalPath(m.session, m.offer), m.session, m.offer, m.epoch, key);
        journal.role = successor ? "SUCCESSOR" : "OBSERVER"; journal.authorityHost = bridge.handoffAuthorityHost();
        journal.authorityPort = net.peercraft.config.PeerCraftConfig.rendezvousPort(); journal.save();
        Context c = new Context(offer, journal, false, successor); c.initialEpoch = m.epoch;
        Peer source = new Peer(sender, m.authority, key, false); c.peers.put(sender, source); current = c; c.retain();
        c.authorityIo.submit(() -> {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20);
            while (!c.frozen && !c.closed.get()) {
                HandoffAuthorityProtocol.Message state = c.operation.query();
                if (state.state == HandoffAuthorityProtocol.ABORTED || state.state == HandoffAuthorityProtocol.UNKNOWN
                        || state.state == HandoffAuthorityProtocol.DENIED || System.nanoTime() > deadline
                        || System.nanoTime() - c.lastSourceHeartbeat > TimeUnit.SECONDS.toNanos(30)) {
                    Thread finish = new Thread(() -> {
                        try {
                            boolean aborted = c.successor && c.operation.abort();
                            c.stopWorkers().get(); if (aborted) c.cleanup().get();
                        } catch (Exception unknown) { /* No cleanup without confirmed ABORT. */ }
                        c.stopWorkers(); c.terminal(state.state == HandoffAuthorityProtocol.ABORTED ? "" : "peercraft.handoff.abort.no_response", false);
                    }, "PeerCraft-Handoff-Early-Terminal");
                    finish.setDaemon(true); finish.start(); return null;
                }
                Thread.sleep(3000);
            }
            return null;
        }).whenComplete((ok, failure) -> {
            if (failure != null && !c.stopped.get() && !c.frozen && !c.closed.get()) {
                Thread finish = new Thread(() -> { c.stopWorkers(); c.terminal("peercraft.handoff.abort.no_response", false); }, "PeerCraft-Handoff-Early-Error");
                finish.setDaemon(true); finish.start();
            }
        });
        if (!successor) { c.consented = true; c.consentDecision = true; c.send(source, ACCEPT, new byte[0]); return; }
        c.manifest = new ManifestExchange((type, payload) -> c.send(source, type, payload));
        SafeHandoffPlatform.INSTANCE.consent(offer).whenComplete((accepted, failure) -> {
            if (c.closed.get()) return;
            c.consented = failure == null && Boolean.TRUE.equals(accepted); c.consentDecision = c.consented;
            c.send(source, c.consented ? ACCEPT : DECLINE, new byte[0]);
            if (!c.consented) {
                Thread decline = new Thread(() -> { c.stopWorkers(); c.terminal("", false); }, "PeerCraft-Handoff-Decline");
                decline.setDaemon(true); decline.start(); return;
            }
            c.io.submit(() -> {
                HostExecutionManifest hostManifest = c.manifest.incoming().get(10, TimeUnit.MINUTES);
                List<String> differences = hostManifest.differences(SafeHandoffPlatform.INSTANCE.manifest(), HostExecutionManifest.Profile.strict());
                if (!differences.isEmpty()) throw new IOException("Incompatible host execution: " + differences);
                c.plan = SuccessorLauncher.preflightTarget(c.offer, new net.peercraft.client.gui.HandoffTargetChoiceUi()).get(10, TimeUnit.MINUTES);
                c.journal.target = c.plan.target.toString(); c.journal.backup = c.plan.backup.toString(); c.journal.keepBackup = c.plan.keepBackup; c.journal.save();
                c.archive = journals().resolve(m.session + "-" + Long.toHexString(m.offer) + ".zip"); c.journal.archive = c.archive.toString(); c.journal.save();
                c.transfer = WorldTransfer.receiver(m.offer, c.archive, 4L * 1024 * 1024 * 1024, bridge::sendRawDatagram,
                        sender.getAddress(), sender.getPort(), new WorldTransfer.ReceiverCallbacks() {
                    public void onProgress(long bytes, long total) { }
                    public void onComplete(Path zip) {
                        c.archive = zip;
                        c.io.submit(() -> { c.received.complete(HostExecutionManifest.hash(zip)); return null; });
                    }
                    public void onFailed(String key) { c.received.completeExceptionally(new IOException(key)); }
                });
                c.preflighted = true; c.send(source, PREFLIGHT, new byte[0]);
                c.timer.scheduleWithFixedDelay(() -> { if (!c.frozen) c.send(source, PREFLIGHT, new byte[0]); }, 300, 300, TimeUnit.MILLISECONDS);
                candidate(c); return null;
            }).whenComplete((done, preflightFailure) -> {
                if (preflightFailure != null) new Thread(() -> {
                    try { if (c.operation.abort()) { c.stopWorkers().get(); c.cleanup().get(); } }
                    catch (Exception unknown) { /* Retain all files when ABORT is unconfirmed. */ }
                    c.stopWorkers(); c.terminal("peercraft.handoff.abort.transfer_failed", false);
                }, "PeerCraft-Handoff-Preflight-Failure").start();
            });
        });
    }
    private void candidate(Context c) {
        if (c.cancelled.get() || c.closed.get() || c.stopped.get()) return;
        c.successorFlow = new HandoffSuccessorFlow(new HandoffSuccessorFlow.Steps() {
            public CompletableFuture<Void> prepared() { return c.prepared; }
            public CompletableFuture<byte[]> receivedArchive() { return c.received; }
            public CompletableFuture<HandoffSuccessorFlow.Staged> verifyStaging() {
                return c.io.submit(() -> {
                    c.staging = c.plan.root.resolve(".peercraft-handoff-staging-" + c.journal.session + "-" + Long.toHexString(c.offer.offerId()));
                    c.journal.staging = c.staging.toString(); c.journal.save();
                    SuccessorLauncher.verifyStaging(c.offer, c.archive, c.plan, c.journal.session + "-" + Long.toHexString(c.offer.offerId()));
                    return new HandoffSuccessorFlow.Staged(HostExecutionManifest.hash(c.archive));
                });
            }
            public boolean sourceAlive() { return System.nanoTime() - c.lastSourceHeartbeat < TimeUnit.SECONDS.toNanos(30); }
            public CompletableFuture<Void> installCommittedWorld() { return c.io.submit(() -> { c.plan.install(c.staging, c.operation, SafeHandoffPlatform.INSTANCE.modernWorldLock()); return null; }); }
            public CompletableFuture<HandoffSuccessorFlow.RegisteredRoom> startAndRegister() {
                c.launch = SafeHandoffPlatform.INSTANCE.start(c.plan, c.offer, c.operation, c.journal.session); return c.launch.result;
            }
            public void readyRecorded(HandoffSuccessorFlow.RegisteredRoom room) throws IOException {
                SafeHandoffPlatform.INSTANCE.rememberGrant(c.plan.target, c.journal.session, c.journal.epoch, c.journal.key);
            }
            public void invalidateLaunch() { if (c.launch != null) c.launch.cancel(); }
            public CompletableFuture<Void> stopNewServer() { return SafeHandoffPlatform.INSTANCE.stopStarted(c.launch); }
            public CompletableFuture<Void> stopAttemptWorkers() { return c.stopWorkers(); }
            public CompletableFuture<Void> cleanupConfirmedAbort() { return c.cleanup(); }
            public void terminal(HandoffSuccessorFlow.Outcome outcome, String room) {
                c.terminal(outcome == HandoffSuccessorFlow.Outcome.READY ? "" : "peercraft.handoff.abort.transfer_failed", outcome == HandoffSuccessorFlow.Outcome.READY);
            }
        }, c.operation, HandoffSourceFlow.Limits.defaults()); c.successorFlow.start();
    }
    private void observe(Context c) {
        c.observerFlow = new HandoffObserverFlow(c.operation, new HandoffObserverFlow.Steps() {
            public void waiting() { SafeHandoffPlatform.INSTANCE.waiting(); }
            public CompletableFuture<Void> reconnect(String room, long timeout) { return SafeHandoffPlatform.INSTANCE.reconnect(room, timeout); }
            public void stopReconnect() { SafeHandoffPlatform.INSTANCE.cancelReconnect(); }
            public void terminal(HandoffObserverFlow.Outcome outcome) { c.stopWorkers(); c.terminal(outcome == HandoffObserverFlow.Outcome.RECONNECTED ? "" : "peercraft.handoff.abort.no_response", false); }
        }, HandoffSourceFlow.Limits.defaults()); c.observerFlow.start();
    }
    public void cancel() {
        Context c = current; if (c == null) return; c.cancelled.set(true);
        if (c.sourceFlow != null) c.sourceFlow.cancel();
        if (c.successorFlow != null) c.successorFlow.cancel();
        if (c.observerFlow != null) c.observerFlow.cancel();
        if (!c.source && c.successorFlow == null && c.observerFlow == null) {
            Thread cancel = new Thread(() -> {
                try {
                    boolean aborted = c.successor && c.operation.abort();
                    c.stopWorkers().get(); if (aborted) c.cleanup().get();
                } catch (Exception unknown) { /* Preserve an unresolved attempt. */ }
                c.terminal("peercraft.handoff.abort.transfer_failed", false);
            }, "PeerCraft-Handoff-Cancel-Preflight"); cancel.setDaemon(true); cancel.start();
        }
    }
}
