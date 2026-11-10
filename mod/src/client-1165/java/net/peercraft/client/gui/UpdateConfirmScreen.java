package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.client.update.SelfUpdateInstaller;
import net.peercraft.client.update.UpdateChecker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../UpdateConfirmScreen.java}: offers to download
 * this build's jar, or to open the CurseForge page instead.
 */
public class UpdateConfirmScreen extends PeerCraftDialogScreen {
    private final Screen parent;
    private final UpdateChecker.UpdateInfo info;
    private SelfUpdateInstaller.State shownState;

    public UpdateConfirmScreen(Screen parent, UpdateChecker.UpdateInfo info) {
        super(new TranslatableComponent("peercraft.update.confirm.title", info.latest), 260);
        this.parent = parent;
        this.info = info;
    }

    @Override
    protected void init() {
        super.init();
        this.shownState = SelfUpdateInstaller.state();
        switch (this.shownState) {
            case DOWNLOADING:
                dialogAction(new TranslatableComponent("peercraft.update.confirm.cancel_download"),
                        (Button.OnPress) b -> SelfUpdateInstaller.cancel(), false, 0, 1);
                break;
            case READY_RESTART:
                dialogAction(new TranslatableComponent("peercraft.update.confirm.quit"),
                        (Button.OnPress) b -> this.minecraft.stop(), true, 0, 2);
                dialogAction(new TranslatableComponent("peercraft.update.confirm.later"),
                        (Button.OnPress) b -> back(), false, 1, 2);
                break;
            default:
                boolean canDownload = SelfUpdateInstaller.blocker(this.info) == SelfUpdateInstaller.Blocker.NONE;
                int count = canDownload ? 3 : 2;
                int index = 0;
                if (canDownload) {
                    dialogAction(new TranslatableComponent("peercraft.update.confirm.install"),
                            (Button.OnPress) b -> { SelfUpdateInstaller.start(this.info); rebuild(); }, true, index++, count);
                }
                dialogAction(new TranslatableComponent("peercraft.update.confirm.open_page"),
                        (Button.OnPress) b -> openPage(), !canDownload, index++, count);
                dialogAction(new TranslatableComponent("gui.cancel"), (Button.OnPress) b -> back(), false, index, count);
                break;
        }
    }

    private void rebuild() {
        init(this.minecraft, this.width, this.height);
    }

    private void back() {
        this.minecraft.setScreen(this.parent);
    }

    private void openPage() {
        String url = this.info.url;
        this.minecraft.setScreen(new ConfirmLinkScreen(open -> {
            if (open) Util.getPlatform().openUri(url);
            this.minecraft.setScreen(this.parent);
        }, url, true));
    }

    @Override
    public void tick() {
        super.tick();
        if (SelfUpdateInstaller.state() != this.shownState) rebuild();
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
                out.add(blocker == SelfUpdateInstaller.Blocker.NONE
                        ? tr("peercraft.update.confirm.body", String.format(Locale.ROOT, "%.1f", this.info.file.size / (1024.0 * 1024.0)))
                        : tr(blocker.langKey()));
                if (SelfUpdateInstaller.state() == SelfUpdateInstaller.State.FAILED) {
                    out.add("");
                    out.add(tr("peercraft.update.confirm.failed", SelfUpdateInstaller.failure()));
                }
                break;
        }
        return out;
    }

    private static String tr(String key, Object... args) {
        return new TranslatableComponent(key, args).getString();
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        drawBody(poseStack, paragraphs(), PeerCraftUi.TEXT_TITLE);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }
}
