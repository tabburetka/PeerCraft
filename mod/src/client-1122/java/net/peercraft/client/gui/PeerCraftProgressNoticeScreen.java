package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

public class PeerCraftProgressNoticeScreen extends PeerCraftDialogScreen {
    private final GuiScreen parent;
    private final Runnable continuation;
    private boolean acknowledged;
    public PeerCraftProgressNoticeScreen(GuiScreen parent) { this(parent, null); }
    public PeerCraftProgressNoticeScreen(GuiScreen parent, Runnable continuation) {
        super(PeerCraftLang.tr("peercraft.gui.progress_notice.title"), 340, 440);
        this.parent = parent;
        this.continuation = continuation;
    }
    public static boolean beforeConnecting(GuiScreen parent, Runnable continuation) {
        net.peercraft.network.account.AccountClient.AccountSession session = net.peercraft.client.account.AccountSessionHolder.current();
        if (session == null) {
            PeerCraftUi.setScreen(net.minecraft.client.Minecraft.getMinecraft(), new PeerCraftConfirmScreen(accepted -> {
                PeerCraftUi.setScreen(net.minecraft.client.Minecraft.getMinecraft(), accepted ? new PeerCraftAccountScreen(parent) : parent);
            }, PeerCraftLang.tr("peercraft.gui.account_required.title"), PeerCraftLang.tr("peercraft.gui.account_required.message"), PeerCraftLang.tr("peercraft.gui.account_required.open"), PeerCraftLang.tr("peercraft.gui.common.back")));
            return true;
        }
        if (session.licensed()
                || !net.peercraft.client.account.AccountProgressNotice.firstDisplay(session.accountId())) return false;
        PeerCraftUi.setScreen(net.minecraft.client.Minecraft.getMinecraft(), new PeerCraftProgressNoticeScreen(parent, continuation));
        return true;
    }
    private void acknowledge() {
        if (acknowledged) return;
        acknowledged = true;
        closeNotice();
        if (this.continuation != null) this.continuation.run();
    }
    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        dialogAction(PeerCraftLang.tr("peercraft.gui.progress_notice.understood"), this::acknowledge, true, 0, 1);
    }

    private void closeNotice() { PeerCraftUi.setScreen(this.mc, this.parent); }
    @Override
    protected void actionPerformed(GuiButton button) { if (button instanceof IdButton) ((IdButton) button).onPress.run(); }
    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (keyCode == 1) closeNotice();
        else if (keyCode == 28 || keyCode == 156) acknowledge();
        else super.keyTyped(typedChar, keyCode);
    }
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        drawBody(java.util.Collections.singletonList(PeerCraftLang.tr("peercraft.gui.progress_notice.message")), PeerCraftUi.TEXT_TITLE);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
