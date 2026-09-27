package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.TranslatableComponent;
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
 * Minecraft 1.16.5 backport of {@code src/main/.../client/gui/HostMigrationScreen.java}. Deltas:
 * no {@code Minecraft.disconnect()}/{@code disconnect(Screen)} on 1.16.5 — the level/client
 * teardown is {@code level.disconnect()} + {@code Minecraft.clearLevel()}/{@code clearLevel(Screen)}
 * (see {@code leaveCurrentWorld}, mirroring {@code HandoffStatusScreen.toTitle()}); no
 * {@code ConnectScreen.startConnecting} static — {@code new ConnectScreen(parent, mc, serverData)}
 * parses {@code host:port} out of {@code ServerData.ip} itself (see {@code PeerCraftJoinScreen}'s
 * own connect path for the same pattern) so no {@code ServerAddress} is needed.
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
        super(new TranslatableComponent("peercraft.handoff.migrating.title"));
        this.successorAccountId = successorAccountId;
        this.amSuccessor = amSuccessor;
    }

    @Override
    protected void init() {
        this.buttons.clear();
        this.children.clear();
        if (failed) {
            this.addButton(Btn.builder(new TranslatableComponent("peercraft.modsync.restart.back"), b -> toTitle())
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
        net.peercraft.network.handoff.HandoffClientAgent agent = P2PBridge.INSTANCE.handoffClientAgent();
        this.statusKey = "peercraft.handoff.migrating.body";
        successorLaunch = net.peercraft.client.handoff.SuccessorLauncher.launch(offer, zip,
                new net.peercraft.client.handoff.SuccessorLauncher.Done() {
                    public boolean active() { return !failed && !exited; }
                    @Override
                    public void serverPublished() {
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

    /** 1.16.5 has no {@code Minecraft.disconnect()}/{@code disconnect(Screen)} — vanilla's own
     * equivalent (decompiled from {@code PauseScreen}) is {@code level.disconnect()} then
     * {@code Minecraft.clearLevel()} (remote) or {@code clearLevel(GenericDirtMessageScreen)}
     * (local/singleplayer — the successor's own freshly-opened world). */
    private void leaveCurrentWorld() {
        if (this.minecraft.level == null) {
            return;
        }
        boolean isLocalServer = this.minecraft.isLocalServer();
        this.minecraft.level.disconnect();
        if (isLocalServer) {
            this.minecraft.clearLevel(new GenericDirtMessageScreen(new TranslatableComponent("menu.savingLevel")));
        } else {
            this.minecraft.clearLevel();
        }
    }

    private void enterWorld() {
        if (failed || exited) return;
        int port = P2PBridge.INSTANCE.getProxyPort();
        ServerData serverData = new ServerData("PeerCraft", "127.0.0.1:" + port, false);
        this.minecraft.setScreen(new ConnectScreen(new TitleScreen(), this.minecraft, serverData));
    }

    private void fail(String key) {
        if (failed || exited) return;
        this.failed = true;
        if (successorLaunch != null) successorLaunch.cancel();
        if (lookup != null) lookup.stop();
        if (amSuccessor) leaveCurrentWorld();
        this.statusKey = key;
        this.init();
    }

    private void toTitle() {
        exited = true;
        if (successorLaunch != null) successorLaunch.cancel();
        if (lookup != null) lookup.stop();
        leaveCurrentWorld();
        this.minecraft.setScreen(new TitleScreen());
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
        return PeerCraftUi.wrap(this.font, new TranslatableComponent(statusKey).getString(),
                Math.min(this.width - 60, 360));
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 30;
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 22;
        int color = failed ? 0xFFFF5555 : 0xFFCCCCCC;
        for (String line : bodyLines()) {
            GuiComponent.drawCenteredString(poseStack, this.font, line, cx, y, color);
            y += 12;
        }
    }
}
