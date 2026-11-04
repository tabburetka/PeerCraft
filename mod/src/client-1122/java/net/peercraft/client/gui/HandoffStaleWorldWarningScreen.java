package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.peercraft.client.handoff.PeercraftWorldMeta;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Forge 1.12.2 backport of {@code src/main/.../client/gui/HandoffStaleWorldWarningScreen.java}
 * (cf. the 1.16.5 twin).
 */
public class HandoffStaleWorldWarningScreen extends PeerCraftDialogScreen {

    private final PeercraftWorldMeta meta;
    private final Runnable onProceed;
    private final Runnable onBack;
    private boolean chosen;

    public HandoffStaleWorldWarningScreen(PeercraftWorldMeta meta, Runnable onProceed, Runnable onBack) {
        super(PeerCraftLang.tr("peercraft.handoff.stale.title"), 320, 400);
        this.meta = meta;
        this.onProceed = onProceed;
        this.onBack = onBack;
    }

    @Override
    public void initGui() {
        super.initGui();
        int renderedLines = 0;
        for (String paragraph : bodyLines()) renderedLines += Math.max(1, PeerCraftUi.wrap(fontRenderer, paragraph, Math.max(1, dialog.contentWidth() - 8)).size());
        int desiredHeight = dialog.headerHeight + 6 + Math.max(1, renderedLines) * 12
                + 22 + dialog.buttonHeight() + dialog.buttonPitch();
        dialog = new SteampunkDialog(width, height, desiredHeight, PeerCraftLang.tr("peercraft.handoff.stale.title"), dialog.width);
        this.buttonList.clear();
        dialogAction(PeerCraftLang.tr("peercraft.handoff.stale.proceed"), () -> choose(onProceed), false, 0, 2);
        dialogAction(PeerCraftLang.tr("peercraft.handoff.stale.back"), () -> choose(onBack), true, 1, 2);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    private void choose(Runnable action) {
        if (chosen) {
            return;
        }
        chosen = true;
        action.run();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            choose(onBack);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private List<String> bodyLines() {
        String to = meta.handedOffTo() == null || meta.handedOffTo().isEmpty()
                ? PeerCraftLang.tr("peercraft.handoff.stale.someone")
                : meta.handedOffTo();
        String when = meta.handedOffAt() > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(meta.handedOffAt() * 1000L))
                : "?";
        return java.util.Collections.singletonList(PeerCraftLang.tr("peercraft.handoff.stale.body", to, when));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        drawBody(bodyLines(), PeerCraftUi.TEXT_TITLE);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
