package net.peercraft.client.gui;

// Forge 1.7.10 backport of src/main/.../client/gui/UpdateConfirmScreen.java. Links go straight
// to the OS like the Feedback/Donate buttons; mc.shutdown() replaces minecraft.stop().

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.peercraft.client.update.SelfUpdateInstaller;
import net.peercraft.client.update.UpdateChecker;
import org.lwjgl.input.Keyboard;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Offers to download this build's jar, or to open the CurseForge page instead. */
public class UpdateConfirmScreen extends PeerCraftDialogScreen {
    private final GuiScreen parent;
    private final UpdateChecker.UpdateInfo info;
    private SelfUpdateInstaller.State shownState;

    public UpdateConfirmScreen(GuiScreen parent, UpdateChecker.UpdateInfo info) {
        super(PeerCraftLang.tr("peercraft.update.confirm.title", info.latest), 260, 360);
        this.parent = parent;
        this.info = info;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        this.shownState = SelfUpdateInstaller.state();
        switch (this.shownState) {
            case DOWNLOADING:
                dialogAction(PeerCraftLang.tr("peercraft.update.confirm.cancel_download"), SelfUpdateInstaller::cancel, false, 0, 1);
                break;
            case READY_RESTART:
                dialogAction(PeerCraftLang.tr("peercraft.update.confirm.quit"), () -> this.mc.shutdown(), true, 0, 2);
                dialogAction(PeerCraftLang.tr("peercraft.update.confirm.later"), this::back, false, 1, 2);
                break;
            default:
                boolean canDownload = SelfUpdateInstaller.blocker(this.info) == SelfUpdateInstaller.Blocker.NONE;
                int count = canDownload ? 3 : 2;
                int index = 0;
                if (canDownload) {
                    dialogAction(PeerCraftLang.tr("peercraft.update.confirm.install"),
                            () -> { SelfUpdateInstaller.start(this.info); initGui(); }, true, index++, count);
                }
                dialogAction(PeerCraftLang.tr("peercraft.update.confirm.open_page"), this::openPage, !canDownload, index++, count);
                dialogAction(net.minecraft.client.resources.I18n.format("gui.cancel"), this::back, false, index, count);
                break;
        }
    }

    private void back() {
        this.mc.displayGuiScreen(this.parent);
    }

    private void openPage() {
        try {
            org.lwjgl.Sys.openURL(this.info.url);
        } catch (Throwable t) {
            try {
                java.awt.Desktop.getDesktop().browse(new URI(this.info.url));
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void updateScreen() {
        if (SelfUpdateInstaller.state() != this.shownState) initGui();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof IdButton) ((IdButton) button).onPress.run();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (SelfUpdateInstaller.state() != SelfUpdateInstaller.State.DOWNLOADING) back();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private List<String> paragraphs() {
        List<String> out = new ArrayList<String>();
        switch (SelfUpdateInstaller.state()) {
            case DOWNLOADING:
                out.add(PeerCraftLang.tr("peercraft.update.confirm.downloading", Math.round(SelfUpdateInstaller.progress() * 100)));
                break;
            case READY_RESTART:
                out.add(PeerCraftLang.tr("peercraft.update.confirm.ready", SelfUpdateInstaller.readyVersion()));
                break;
            default:
                out.add(PeerCraftLang.tr("peercraft.update.versions_plain", this.info.current, this.info.latest));
                out.add("");
                SelfUpdateInstaller.Blocker blocker = SelfUpdateInstaller.blocker(this.info);
                out.add(blocker == SelfUpdateInstaller.Blocker.NONE
                        ? PeerCraftLang.tr("peercraft.update.confirm.body",
                                String.format(Locale.ROOT, "%.1f", this.info.file.size / (1024.0 * 1024.0)))
                        : PeerCraftLang.tr(blocker.langKey()));
                if (SelfUpdateInstaller.state() == SelfUpdateInstaller.State.FAILED) {
                    out.add("");
                    out.add(PeerCraftLang.tr("peercraft.update.confirm.failed", SelfUpdateInstaller.failure()));
                }
                break;
        }
        return out;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        drawBody(paragraphs(), PeerCraftUi.TEXT_TITLE);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
