package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.network.handoff.HandoffClientAgent;
import net.peercraft.network.handoff.HandoffProtocol;

import java.util.List;

/**
 * Shown to the player a host chose as the handoff successor: "%s wants to hand you the world".
 * Accept &rarr; reply ACCEPT and wait for the world to arrive; Decline &rarr; reply DECLINE and
 * drop back into the game. The M2/M3 receive + launch flow takes over once MIGRATE arrives.
 */
public class HandoffOfferScreen extends Screen {

    private final HandoffProtocol.Offer offer;
    private final HandoffClientAgent agent;
    private final Runnable onClosed;
    private final Runnable onAccept;

    private volatile String statusKey;   // non-null once accepted or aborted
    private volatile boolean terminal;   // true once an OK button should just close
    private volatile long recvBytes;
    private volatile long recvTotal;

    public HandoffOfferScreen(HandoffProtocol.Offer offer, HandoffClientAgent agent, Runnable onClosed, Runnable onAccept) {
        super(Component.translatable("peercraft.handoff.offer.title", hostLabel(offer)));
        this.offer = offer;
        this.agent = agent;
        this.onClosed = onClosed;
        this.onAccept = onAccept;
    }

    private static String hostLabel(HandoffProtocol.Offer offer) {
        // The host's own name isn't in the offer; the world label is the closest human anchor.
        String w = offer.worldLabel();
        return (w == null || w.isBlank()) ? "…" : w;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        int cx = this.width / 2;
        if (terminal) {
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.modsync.restart.back"), b -> close())
                    .bounds(cx - 100, this.height - 40, 200, 20).build());
            return;
        }
        if (statusKey != null) {
            // accepted, waiting — no buttons, just the status line
            return;
        }
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.handoff.offer.accept"), b -> accept())
                .bounds(cx - 155, this.height - 44, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.handoff.offer.decline"), b -> decline())
                .bounds(cx + 5, this.height - 44, 150, 20).build());
    }

    private void accept() {
        // Set up the world-archive receiver BEFORE replying ACCEPT, so the host's BEGIN can't
        // race ahead of it.
        onAccept.run();
        agent.accept();
        this.statusKey = "peercraft.handoff.status.transferring";
        this.clearWidgets();
        this.init();
    }

    private void decline() {
        agent.decline("peercraft.handoff.decline.declined");
        close();
    }

    /** World-archive receive progress (successor side). */
    public void onReceiveProgress(long received, long total) {
        this.recvBytes = received;
        this.recvTotal = total;
    }

    /** The world archive arrived and verified — now just waiting for the host's MIGRATE. */
    public void onWorldReceived() {
        this.statusKey = "peercraft.handoff.migrating.waiting_for_host";
    }

    /** Called by the controller when the host sends ABORT while this screen is open. */
    public void onAbortedExternally(String reasonKey) {
        this.statusKey = reasonKey;
        this.terminal = true;
        this.clearWidgets();
        this.init();
    }

    private void close() {
        onClosed.run();
        // Back into the game (or wherever we came from — usually null while playing).
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
                ? Component.translatable(statusKey).getString()
                : Component.translatable("peercraft.handoff.offer.body",
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

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 50;
        graphics.drawCenteredString(this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 22;
        for (String line : bodyLines()) {
            graphics.drawCenteredString(this.font, line, cx, y, 0xFFCCCCCC);
            y += 12;
        }
        if (offer.friendsOnly()) {
            y += 8;
            for (String line : PeerCraftUi.wrap(this.font,
                    Component.translatable("peercraft.handoff.picker.friends_only_note").getString(),
                    Math.min(this.width - 60, 360))) {
                graphics.drawCenteredString(this.font, line, cx, y, 0xFFAAAAAA);
                y += 12;
            }
        }
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 50;
        graphics.centeredText(this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 22;
        for (String line : bodyLines()) {
            graphics.centeredText(this.font, line, cx, y, 0xFFCCCCCC);
            y += 12;
        }
        if (offer.friendsOnly()) {
            y += 8;
            for (String line : PeerCraftUi.wrap(this.font,
                    Component.translatable("peercraft.handoff.picker.friends_only_note").getString(),
                    Math.min(this.width - 60, 360))) {
                graphics.centeredText(this.font, line, cx, y, 0xFFAAAAAA);
                y += 12;
            }
        }
    }*/
    //?}
}
