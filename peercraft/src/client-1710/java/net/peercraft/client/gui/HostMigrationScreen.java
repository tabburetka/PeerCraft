package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.peercraft.client.modsync.ClientModSyncAgent;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.handoff.HandoffProtocol;
import net.peercraft.network.handoff.LookupHostClient;
import net.peercraft.network.p2p.P2PBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Forge 1.7.10 backport of {@code src/main/.../client/gui/HostMigrationScreen.java} (cf. the
 * 1.12.2 twin, near-mechanical: {@code mc.world}/{@code mc.player} -&gt;
 * {@code mc.theWorld}/{@code mc.thePlayer}, {@code addScheduledTask} -&gt; {@code func_152344_a}).
 */
public class HostMigrationScreen extends PeerCraftDialogScreen {

    private static final Logger LOGGER = LogManager.getLogger("peercraft");
    private static final long LOOKUP_TIMEOUT_MILLIS = 3 * 60_000L;

    private final UUID successorAccountId;
    private final boolean amSuccessor;

    private volatile String statusKey = "peercraft.handoff.migrating.body";
    private volatile boolean failed;
    private volatile boolean exited;
    private net.peercraft.client.handoff.SuccessorLauncher.Launch successorLaunch;
    private LookupHostClient lookup;
    private volatile boolean started;

    private String safeRoom;
    private java.util.concurrent.CompletableFuture<Void> safeJoin;
    public HostMigrationScreen(String room, java.util.concurrent.CompletableFuture<Void> join) {
        this(new UUID(0, 0), false); safeRoom = room; safeJoin = join;
    }
    public void cancelSafeReconnect() {
        exited = true; failed = true; if (lookup != null) lookup.stop();
        if (safeJoin != null) safeJoin.cancel(false);
    }
    public HostMigrationScreen(UUID successorAccountId, boolean amSuccessor) {
        super(PeerCraftLang.tr("peercraft.handoff.migrating.title"), 320, 400);
        this.successorAccountId = successorAccountId;
        this.amSuccessor = amSuccessor;
    }

    private <T extends GuiButton> T addButton(T button) {
        this.buttonList.add(button);
        return button;
    }

    @Override
    public void initGui() {
        super.initGui();
        int renderedLines = bodyLines().size();
        int actions = (failed ? 1 : 0);
        int desiredHeight = dialog.headerHeight + 6 + Math.max(3, renderedLines) * 12
                + 18 + actions * dialog.buttonPitch();
        dialog = new SteampunkDialog(width, height, desiredHeight, PeerCraftLang.tr("peercraft.handoff.migrating.title"), 400);
        this.buttonList.clear();
        if (failed) dialogAction(PeerCraftLang.tr("peercraft.modsync.restart.back"), this::toTitle, false, 0, 1);
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
    protected void actionPerformed(GuiButton button) {
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
                        Minecraft.getMinecraft().func_152344_a(() -> {
                            if (failed || exited) return;
                            fail(reasonKey);
                            PeerCraftUi.setScreen(Minecraft.getMinecraft(), HostMigrationScreen.this);
                        });
                    }
                });
    }

    private void startReconnect() {
        if (safeRoom != null) { connectTo(safeRoom); return; }
        this.statusKey = "peercraft.handoff.migrating.waiting_for_host";
        this.lookup = new LookupHostClient();
        lookup.start(successorAccountId, PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(),
                LOOKUP_TIMEOUT_MILLIS, new LookupHostClient.Callback() {
                    @Override
                    public void onFound(String roomCode) {
                        Minecraft.getMinecraft().func_152344_a(() -> connectTo(roomCode));
                    }

                    @Override
                    public void onGaveUp() {
                        Minecraft.getMinecraft().func_152344_a(() -> fail("peercraft.handoff.abort.no_response"));
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
                        Minecraft.getMinecraft().func_152344_a(HostMigrationScreen.this::enterWorld);
                    }
                    @Override public void onFailed(String reason) {
                        Minecraft.getMinecraft().func_152344_a(() -> fail(reason));
                    }
                },
                new ClientModSyncAgent(this, roomCode));
    }

    private void leaveCurrentWorld() {
        if (this.mc.theWorld != null) {
            this.mc.theWorld.sendQuittingDisconnectingPacket();
            this.mc.loadWorld((WorldClient) null);
        }
    }

    private void enterWorld() {
        if (failed || exited) return;
        int port = P2PBridge.INSTANCE.getProxyPort();
        PeerCraftUi.connectLocal(new GuiMainMenu(), port);
    }

    private void leaveAttemptWorld() {
        net.minecraft.server.MinecraftServer server = this.mc.getIntegratedServer();
        if (server != null && successorLaunch != null && successorLaunch.ownsServer(server,
                net.peercraft.client.handoff.WorldArchiver.worldDir(server))) leaveCurrentWorld();
    }

    private void fail(String key) {
        if (failed || exited) return;
        this.failed = true;
        if (safeJoin != null) safeJoin.completeExceptionally(new java.io.IOException(key));
        if (successorLaunch != null) successorLaunch.cancel();
        if (lookup != null) lookup.stop();
        if (amSuccessor) leaveAttemptWorld();
        this.statusKey = key;
        this.initGui();
    }

    private void toTitle() {
        exited = true;
        if (successorLaunch != null) successorLaunch.cancel();
        if (lookup != null) lookup.stop();
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
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1 && !failed) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private List<String> bodyLines() {
        return PeerCraftUi.wrap(this.fontRendererObj, PeerCraftLang.tr(statusKey), dialog.contentWidth() - 8);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        drawBody(bodyLines(), failed ? PeerCraftUi.TEXT_ERROR : PeerCraftUi.TEXT_TITLE);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
