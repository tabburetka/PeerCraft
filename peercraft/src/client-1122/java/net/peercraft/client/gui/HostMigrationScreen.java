package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.WorldClient;
import net.peercraft.client.modsync.ClientModSyncAgent;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.handoff.HandoffProtocol;
import net.peercraft.network.handoff.LookupHostClient;
import net.peercraft.network.p2p.P2PBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Forge 1.12.2 backport of {@code src/main/.../client/gui/HostMigrationScreen.java} (cf. the
 * 1.16.5 twin). {@code leaveCurrentWorld} mirrors vanilla's own {@code GuiIngameMenu} disconnect
 * button exactly (decompiled: {@code world.sendQuittingDisconnectingPacket()} then
 * {@code loadWorld(null)}, no local/remote branch or "saving level" screen on 1.12.2 — see
 * {@code HandoffStatusScreen}); {@code enterWorld} uses {@code GuiConnecting(parent, mc, host,
 * port)} directly (no {@code ConnectScreen.startConnecting}/{@code ServerAddress}), same as
 * {@code PeerCraftJoinScreen.handleConnected}.
 */
public class HostMigrationScreen extends GuiScreen {

    private static final Logger LOGGER = LogManager.getLogger("peercraft");
    private static final long LOOKUP_TIMEOUT_MILLIS = 3 * 60_000L;

    private final UUID successorAccountId;
    private final boolean amSuccessor;

    private volatile String statusKey = "peercraft.handoff.migrating.body";
    private volatile boolean failed;
    private LookupHostClient lookup;
    private volatile boolean started;

    public HostMigrationScreen(UUID successorAccountId, boolean amSuccessor) {
        this.successorAccountId = successorAccountId;
        this.amSuccessor = amSuccessor;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        if (failed) {
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.modsync.restart.back"), this::toTitle)
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

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
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
        net.peercraft.client.handoff.SuccessorLauncher.launch(offer, zip,
                new net.peercraft.client.handoff.SuccessorLauncher.Done() {
                    @Override
                    public void serverPublished() {
                        if (agent != null) {
                            agent.signalReady();
                        }
                        LOGGER.info("[Handoff] Стали новым хостом.");
                    }

                    @Override
                    public void failed(String reasonKey) {
                        Minecraft.getMinecraft().addScheduledTask(() -> {
                            fail(reasonKey);
                            PeerCraftUi.setScreen(Minecraft.getMinecraft(), HostMigrationScreen.this);
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
                        Minecraft.getMinecraft().addScheduledTask(() -> connectTo(roomCode));
                    }

                    @Override
                    public void onGaveUp() {
                        Minecraft.getMinecraft().addScheduledTask(() -> fail("peercraft.handoff.abort.no_response"));
                    }
                });
    }

    private void connectTo(String roomCode) {
        this.statusKey = "peercraft.handoff.migrating.body";
        leaveCurrentWorld();
        P2PBridge.INSTANCE.prepareForHandoffReconnect();
        P2PBridge.INSTANCE.startClientViaRendezvous(roomCode,
                PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(),
                new P2PBridge.ConnectListener() {
                    @Override public void onStatus(String message) { }
                    @Override public void onConnected() {
                        Minecraft.getMinecraft().addScheduledTask(HostMigrationScreen.this::enterWorld);
                    }
                    @Override public void onFailed(String reason) {
                        Minecraft.getMinecraft().addScheduledTask(() -> fail(reason));
                    }
                },
                new ClientModSyncAgent(this, roomCode));
    }

    private void leaveCurrentWorld() {
        if (this.mc.world != null) {
            this.mc.world.sendQuittingDisconnectingPacket();
            this.mc.loadWorld((WorldClient) null);
        }
    }

    private void enterWorld() {
        int port = P2PBridge.INSTANCE.getProxyPort();
        this.mc.displayGuiScreen(new GuiConnecting(new GuiMainMenu(), this.mc, "127.0.0.1", port));
    }

    private void fail(String key) {
        if (amSuccessor) leaveCurrentWorld();
        this.statusKey = key;
        this.failed = true;
        this.initGui();
    }

    private void toTitle() {
        leaveCurrentWorld();
        PeerCraftUi.setScreen(this.mc, new GuiMainMenu());
    }

    @Override
    public void onGuiClosed() {
        if (lookup != null) {
            lookup.stop();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1 && !failed) {
            return; // ESC blocked while migrating in progress — only the terminal failure state allows it
        }
        super.keyTyped(typedChar, keyCode);
    }

    private List<String> bodyLines() {
        return PeerCraftUi.wrap(this.fontRenderer, PeerCraftLang.tr(statusKey), Math.min(this.width - 60, 360));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        int cx = this.width / 2;
        int y = this.height / 2 - 30;
        this.drawCenteredString(this.fontRenderer, PeerCraftLang.tr("peercraft.handoff.migrating.title"), cx, y, 0xFFFFFFFF);
        y += 22;
        int color = failed ? 0xFFFF5555 : 0xFFCCCCCC;
        for (String line : bodyLines()) {
            this.drawCenteredString(this.fontRenderer, line, cx, y, color);
            y += 12;
        }
    }
}
