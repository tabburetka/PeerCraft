package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.peercraft.network.handoff.HandoffClientAgent;
import net.peercraft.network.handoff.HandoffProtocol;

import java.util.List;

/**
 * Forge 1.7.10 backport of {@code src/main/.../client/gui/HandoffOfferScreen.java} (cf. the
 * 1.12.2 twin, near-mechanical from there): {@code fontRenderer} -&gt; {@code fontRendererObj};
 * no {@code throws IOException} on {@code actionPerformed}/{@code keyTyped} (that arrived in
 * 1.8); no {@code GuiScreen#addButton} (1.9+) -&gt; local shim.
 */
public class HandoffOfferScreen extends GuiScreen {

    private final HandoffProtocol.Offer offer;
    private final HandoffClientAgent agent;
    private final Runnable onClosed;
    private final Runnable onAccept;

    private volatile String statusKey;
    private volatile boolean terminal;
    private volatile long recvBytes;
    private volatile long recvTotal;
    private boolean closeHandled;

    public HandoffOfferScreen(HandoffProtocol.Offer offer, HandoffClientAgent agent, Runnable onClosed, Runnable onAccept) {
        this.offer = offer;
        this.agent = agent;
        this.onClosed = onClosed;
        this.onAccept = onAccept;
    }

    private static String hostLabel(HandoffProtocol.Offer offer) {
        String w = offer.worldLabel();
        return (w == null || w.trim().isEmpty()) ? "…" : w;
    }

    private <T extends GuiButton> T addButton(T button) {
        this.buttonList.add(button);
        return button;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cx = this.width / 2;
        if (terminal) {
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.modsync.restart.back"), this::close)
                    .bounds(cx - 100, this.height - 40, 200, 20).build());
            return;
        }
        if (statusKey != null) {
            return;
        }
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.handoff.offer.accept"), this::accept)
                .bounds(cx - 155, this.height - 44, 150, 20).build());
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.handoff.offer.decline"), this::decline)
                .bounds(cx + 5, this.height - 44, 150, 20).build());
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1 && !(statusKey == null || terminal)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void accept() {
        onAccept.run();
        agent.accept();
        this.statusKey = "peercraft.handoff.status.transferring";
        this.initGui();
    }

    private void decline() {
        if (closeHandled) {
            return;
        }
        closeHandled = true;
        agent.decline("peercraft.handoff.decline.declined");
        onClosed.run();
        close();
    }

    public void onReceiveProgress(long received, long total) {
        this.recvBytes = received;
        this.recvTotal = total;
    }

    public void onWorldReceived() {
        this.statusKey = "peercraft.handoff.migrating.waiting_for_host";
    }

    public void onAbortedExternally(String reasonKey) {
        this.statusKey = reasonKey;
        this.terminal = true;
        this.initGui();
    }

    private void close() {
        if (!closeHandled) {
            closeHandled = true;
            onClosed.run();
        }
        PeerCraftUi.setScreen(this.mc, null);
    }

    @Override
    public void onGuiClosed() {
        if (closeHandled) {
            return;
        }
        closeHandled = true;
        onClosed.run();
        if (statusKey == null) {
            agent.decline("peercraft.handoff.decline.declined");
        }
    }

    private List<String> bodyLines() {
        String body = statusKey != null
                ? PeerCraftLang.tr(statusKey)
                : PeerCraftLang.tr("peercraft.handoff.offer.body", hostLabel(offer), PeerCraftUi.humanSize(offer.estArchiveBytes()));
        List<String> out = PeerCraftUi.wrap(this.fontRendererObj, body, Math.min(this.width - 60, 360));
        long total = this.recvTotal;
        if (statusKey != null && !terminal && total > 0) {
            long got = Math.min(this.recvBytes, total);
            int pct = (int) Math.round(100.0 * got / total);
            out.add(PeerCraftUi.humanSize(got) + " / " + PeerCraftUi.humanSize(total) + "  (" + pct + "%)");
        }
        return out;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        int cx = this.width / 2;
        int y = this.height / 2 - 50;
        this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.handoff.offer.title", hostLabel(offer)), cx, y, 0xFFFFFFFF);
        y += 22;
        for (String line : bodyLines()) {
            this.drawCenteredString(this.fontRendererObj, line, cx, y, 0xFFCCCCCC);
            y += 12;
        }
        if (offer.friendsOnly()) {
            y += 8;
            for (String line : PeerCraftUi.wrap(this.fontRendererObj,
                    PeerCraftLang.tr("peercraft.handoff.picker.friends_only_note"), Math.min(this.width - 60, 360))) {
                this.drawCenteredString(this.fontRendererObj, line, cx, y, 0xFFAAAAAA);
                y += 12;
            }
        }
    }
}
