package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.client.handoff.PeercraftWorldMeta;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../client/gui/HandoffStaleWorldWarningScreen.java}.
 */
public class HandoffStaleWorldWarningScreen extends PeerCraftDialogScreen {

    private final PeercraftWorldMeta meta;
    private final Runnable onProceed;
    private final Runnable onBack;
    private boolean chosen;

    public HandoffStaleWorldWarningScreen(PeercraftWorldMeta meta, Runnable onProceed, Runnable onBack) {
        super(new TranslatableComponent("peercraft.handoff.stale.title"), 300);
        this.meta = meta;
        this.onProceed = onProceed;
        this.onBack = onBack;
    }

    @Override
    protected void init() {
        super.init();
        int desiredHeight = dialog.headerHeight + 6 + Math.max(1, bodyLines().size()) * 12
                + 22 + dialog.buttonHeight() + dialog.buttonPitch();
        dialog = new SteampunkDialog(width, height, desiredHeight, title, dialog.width);
        int cx = this.width / 2;
        dialogAction(new TranslatableComponent("peercraft.handoff.stale.proceed"), b -> choose(onProceed), true, 0, 2);
        dialogAction(new TranslatableComponent("peercraft.handoff.stale.back"), b -> choose(onBack), false, 1, 2);
    }

    private void choose(Runnable action) {
        if (chosen) {
            return;
        }
        chosen = true;
        action.run();
    }

    @Override
    public void onClose() {
        choose(onBack);
    }

    private List<String> bodyLines() {
        String to = meta.handedOffTo() == null || meta.handedOffTo().isEmpty()
                ? new TranslatableComponent("peercraft.handoff.stale.someone").getString()
                : meta.handedOffTo();
        String when = meta.handedOffAt() > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(meta.handedOffAt() * 1000L))
                : "?";
        String body = new TranslatableComponent("peercraft.handoff.stale.body", to, when).getString();
        return PeerCraftUi.wrap(this.font, body, dialog.contentWidth() - 10);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        drawBody(poseStack, bodyLines(), PeerCraftUi.TEXT_ERROR);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }
}
