package net.peercraft.client.gui;
import net.minecraft.client.gui.*;
import java.io.IOException;
import net.peercraft.client.account.AccountSessionHolder;
public final class PeerCraftProgressToolsScreen extends PeerCraftDialogScreen {
    private IdButton emailButton;
    private final GuiScreen parent;
    public PeerCraftProgressToolsScreen(GuiScreen parent) { super(PeerCraftLang.tr("peercraft.gui.transfer.tools"), 180); this.parent = parent; }
    @Override public void initGui() {
        super.initGui(); buttonList.clear();
        emailButton = null;
        boolean email = AccountSessionHolder.current() != null && !AccountSessionHolder.current().licensed();
        int actions = email ? 4 : 3;
        dialog = new SteampunkDialog(width, height, dialog.headerHeight + 6 + actions * dialog.buttonPitch() + 18, PeerCraftLang.tr("peercraft.gui.transfer.tools"));
        dialogAction(PeerCraftLang.tr("peercraft.gui.transfer.title"), () -> PeerCraftUi.setScreen(mc, new PeerCraftProgressTransferScreen(this)), true, 0, actions);
        dialogAction(PeerCraftLang.tr("peercraft.gui.progress_notice.open"), () -> PeerCraftUi.setScreen(mc, new PeerCraftProgressNoticeScreen(this)), false, 1, actions);
        if (email) {
            emailButton = dialogAction(PeerCraftLang.tr("peercraft.gui.email.bind_title"), () -> PeerCraftUi.setScreen(mc, new PeerCraftEmailScreen(this, true)), false, 2, actions);
            emailButton.enabled = false;
        }
        dialogAction(PeerCraftLang.tr("peercraft.gui.common.back"), this::close, false, actions - 1, actions);
    }
    private void close() { PeerCraftUi.setScreen(mc, parent); }
    @Override protected void actionPerformed(GuiButton button) { if (button instanceof IdButton) ((IdButton) button).onPress.run(); }
    @Override protected void keyTyped(char typed, int key) { if (key == 1) close(); else super.keyTyped(typed, key); }
    @Override public void drawScreen(int x, int y, float delta) { drawDefaultBackground(); super.drawScreen(x, y, delta);
        if (emailButton != null && x >= emailButton.xPosition && x < emailButton.xPosition + emailButton.width
                && y >= emailButton.yPosition && y < emailButton.yPosition + emailButton.height) {
            drawHoveringText(java.util.Collections.singletonList(PeerCraftLang.tr("peercraft.gui.email.not_implemented")), x, y, mc.fontRenderer);
        }
    }
}
