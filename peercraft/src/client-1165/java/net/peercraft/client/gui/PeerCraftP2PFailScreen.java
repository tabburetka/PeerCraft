package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.TranslatableComponent;
import java.util.ArrayList;
import java.util.List;

/** Minecraft 1.16.5 backport of {@code src/main/.../PeerCraftP2PFailScreen.java}. */
final class PeerCraftP2PFailScreen extends PeerCraftDialogScreen {
    private final PeerCraftJoinScreen join;
    private final List<String> paragraphs = new ArrayList<>();

    PeerCraftP2PFailScreen(PeerCraftJoinScreen join, String reason) {
        super(new TranslatableComponent("peercraft.gui.p2pfail.title"), 260);
        this.join = join;
        // A relay error says what went wrong with the relay itself; keep it above the advice.
        if (reason.startsWith("peercraft.p2p.fail.own_relay_")) {
            paragraphs.add(new TranslatableComponent(reason).getString());
            paragraphs.add("");
        }
        for (String line : new TranslatableComponent("peercraft.gui.p2pfail.message").getString().split("\n")) paragraphs.add(line);
    }

    static boolean handles(String reason) {
        return "peercraft.p2p.fail.direct_failed".equals(reason) || "peercraft.p2p.fail.hole_punching".equals(reason)
                || reason.startsWith("peercraft.p2p.fail.own_relay_");
    }

    @Override protected void init() {
        super.init();
        int lines = 0;
        for (String paragraph : paragraphs) lines += Math.max(1, PeerCraftUi.wrap(font, paragraph, Math.max(1, dialog.contentWidth() - 10)).size());
        int desiredHeight = dialog.headerHeight + 6 + lines * 12 + 22 + dialog.buttonHeight() + 2 * dialog.buttonPitch();
        dialog = new SteampunkDialog(width, height, desiredHeight, title);
        dialogAction(new TranslatableComponent("peercraft.p2p.retry"), b -> join.retryFromDialog(), true, 0, 3);
        dialogAction(new TranslatableComponent("peercraft.gui.p2pfail.own_relay"),
                b -> PeerCraftUi.setScreen(minecraft, new PeerCraftRelayScreen(join)), false, 1, 3);
        dialogAction(new TranslatableComponent("peercraft.gui.common.back"), b -> onClose(), false, 2, 3);
    }
    @Override public void onClose() { PeerCraftUi.setScreen(minecraft, join); }
    @Override public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        drawBody(pose, paragraphs, PeerCraftUi.TEXT_TITLE);
        super.render(pose, mouseX, mouseY, partialTick);
    }
}
