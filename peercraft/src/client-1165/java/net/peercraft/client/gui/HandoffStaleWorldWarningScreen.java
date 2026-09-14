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
public class HandoffStaleWorldWarningScreen extends Screen {

    private final PeercraftWorldMeta meta;
    private final Runnable onProceed;
    private final Runnable onBack;
    private boolean chosen;

    public HandoffStaleWorldWarningScreen(PeercraftWorldMeta meta, Runnable onProceed, Runnable onBack) {
        super(new TranslatableComponent("peercraft.handoff.stale.title"));
        this.meta = meta;
        this.onProceed = onProceed;
        this.onBack = onBack;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.handoff.stale.proceed"), b -> choose(onProceed))
                .bounds(cx - 155, this.height - 44, 150, 20).build());
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.handoff.stale.back"), b -> choose(onBack))
                .bounds(cx + 5, this.height - 44, 150, 20).build());
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
        return PeerCraftUi.wrap(this.font, body, Math.min(this.width - 60, 380));
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 40;
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, cx, y, 0xFFFF5555);
        y += 22;
        for (String line : bodyLines()) {
            GuiComponent.drawCenteredString(poseStack, this.font, line, cx, y, 0xFFCCCCCC);
            y += 12;
        }
    }
}
