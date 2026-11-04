package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;
import org.lwjgl.input.Keyboard;
import java.util.ArrayList;
import java.util.List;

/** Forge 1.12.2 backport of {@code src/main/.../PeerCraftP2PFailScreen.java}. */
final class PeerCraftP2PFailScreen extends PeerCraftDialogScreen {
    private final PeerCraftJoinScreen join;
    private final List<String> paragraphs = new ArrayList<>();

    PeerCraftP2PFailScreen(PeerCraftJoinScreen join, String reason) {
        super(PeerCraftLang.tr("peercraft.gui.p2pfail.title"), 260);
        this.join = join;
        // A relay error says what went wrong with the relay itself; keep it above the advice.
        if (reason.startsWith("peercraft.p2p.fail.own_relay_")) {
            paragraphs.add(PeerCraftLang.tr(reason));
            paragraphs.add("");
        }
        for (String line : PeerCraftLang.tr("peercraft.gui.p2pfail.message").split("\n")) paragraphs.add(line);
    }

    static boolean handles(String reason) {
        return "peercraft.p2p.fail.direct_failed".equals(reason) || "peercraft.p2p.fail.hole_punching".equals(reason)
                || reason.startsWith("peercraft.p2p.fail.own_relay_");
    }

    @Override public void initGui() {
        super.initGui(); this.buttonList.clear();
        int lines = 0;
        for (String paragraph : paragraphs) lines += Math.max(1, PeerCraftUi.wrap(fontRenderer, paragraph, Math.max(1, dialog.contentWidth() - 8)).size());
        int desiredHeight = dialog.headerHeight + 6 + lines * 12 + 22 + dialog.buttonHeight() + 2 * dialog.buttonPitch();
        dialog = new SteampunkDialog(width, height, desiredHeight, PeerCraftLang.tr("peercraft.gui.p2pfail.title"));
        dialogAction(PeerCraftLang.tr("peercraft.p2p.retry"), join::retryFromDialog, true, 0, 3);
        dialogAction(PeerCraftLang.tr("peercraft.gui.p2pfail.own_relay"),
                () -> PeerCraftUi.setScreen(mc, new PeerCraftRelayScreen(join)), false, 1, 3);
        dialogAction(PeerCraftLang.tr("peercraft.gui.common.back"), () -> PeerCraftUi.setScreen(mc, join), false, 2, 3);
    }
    @Override protected void actionPerformed(GuiButton button) throws java.io.IOException {
        if (button instanceof IdButton) ((IdButton) button).onPress.run();
    }
    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawBody(paragraphs, PeerCraftUi.TEXT_TITLE);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
    @Override protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) { PeerCraftUi.setScreen(mc, join); return; }
        super.keyTyped(typedChar, keyCode);
    }
}
