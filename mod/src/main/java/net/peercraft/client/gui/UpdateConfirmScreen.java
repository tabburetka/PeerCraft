package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.update.SelfUpdateInstaller;
import net.peercraft.client.update.UpdateChecker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Opened from the title-screen update plate: offers to download this build's jar (installed by
 * the Mod Sync helper after the game exits), or to open the CurseForge page instead.
 */
public class UpdateConfirmScreen extends PeerCraftDialogScreen {
    private final Screen parent;
    private final UpdateChecker.UpdateInfo info;
    private SelfUpdateInstaller.State shownState;

    public UpdateConfirmScreen(Screen parent, UpdateChecker.UpdateInfo info) {
        super(Component.translatable("peercraft.update.confirm.title", info.latest));
        this.parent = parent;
        this.info = info;
    }

    @Override
    protected void init() {
        super.init();
        this.shownState = SelfUpdateInstaller.state();
        switch (this.shownState) {
            case DOWNLOADING:
                addRenderableWidget(dialogAction(Component.translatable("peercraft.update.confirm.cancel_download"),
                        b -> SelfUpdateInstaller.cancel(), false, 0, 1));
                break;
            case READY_RESTART:
                addRenderableWidget(dialogAction(Component.translatable("peercraft.update.confirm.quit"),
                        b -> this.minecraft.stop(), true, 0, 2));
                addRenderableWidget(dialogAction(Component.translatable("peercraft.update.confirm.later"),
                        b -> back(), false, 1, 2));
                break;
            default:
                boolean canDownload = SelfUpdateInstaller.blocker(this.info) == SelfUpdateInstaller.Blocker.NONE;
                int count = canDownload ? 3 : 2;
                int index = 0;
                if (canDownload) {
                    addRenderableWidget(dialogAction(Component.translatable("peercraft.update.confirm.install"),
                            b -> { SelfUpdateInstaller.start(this.info); rebuildWidgets(); }, true, index++, count));
                }
                addRenderableWidget(dialogAction(Component.translatable("peercraft.update.confirm.open_page"),
                        ConfirmLinkScreen.confirmLink(this.parent, this.info.url), !canDownload, index++, count));
                addRenderableWidget(dialogAction(Component.translatable("gui.cancel"), b -> back(), false, index, count));
                break;
        }
    }

    private void back() {
        PeerCraftUi.setScreen(this.minecraft, this.parent);
    }

    @Override
    public void tick() {
        super.tick();
        if (SelfUpdateInstaller.state() != this.shownState) rebuildWidgets();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return SelfUpdateInstaller.state() != SelfUpdateInstaller.State.DOWNLOADING;
    }

    @Override
    public void onClose() {
        back();
    }

    private List<String> paragraphs() {
        List<String> out = new ArrayList<>();
        switch (SelfUpdateInstaller.state()) {
            case DOWNLOADING:
                out.add(tr("peercraft.update.confirm.downloading", Math.round(SelfUpdateInstaller.progress() * 100)));
                break;
            case READY_RESTART:
                out.add(tr("peercraft.update.confirm.ready", SelfUpdateInstaller.readyVersion()));
                break;
            default:
                out.add(tr("peercraft.update.versions_plain", this.info.current, this.info.latest));
                out.add("");
                SelfUpdateInstaller.Blocker blocker = SelfUpdateInstaller.blocker(this.info);
                if (blocker == SelfUpdateInstaller.Blocker.NONE) {
                    out.add(tr("peercraft.update.confirm.body",
                            String.format(Locale.ROOT, "%.1f", this.info.file.size / (1024.0 * 1024.0))));
                } else {
                    out.add(tr(blocker.langKey()));
                }
                if (SelfUpdateInstaller.state() == SelfUpdateInstaller.State.FAILED) {
                    out.add("");
                    out.add(tr("peercraft.update.confirm.failed", SelfUpdateInstaller.failure()));
                }
                break;
        }
        return out;
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        drawBody(graphics, paragraphs(), 0xFFE9DFCB);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawBody(graphics, paragraphs(), 0xFFE9DFCB);
    }*/
    //?}
}
