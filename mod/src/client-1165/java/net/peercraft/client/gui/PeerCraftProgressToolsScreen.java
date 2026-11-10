package net.peercraft.client.gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.client.account.AccountSessionHolder;
public final class PeerCraftProgressToolsScreen extends PeerCraftDialogScreen {
    private net.minecraft.client.gui.components.Button emailButton;
    private final Screen parent;
    public PeerCraftProgressToolsScreen(Screen parent) { super(new TranslatableComponent("peercraft.gui.transfer.tools"), 190); this.parent = parent; }
    @Override protected void init() {
        super.init();
        emailButton = null;
        boolean email = AccountSessionHolder.current() != null && !AccountSessionHolder.current().licensed();
        int actions = email ? 4 : 3;
        dialog = new SteampunkDialog(width, height, dialog.headerHeight + 6 + actions * dialog.buttonPitch() + 18, title);
        dialogAction(new TranslatableComponent("peercraft.gui.transfer.title"), b -> PeerCraftUi.setScreen(minecraft, new PeerCraftProgressTransferScreen(this)), true, 0, actions);
        dialogAction(new TranslatableComponent("peercraft.gui.progress_notice.open"), b -> PeerCraftUi.setScreen(minecraft, new PeerCraftProgressNoticeScreen(this)), false, 1, actions);
        if (email) {
            emailButton = dialogAction(new TranslatableComponent("peercraft.gui.email.bind_title"), b -> PeerCraftUi.setScreen(minecraft, new PeerCraftEmailScreen(this, true)), false, 2, actions);
            emailButton.active = false;
        }
        dialogAction(new TranslatableComponent("peercraft.gui.common.back"), b -> onClose(), false, actions - 1, actions);
    }
    @Override public void render(com.mojang.blaze3d.vertex.PoseStack pose, int x, int y, float delta) {
        renderBackground(pose);
        super.render(pose, x, y, delta);
        if (emailButton != null && x >= emailButton.x && x < emailButton.x + emailButton.getWidth()
                && y >= emailButton.y && y < emailButton.y + emailButton.getHeight()) {
            renderTooltip(pose, new TranslatableComponent("peercraft.gui.email.not_implemented"), x, y);
        }
    }
    @Override public void onClose() { PeerCraftUi.setScreen(minecraft, parent); }
}
