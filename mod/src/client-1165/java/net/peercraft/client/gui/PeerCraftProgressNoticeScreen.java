package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;

public class PeerCraftProgressNoticeScreen extends PeerCraftDialogScreen {
    private final Screen parent;
    private final Runnable continuation;
    public PeerCraftProgressNoticeScreen(Screen parent) { this(parent, null); }
    public PeerCraftProgressNoticeScreen(Screen parent, Runnable continuation) {
        super(new TranslatableComponent("peercraft.gui.progress_notice.title"), 300);
        this.parent = parent;
        this.continuation = continuation;
    }
    public static boolean beforeConnecting(Screen parent, Runnable continuation) {
        net.peercraft.network.account.AccountClient.AccountSession session = net.peercraft.client.account.AccountSessionHolder.current();
        if (session == null) {
            PeerCraftUi.setScreen(net.minecraft.client.Minecraft.getInstance(), new PeerCraftConfirmScreen(accepted -> {
                PeerCraftUi.setScreen(net.minecraft.client.Minecraft.getInstance(), accepted ? new PeerCraftAccountScreen(parent) : parent);
            }, new TranslatableComponent("peercraft.gui.account_required.title"), new TranslatableComponent("peercraft.gui.account_required.message"), new TranslatableComponent("peercraft.gui.account_required.open"), new TranslatableComponent("peercraft.gui.common.back")));
            return true;
        }
        if (session.licensed()
                || !net.peercraft.client.account.AccountProgressNotice.firstDisplay(session.accountId())) return false;
        PeerCraftUi.setScreen(net.minecraft.client.Minecraft.getInstance(), new PeerCraftProgressNoticeScreen(parent, continuation));
        return true;
    }
    private void acknowledge() {
        onClose();
        if (this.continuation != null) this.continuation.run();
    }
    @Override
    protected void init() {
        super.init();
        dialogAction(new TranslatableComponent("peercraft.gui.progress_notice.understood"),
                b -> acknowledge(), false, 0, 1);
    }
    @Override
    public void onClose() { PeerCraftUi.setScreen(this.minecraft, this.parent); }
    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        drawBody(poseStack, java.util.Collections.singletonList(new TranslatableComponent("peercraft.gui.progress_notice.message").getString()), PeerCraftUi.TEXT_TITLE);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }
}
