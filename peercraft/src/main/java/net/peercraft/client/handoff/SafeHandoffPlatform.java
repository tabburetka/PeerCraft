package net.peercraft.client.handoff;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.peercraft.network.handoff.*;
import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.client.gui.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;

/** Real native adapter for main; heavy work remains outside the render thread. */
public final class SafeHandoffPlatform extends SafeHandoffSupport {
    public static final SafeHandoffPlatform INSTANCE = new SafeHandoffPlatform();
    private final Minecraft mc = Minecraft.getInstance();
    private volatile HostMigrationScreen reconnectScreen;
    private volatile Start restoring;
    private volatile net.minecraft.client.gui.screens.Screen consentScreen;
    private SafeHandoffPlatform() { }
    public boolean modernWorldLock() { return true; }
    public Path savesDirectory() { return SuccessorLauncher.savesDirectory(); }
    public Path worldPath(Object server) { return WorldArchiver.worldDir((MinecraftServer) server); }
    public void render(Runnable action) { mc.execute(action); }
    public CompletableFuture<Boolean> consent(net.peercraft.network.handoff.HandoffProtocol.Offer offer) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!net.peercraft.config.PeerCraftConfig.handoff() || net.peercraft.config.PeerCraftConfig.declineHandoffSuccessor()) {
            result.complete(false); return result;
        }
        render(() -> { net.minecraft.client.gui.screens.Screen screen = new net.minecraft.client.gui.screens.ConfirmScreen(
                    result::complete, net.minecraft.network.chat.Component.translatable("peercraft.handoff.offer.title", offer.worldLabel()),
                    net.minecraft.network.chat.Component.translatable("peercraft.handoff.offer.body", offer.worldLabel())); consentScreen = screen; PeerCraftUi.setScreen(mc, screen); }); return result;
    }
    public void dismissConsent() { render(() -> { if (consentScreen != null && PeerCraftUi.isCurrentScreen(consentScreen)) PeerCraftUi.setScreen(mc, null); consentScreen = null; }); }
    public void waiting() { render(() -> { leaveCurrentWorld(); PeerCraftUi.setScreen(mc, new HandoffStatusScreen(new net.minecraft.client.gui.screens.TitleScreen(), "")); }); }
    public void sourceDone() { render(() -> {
        HandoffStatusScreen done = new HandoffStatusScreen(new net.minecraft.client.gui.screens.TitleScreen(), "");
        done.onDone("peercraft.handoff.status.done"); PeerCraftUi.setScreen(mc, done);
    }); }
    public void error(String key) { render(() -> {
        HandoffStatusScreen error = new HandoffStatusScreen(new net.minecraft.client.gui.screens.TitleScreen(), ""); error.onAborted(key); PeerCraftUi.setScreen(mc, error);
    }); }
    public void saveAndStop(Object server) throws IOException {
        WorldArchiver.saveAndStop((MinecraftServer) server, 180_000);
        CompletableFuture<Void> left = new CompletableFuture<>();
        render(() -> { try { leaveCurrentWorld(); PeerCraftUi.setScreen(mc, new HandoffStatusScreen(new net.minecraft.client.gui.screens.TitleScreen(), "")); left.complete(null); } catch (RuntimeException failure) { left.completeExceptionally(failure); } });
        try { left.get(30, TimeUnit.SECONDS); } catch (Exception failed) { throw new IOException("Source client teardown failed", failed); }
    }
    public static final class Start {
        public final CompletableFuture<HandoffSuccessorFlow.RegisteredRoom> result = new CompletableFuture<>();
        volatile SuccessorLauncher.Launch handle;
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        void handle(SuccessorLauncher.Launch next) { handle = next; if (cancelled.get()) next.cancel(); }
        public void cancel() { cancelled.set(true); if (handle != null) handle.cancel(); }

    }
    private SuccessorLauncher.Done callback(Start start) {
        return new SuccessorLauncher.Done() {
            public boolean active() { return !start.cancelled.get() && !start.result.isCompletedExceptionally() && !start.result.isCancelled(); }
            public void serverPublished() {
                // Registration callbacks may run on the UDP/render thread: RPC must leave it free.
                Thread proof = new Thread(() -> {
                    try {
                        if (start.cancelled.get()) throw new IOException("Launch cancelled");
                        String room = P2PBridge.INSTANCE.registeredRoomCode();
                        start.result.complete(new HandoffSuccessorFlow.RegisteredRoom(room, P2PBridge.INSTANCE.handoffAuthority().roomProof(room)));
                    } catch (IOException | RuntimeException failure) { start.result.completeExceptionally(failure); }
                }, "PeerCraft-Handoff-Registered-Room-Proof"); proof.setDaemon(true); proof.start();
            }
            public void failed(String key) { start.result.completeExceptionally(new IOException(key)); }
        };
    }
    public Start start(WorldTargetPlan plan, net.peercraft.network.handoff.HandoffProtocol.Offer offer,
            HandoffOperation operation, UUID sid) {
        Start start = new Start(); start.handle(SuccessorLauncher.startPlaced(plan, offer, operation, sid, callback(start))); return start;
    }
    public CompletableFuture<Void> stopStarted(Start start) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        render(() -> {
            MinecraftServer server = mc.getSingleplayerServer();
            if (server == null || start == null || start.handle == null || !start.handle.ownsServer(server, worldPath(server))) { result.complete(null); return; }
            Thread worker = new Thread(() -> {
                try { WorldArchiver.saveAndStop(server, 180_000); render(this::leaveCurrentWorld); result.complete(null); }
                catch (IOException failure) { result.completeExceptionally(failure); }
            }, "PeerCraft-Handoff-Stop-Failed-Host"); worker.setDaemon(true); worker.start();
        }); return result;
    }
    public CompletableFuture<HandoffSuccessorFlow.RegisteredRoom> restore(Object original, Path world,
            net.peercraft.network.handoff.HandoffProtocol.Offer offer, UUID sid) {
        MinecraftServer server = (MinecraftServer) original;
        if (server.isRunning()) {
            CompletableFuture<HandoffSuccessorFlow.RegisteredRoom> result = new CompletableFuture<>();
            new Thread(() -> {
                try { String room = P2PBridge.INSTANCE.registeredRoomCode(); result.complete(new HandoffSuccessorFlow.RegisteredRoom(room,
                        P2PBridge.INSTANCE.handoffAuthority().roomProof(room))); }
                catch (IOException | RuntimeException failure) { result.completeExceptionally(failure); }
            }, "PeerCraft-Handoff-Restore-Room").start(); return result;
        }
        Start start = new Start(); restoring = start;
        Thread restore = new Thread(() -> {
            try { WorldArchiver.awaitClosed(server, 180_000); start.handle(SuccessorLauncher.restoreClosed(world, offer, sid, callback(start))); }
            catch (IOException failure) { start.result.completeExceptionally(failure); }
        }, "PeerCraft-Handoff-Restore-Closed-World"); restore.setDaemon(true); restore.start(); return start.result;
    }
    public CompletableFuture<Void> stopFailedRestore() {
        Start start = restoring;
        if (start == null) return CompletableFuture.completedFuture(null);
        start.cancel(); return stopStarted(start);
    }
    public CompletableFuture<Void> reconnect(String room, long timeout) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean entered = new java.util.concurrent.atomic.AtomicBoolean();
        render(() -> {
            leaveCurrentWorld();
            HostMigrationScreen screen = new HostMigrationScreen(room, result); reconnectScreen = screen;
            PeerCraftUi.setScreen(mc, screen); entered.set(true);
        });
        Thread wait = new Thread(() -> {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            while (!result.isDone() && System.nanoTime() < end) {
                if (entered.get() && mc.player != null && mc.level != null && mc.getSingleplayerServer() == null) { result.complete(null); break; }
                try { Thread.sleep(100); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); break; }
            }
            if (!result.isDone()) result.completeExceptionally(new IOException("Reconnect expired"));
        }, "PeerCraft-Handoff-Actual-Join"); wait.setDaemon(true); wait.start(); return result;
    }
    public void cancelReconnect() { render(() -> { HostMigrationScreen screen = reconnectScreen; if (screen != null) screen.cancelSafeReconnect(); }); }
    private void leaveCurrentWorld() {
        if (mc.level == null) {
            return;
        }
        boolean isLocalServer = mc.isLocalServer();
        //? if <1.21.6 {
        mc.level.disconnect();
        if (isLocalServer) {
            mc.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
        } else {
            mc.disconnect();
        }
        //?} else {
        /*mc.level.disconnect(net.minecraft.network.chat.Component.empty());
        if (isLocalServer) {
            mc.disconnectWithSavingScreen();
        } else {
            mc.disconnectWithProgressScreen();
        }
        *///?}
    }

}
