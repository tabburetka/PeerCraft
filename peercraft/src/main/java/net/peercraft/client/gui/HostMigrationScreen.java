package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.peercraft.client.modsync.ClientModSyncAgent;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.handoff.HandoffProtocol;
import net.peercraft.network.handoff.LookupHostClient;
import net.peercraft.network.p2p.P2PBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Shown to every joiner on a handoff MIGRATE. The successor unzips + loads + re-hosts the
 * world ({@link net.peercraft.client.handoff.SuccessorLauncher}); everyone else polls
 * {@code TYPE_LOOKUP_HOST} for the successor's new room code and auto-reconnects.
 */
public class HostMigrationScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final long LOOKUP_TIMEOUT_MILLIS = 3 * 60_000L;

    private final UUID successorAccountId;
    private final boolean amSuccessor;

    private volatile String statusKey = "peercraft.handoff.migrating.body";
    private volatile boolean failed;
    private volatile boolean exited;
    private net.peercraft.client.handoff.SuccessorLauncher.Launch successorLaunch;
    private LookupHostClient lookup;
    private volatile boolean started;

    public HostMigrationScreen(UUID successorAccountId, boolean amSuccessor) {
        super(Component.translatable("peercraft.handoff.migrating.title"));
        this.successorAccountId = successorAccountId;
        this.amSuccessor = amSuccessor;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        if (failed) {
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.modsync.restart.back"), b -> toTitle())
                    .bounds(this.width / 2 - 100, this.height - 40, 200, 20).build());
        }
        if (!started) {
            started = true;
            if (amSuccessor) {
                startSuccessor();
            } else {
                startReconnect();
            }
        }
    }

    private void startSuccessor() {
        HandoffProtocol.Offer offer = HandoffClientController.INSTANCE.acceptedOffer();
        java.nio.file.Path zip = HandoffClientController.INSTANCE.takeReceivedWorldZip();
        if (offer == null || zip == null) {
            fail("peercraft.handoff.abort.transfer_failed");
            return;
        }
        // Captured now, before SuccessorLauncher.launch() opens the new world — that disconnects
        // us from the old host first, which clears P2PBridge's own handoffClientAgent reference
        // (see endClientSession). The agent object itself is still perfectly usable for the
        // MIGRATE_OK reply below; it just needs to be grabbed before the field goes null.
        net.peercraft.network.handoff.HandoffClientAgent agent = P2PBridge.INSTANCE.handoffClientAgent();
        this.statusKey = "peercraft.handoff.migrating.body";
        successorLaunch = net.peercraft.client.handoff.SuccessorLauncher.launch(offer, zip,
                new net.peercraft.client.handoff.SuccessorLauncher.Done() {
                    public boolean active() { return !failed && !exited; }
                    @Override
                    public void serverPublished() {
                        // OpenToLanMixin is now registering our room; nothing else to do here —
                        // the game screen is already showing our freshly-loaded world.
                        if (agent != null) {
                            agent.signalReady();
                        }
                        LOGGER.info("[Handoff] Стали новым хостом.");
                    }

                    @Override
                    public void failed(String reasonKey) {
                        Minecraft.getInstance().execute(() -> {
                            if (failed || exited) return;
                            fail(reasonKey);
                            PeerCraftUi.setScreen(Minecraft.getInstance(), HostMigrationScreen.this);
                        });
                    }
                });
    }

    private void startReconnect() {
        this.statusKey = "peercraft.handoff.migrating.waiting_for_host";
        this.lookup = new LookupHostClient();
        lookup.start(successorAccountId, PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(),
                LOOKUP_TIMEOUT_MILLIS, new LookupHostClient.Callback() {
                    @Override
                    public void onFound(String roomCode) {
                        Minecraft.getInstance().execute(() -> connectTo(roomCode));
                    }

                    @Override
                    public void onGaveUp() {
                        Minecraft.getInstance().execute(() -> fail("peercraft.handoff.abort.no_response"));
                    }
                });
    }

    private void connectTo(String roomCode) {
        if (failed || exited) return;
        this.statusKey = "peercraft.handoff.migrating.body";
        // Still nominally connected to the OLD host at this point (we've only been shown this
        // screen, not disconnected) — leave it first, both at the vanilla network layer and in
        // P2PBridge's own bookkeeping, or startClientViaRendezvous below gets rejected as
        // "already connecting" by its own re-entrancy guard.
        leaveCurrentWorld();
        P2PBridge.INSTANCE.prepareForHandoffReconnect();
        P2PBridge.INSTANCE.startClientViaRendezvous(roomCode,
                PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(),
                new P2PBridge.ConnectListener() {
                    @Override public void onStatus(String message) { }
                    @Override public void onConnected() {
                        Minecraft.getInstance().execute(HostMigrationScreen.this::enterWorld);
                    }
                    @Override public void onFailed(String reason) {
                        Minecraft.getInstance().execute(() -> fail(reason));
                    }
                },
                new ClientModSyncAgent(this, roomCode));
    }

    // Mirrors vanilla's own "disconnect to server list" sequence (PauseScreen's disconnect
    // button): tear down the level, then the client connection — the local-server (singleplayer/
    // hosting) and remote-server branches use different vanilla calls, and by the time this is
    // reached the caller could legitimately be either: a regular joiner still connected to
    // host1 (remote), or the successor who has already called SuccessorLauncher.openWorld()
    // and is now running its own IntegratedServer (local) when its publish step then fails.
    // ClientLevel.disconnect() gained a reason-Component param and Minecraft.disconnect()/
    // disconnect(Screen) were replaced by disconnectWithSavingScreen()/disconnectWithProgressScreen()
    // at 1.21.6 — see HandoffStatusScreen.toTitle() for the same boundary.
    private void leaveCurrentWorld() {
        if (this.minecraft.level == null) {
            return;
        }
        boolean isLocalServer = this.minecraft.isLocalServer();
        //? if <1.21.6 {
        this.minecraft.level.disconnect();
        if (isLocalServer) {
            this.minecraft.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        } else {
            this.minecraft.disconnect();
        }
        //?} else {
        /*this.minecraft.level.disconnect(Component.empty());
        if (isLocalServer) {
            this.minecraft.disconnectWithSavingScreen();
        } else {
            this.minecraft.disconnectWithProgressScreen();
        }
        *///?}
    }

    private void enterWorld() {
        if (failed || exited) return;
        int port = P2PBridge.INSTANCE.getProxyPort();
        ServerAddress address = new ServerAddress("127.0.0.1", port);
        ServerData serverData = new ServerData("PeerCraft", "127.0.0.1:" + port, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(new TitleScreen(), this.minecraft, address, serverData, false, null);
    }

    private void fail(String key) {
        if (failed || exited) return;
        this.failed = true;
        if (successorLaunch != null) successorLaunch.cancel();
        if (lookup != null) lookup.stop();
        if (amSuccessor) leaveCurrentWorld();
        this.statusKey = key;
        this.clearWidgets();
        this.init();
    }

    private void toTitle() {
        exited = true;
        if (successorLaunch != null) successorLaunch.cancel();
        if (lookup != null) lookup.stop();
        leaveCurrentWorld();
        //? if <26.2
        this.minecraft.setScreen(new TitleScreen());
        //? if >=26.2
        /*this.minecraft.gui.setScreen(new TitleScreen());*/
    }

    @Override
    public void onClose() {
        if (lookup != null) {
            lookup.stop();
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return failed;
    }

    private List<String> bodyLines() {
        return PeerCraftUi.wrap(this.font, Component.translatable(statusKey).getString(),
                Math.min(this.width - 60, 360));
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 30;
        graphics.drawCenteredString(this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 22;
        int color = failed ? 0xFFFF5555 : 0xFFCCCCCC;
        for (String line : bodyLines()) {
            graphics.drawCenteredString(this.font, line, cx, y, color);
            y += 12;
        }
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 30;
        graphics.centeredText(this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 22;
        int color = failed ? 0xFFFF5555 : 0xFFCCCCCC;
        for (String line : bodyLines()) {
            graphics.centeredText(this.font, line, cx, y, color);
            y += 12;
        }
    }*/
    //?}
}
