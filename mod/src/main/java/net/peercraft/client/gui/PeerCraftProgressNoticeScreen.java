package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explains the actual user-visible consequence of removing account UUID support. */
public class PeerCraftProgressNoticeScreen extends Screen {
    private final Screen parent;
    private final Runnable continuation;
    private final long animationStart = System.nanoTime();
    private SteampunkDialog dialog;
    public PeerCraftProgressNoticeScreen(Screen parent) { this(parent, null); }
    public PeerCraftProgressNoticeScreen(Screen parent, Runnable continuation) {
        super(Component.translatable("peercraft.gui.progress_notice.title"));
        this.parent = parent;
        this.continuation = continuation;
    }
    public static boolean beforeConnecting(Screen parent, Runnable continuation) {
        net.peercraft.network.account.AccountClient.AccountSession session = net.peercraft.client.account.AccountSessionHolder.current();
        if (session == null) {
            PeerCraftUi.setScreen(net.minecraft.client.Minecraft.getInstance(), new PeerCraftConfirmScreen(accepted -> {
                PeerCraftUi.setScreen(net.minecraft.client.Minecraft.getInstance(), accepted ? new PeerCraftAccountScreen(parent) : parent);
            }, Component.translatable("peercraft.gui.account_required.title"), Component.translatable("peercraft.gui.account_required.message"), Component.translatable("peercraft.gui.account_required.open"), Component.translatable("peercraft.gui.common.back")));
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
        this.dialog = new SteampunkDialog(this.width, this.height, 260, this.title);
        this.addRenderableWidget(SteampunkSettingsTheme.action(this.dialog.contentX(),
                this.dialog.top + this.dialog.height - 34, this.dialog.contentWidth(), 24,
                Component.translatable("peercraft.gui.progress_notice.understood"), b -> acknowledge(), true));
    }
    @Override
    public void onClose() { PeerCraftUi.setScreen(this.minecraft, this.parent); }

    //? if <26.1 {
    //? if <26.1 {
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.dialog.background(graphics, this.font, this.width, this.height,
                (System.nanoTime() - this.animationStart) / 1_000_000L);
    }
    //?} else {
    /* */
    //?}
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int y = this.dialog.contentTop();
        for (String line : PeerCraftUi.wrap(this.font, Component.translatable("peercraft.gui.progress_notice.message").getString(), this.dialog.contentWidth())) {
            graphics.drawString(this.font, line, this.dialog.contentX(), y, SteampunkSettingsTheme.TEXT, false);
            y += 12;
        }
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int y = this.dialog.contentTop();
        for (String line : PeerCraftUi.wrap(this.font, Component.translatable("peercraft.gui.progress_notice.message").getString(), this.dialog.contentWidth())) {
            graphics.text(this.font, line, this.dialog.contentX(), y, SteampunkSettingsTheme.TEXT, false);
            y += 12;
        }
    }*/
    //?}
    //? if >=26.1 {
    /*    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.dialog.background(graphics, this.font, this.width, this.height,
                (System.nanoTime() - this.animationStart) / 1_000_000L);
    }*/
    //?}

}
