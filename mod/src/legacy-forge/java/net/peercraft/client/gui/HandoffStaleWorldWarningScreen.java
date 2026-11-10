package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.peercraft.client.handoff.PeercraftWorldMeta;

//? if >=1.12 {
import java.io.IOException;
//?}
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 //? if >=1.12 {
 * Forge 1.12.2 backport of {@code src/main/.../client/gui/HandoffStaleWorldWarningScreen.java}
 * (cf. the 1.16.5 twin).
 //?} else {
//~  * Forge 1.7.10 backport of {@code src/main/.../client/gui/HandoffStaleWorldWarningScreen.java}
//~  * (cf. the 1.12.2 twin, mechanical from there).
 //?}
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

    //? if <1.12 {
//~     private <T extends GuiButton> T addButton(T button) {
//~         this.buttonList.add(button);
//~         return button;
//~     }
//~
    //?}
    @Override
    public void initGui() {
        super.initGui();
        int renderedLines = 0;
        //? if >=1.12 {
        for (String paragraph : bodyLines()) renderedLines += Math.max(1, PeerCraftUi.wrap(fontRenderer, paragraph, Math.max(1, dialog.contentWidth() - 8)).size());
        //?} else {
//~         for (String paragraph : bodyLines()) renderedLines += Math.max(1, PeerCraftUi.wrap(fontRendererObj, paragraph, Math.max(1, dialog.contentWidth() - 8)).size());
        //?}
        int desiredHeight = dialog.headerHeight + 6 + Math.max(1, renderedLines) * 12
                + 22 + dialog.buttonHeight() + dialog.buttonPitch();
        dialog = new SteampunkDialog(width, height, desiredHeight, PeerCraftLang.tr("peercraft.handoff.stale.title"), dialog.width);
        this.buttonList.clear();
        dialogAction(PeerCraftLang.tr("peercraft.handoff.stale.proceed"), () -> choose(onProceed), false, 0, 2);
        dialogAction(PeerCraftLang.tr("peercraft.handoff.stale.back"), () -> choose(onBack), true, 1, 2);
    }

    @Override
    //? if >=1.12 {
    protected void actionPerformed(GuiButton button) throws IOException {
    //?} else {
//~     protected void actionPerformed(GuiButton button) {
    //?}
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
    //? if >=1.12 {
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
    //?} else {
//~     protected void keyTyped(char typedChar, int keyCode) {
    //?}
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
