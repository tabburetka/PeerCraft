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
public class HandoffOfferScreen extends PeerCraftDialogScreen {

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
        super.init();
        int renderedLines = 0;
        for (String line : bodyLines()) renderedLines += Math.max(1,
                font.split(Component.literal(line), Math.max(1, dialog.contentWidth() - 8)).size());
        int actions = (terminal ? 1 : statusKey == null ? 2 : 0);
        int desiredHeight = dialog.headerHeight + 6 + Math.max(3, renderedLines) * 12
                + 18 + actions * dialog.buttonPitch();
        dialog = new SteampunkDialog(width, height, desiredHeight, title);
        this.clearWidgets();
        int cx = this.width / 2;
        if (terminal) {
            this.addRenderableWidget(dialogAction(Component.translatable("peercraft.modsync.restart.back"), b -> close(), true, 0, 1));
            return;
        }
        if (statusKey != null) {
            // accepted, waiting — no buttons, just the status line
            return;
        }
        this.addRenderableWidget(dialogAction(Component.translatable("peercraft.handoff.offer.accept"), b -> accept(), true, 0, 2));
        this.addRenderableWidget(dialogAction(Component.translatable("peercraft.handoff.offer.decline"), b -> decline(), false, 1, 2));
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
        List<String> out = PeerCraftUi.wrap(this.font, body, Math.max(1, this.dialog.contentWidth() - 8));
        long total = this.recvTotal;
        if (statusKey != null && !terminal && total > 0) {
            long got = Math.min(this.recvBytes, total);
            int pct = (int) Math.round(100.0 * got / total);
            out.add(PeerCraftUi.humanSize(got) + " / " + PeerCraftUi.humanSize(total) + "  (" + pct + "%)");
        }
        if (offer.friendsOnly()) {
            out.add("");
            out.add(Component.translatable("peercraft.handoff.picker.friends_only_note").getString());
        }
        return out;
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        drawBody(graphics, bodyLines(), 0xFFE9DFCB);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawBody(graphics, bodyLines(), 0xFFE9DFCB);
    }*/
    //?}
}
