package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.network.chat.Component;

/** Shown when no direct route was found: suggests disabling a VPN, then a self-hosted relay. */
final class PeerCraftP2PFailScreen extends PeerCraftDialogScreen {
    private final PeerCraftJoinScreen join;
    private final Component message;

    PeerCraftP2PFailScreen(PeerCraftJoinScreen join, String reason) {
        super(Component.translatable("peercraft.gui.p2pfail.title"));
        this.join = join;
        Component body = Component.translatable("peercraft.gui.p2pfail.message");
        // A relay error says what went wrong with the relay itself; keep it above the advice.
        this.message = reason.startsWith("peercraft.p2p.fail.own_relay_")
                ? Component.translatable(reason).append("\n\n").append(body) : body;
    }

    static boolean handles(String reason) {
        return "peercraft.p2p.fail.direct_failed".equals(reason) || "peercraft.p2p.fail.hole_punching".equals(reason)
                || reason.startsWith("peercraft.p2p.fail.own_relay_");
    }

    @Override protected void init() {
        super.init();
        int lines = Math.max(1, font.split(message, Math.max(1, dialog.contentWidth() - 8)).size());
        int desiredHeight = dialog.headerHeight + 6 + lines * 12 + 22 + dialog.buttonHeight() + 2 * dialog.buttonPitch();
        dialog = new SteampunkDialog(width, height, desiredHeight, title);
        addRenderableWidget(dialogAction(Component.translatable("peercraft.p2p.retry"), b -> join.retryFromDialog(), true, 0, 3));
        addRenderableWidget(dialogAction(Component.translatable("peercraft.gui.p2pfail.own_relay"),
                b -> PeerCraftUi.setScreen(minecraft, new PeerCraftRelayScreen(join)), false, 1, 3));
        addRenderableWidget(dialogAction(Component.translatable("peercraft.gui.common.back"), b -> onClose(), false, 2, 3));
    }
    @Override public void onClose() { PeerCraftUi.setScreen(minecraft, join); }

    //? if <26.1 {
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.dialog.status(graphics, this.font, this.message, this.dialog.contentTop(),
                this.dialog.height - this.dialog.headerHeight - 3 * this.dialog.buttonPitch() - 22,
                SteampunkSettingsTheme.TEXT, mouseX, mouseY);
    }
    //?} else {
    /*@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        this.dialog.status(graphics, this.font, this.message, this.dialog.contentTop(),
                this.dialog.height - this.dialog.headerHeight - 3 * this.dialog.buttonPitch() - 22,
                SteampunkSettingsTheme.TEXT, mouseX, mouseY);
    }*/
    //?}
}
