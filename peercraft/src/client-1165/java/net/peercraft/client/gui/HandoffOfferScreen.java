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
public class HandoffOfferScreen extends Screen {

    private final HandoffProtocol.Offer offer;
    private final HandoffClientAgent agent;
    private final Runnable onClosed;
    private final Runnable onAccept;

    private volatile String statusKey;
    private volatile boolean terminal;
    private volatile long recvBytes;
    private volatile long recvTotal;

    public HandoffOfferScreen(HandoffProtocol.Offer offer, HandoffClientAgent agent, Runnable onClosed, Runnable onAccept) {
        super(new TranslatableComponent("peercraft.handoff.offer.title", hostLabel(offer)));
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
        this.buttons.clear();
        this.children.clear();
        int cx = this.width / 2;
        if (terminal) {
            this.addButton(Btn.builder(new TranslatableComponent("peercraft.modsync.restart.back"), b -> close())
                    .bounds(cx - 100, this.height - 40, 200, 20).build());
            return;
        }
        if (statusKey != null) {
            return;
        }
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.handoff.offer.accept"), b -> accept())
                .bounds(cx - 155, this.height - 44, 150, 20).build());
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.handoff.offer.decline"), b -> decline())
                .bounds(cx + 5, this.height - 44, 150, 20).build());
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
        List<String> out = PeerCraftUi.wrap(this.font, body, Math.min(this.width - 60, 360));
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
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 50;
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 22;
        for (String line : bodyLines()) {
            GuiComponent.drawCenteredString(poseStack, this.font, line, cx, y, 0xFFCCCCCC);
            y += 12;
        }
        if (offer.friendsOnly()) {
            y += 8;
            for (String line : PeerCraftUi.wrap(this.font,
                    new TranslatableComponent("peercraft.handoff.picker.friends_only_note").getString(),
                    Math.min(this.width - 60, 360))) {
                GuiComponent.drawCenteredString(poseStack, this.font, line, cx, y, 0xFFAAAAAA);
                y += 12;
            }
        }
    }
}
