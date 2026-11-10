package net.peercraft.client.gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.account.AccountSessionHolder;
public final class PeerCraftProgressToolsScreen extends PeerCraftDialogScreen {
    private net.minecraft.client.gui.components.Button emailButton;
    private final Screen parent;
    public PeerCraftProgressToolsScreen(Screen parent) { super(Component.translatable("peercraft.gui.transfer.tools")); this.parent = parent; }
    @Override protected void init() {
        super.init();
        emailButton = null;
        boolean email = AccountSessionHolder.current() != null && !AccountSessionHolder.current().licensed();
        int actions = email ? 4 : 3;
        dialog = new SteampunkDialog(width, height, dialog.headerHeight + 6 + actions * dialog.buttonPitch() + 18, title);
        addRenderableWidget(dialogAction(Component.translatable("peercraft.gui.transfer.title"), b -> PeerCraftUi.setScreen(minecraft, new PeerCraftProgressTransferScreen(this)), true, 0, actions));
        addRenderableWidget(dialogAction(Component.translatable("peercraft.gui.progress_notice.open"), b -> PeerCraftUi.setScreen(minecraft, new PeerCraftProgressNoticeScreen(this)), false, 1, actions));
        if (email) {
            emailButton = addRenderableWidget(dialogAction(Component.translatable("peercraft.gui.email.bind_title"), b -> PeerCraftUi.setScreen(minecraft, new PeerCraftEmailScreen(this, true)), false, 2, actions));
            emailButton.active = false;
            emailButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("peercraft.gui.email.not_implemented")));
        }
        addRenderableWidget(dialogAction(Component.translatable("peercraft.gui.common.back"), b -> onClose(), false, actions - 1, actions));
    }
    @Override public void onClose() { PeerCraftUi.setScreen(minecraft, parent); }
}
