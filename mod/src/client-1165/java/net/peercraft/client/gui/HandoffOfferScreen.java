package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.network.handoff.HandoffClientAgent;
import net.peercraft.network.handoff.HandoffProtocol;

import java.util.List;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../client/gui/HandoffOfferScreen.java}. Deltas:
 * render(GuiGraphics) -&gt; render(PoseStack); graphics.drawCenteredString -&gt;
 * GuiComponent.drawCenteredString(poseStack, ...); Button.builder -&gt; Btn.builder;
 * addRenderableWidget -&gt; addButton; Component.translatable -&gt; new TranslatableComponent;
 * String.isBlank() -&gt; trim().isEmpty(). Keep in sync with the original.
 */
public class HandoffOfferScreen extends PeerCraftDialogScreen {

    private final HandoffProtocol.Offer offer;
    private final HandoffClientAgent agent;
    private final Runnable onClosed;
    private final Runnable onAccept;

    private volatile String statusKey;
    private volatile boolean terminal;
    private volatile long recvBytes;
    private volatile long recvTotal;

    public HandoffOfferScreen(HandoffProtocol.Offer offer, HandoffClientAgent agent, Runnable onClosed, Runnable onAccept) {
        super(new TranslatableComponent("peercraft.handoff.offer.title", hostLabel(offer)), 300);
        this.offer = offer;
        this.agent = agent;
        this.onClosed = onClosed;
        this.onAccept = onAccept;
    }

    private static String hostLabel(HandoffProtocol.Offer offer) {
        String w = offer.worldLabel();
        return (w == null || w.trim().isEmpty()) ? "…" : w;
    }

    @Override
    protected void init() {
        super.init();
        int renderedLines = bodyLines().size();
        int actions = (terminal ? 1 : statusKey == null ? 2 : 0);
        int desiredHeight = dialog.headerHeight + 6 + Math.max(3, renderedLines) * 12
                + 18 + actions * dialog.buttonPitch();
        dialog = new SteampunkDialog(width, height, desiredHeight, title);
        this.buttons.clear();
        this.children.clear();
        int cx = this.width / 2;
        if (terminal) {
            dialogAction(new TranslatableComponent("peercraft.modsync.restart.back"), b -> close(), false, 0, 1);
            return;
        }
        if (statusKey != null) {
            return;
        }
        dialogAction(new TranslatableComponent("peercraft.handoff.offer.accept"), b -> accept(), true, 0, 2);
        dialogAction(new TranslatableComponent("peercraft.handoff.offer.decline"), b -> decline(), false, 1, 2);
    }

    private void accept() {
        onAccept.run();
        agent.accept();
        this.statusKey = "peercraft.handoff.status.transferring";
        this.init();
    }

    private void decline() {
        agent.decline("peercraft.handoff.decline.declined");
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
        this.init();
    }

    private void close() {
        onClosed.run();
        PeerCraftUi.setScreen(this.minecraft, null);
    }

    @Override
    public void onClose() {
        if (statusKey == null) {
            decline();
        } else if (terminal) {
            close();
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return statusKey == null || terminal;
    }

    private List<String> bodyLines() {
        String body = statusKey != null
                ? new TranslatableComponent(statusKey).getString()
                : new TranslatableComponent("peercraft.handoff.offer.body",
                        hostLabel(offer), PeerCraftUi.humanSize(offer.estArchiveBytes())).getString();
        List<String> out = PeerCraftUi.wrap(this.font, body, dialog.contentWidth() - 10);
        long total = this.recvTotal;
        if (statusKey != null && !terminal && total > 0) {
            long got = Math.min(this.recvBytes, total);
            int pct = (int) Math.round(100.0 * got / total);
            out.add(PeerCraftUi.humanSize(got) + " / " + PeerCraftUi.humanSize(total) + "  (" + pct + "%)");
        }
        return out;
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        java.util.List<String> lines = bodyLines();
        if (offer.friendsOnly()) lines.add(new TranslatableComponent("peercraft.handoff.picker.friends_only_note").getString());
        drawBody(poseStack, lines, terminal ? PeerCraftUi.TEXT_ERROR : PeerCraftUi.TEXT_TITLE);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }
}
