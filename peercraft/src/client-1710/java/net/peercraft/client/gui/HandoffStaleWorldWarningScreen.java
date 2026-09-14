package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.peercraft.client.handoff.PeercraftWorldMeta;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Forge 1.7.10 backport of {@code src/main/.../client/gui/HandoffStaleWorldWarningScreen.java}
 * (cf. the 1.12.2 twin, mechanical from there).
 */
public class HandoffStaleWorldWarningScreen extends GuiScreen {

    private final PeercraftWorldMeta meta;
    private final Runnable onProceed;
    private final Runnable onBack;
    private boolean chosen;

    public HandoffStaleWorldWarningScreen(PeercraftWorldMeta meta, Runnable onProceed, Runnable onBack) {
        this.meta = meta;
        this.onProceed = onProceed;
        this.onBack = onBack;
    }

    private <T extends GuiButton> T addButton(T button) {
        this.buttonList.add(button);
        return button;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cx = this.width / 2;
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.handoff.stale.proceed"),
                        () -> choose(onProceed))
                .bounds(cx - 155, this.height - 44, 150, 20).build());
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.handoff.stale.back"),
                        () -> choose(onBack))
                .bounds(cx + 5, this.height - 44, 150, 20).build());
    }

    @Override
    protected void actionPerformed(GuiButton button) {
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
    protected void keyTyped(char typedChar, int keyCode) {
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
        return PeerCraftUi.wrap(this.fontRendererObj, PeerCraftLang.tr("peercraft.handoff.stale.body", to, when),
                Math.min(this.width - 60, 380));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        int cx = this.width / 2;
        int y = this.height / 2 - 40;
        this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.handoff.stale.title"), cx, y, 0xFFFF5555);
        y += 22;
        for (String line : bodyLines()) {
            this.drawCenteredString(this.fontRendererObj, line, cx, y, 0xFFCCCCCC);
            y += 12;
        }
    }
}
