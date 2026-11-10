package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.peercraft.client.theme.SteampunkPalette;
import java.util.List;
import java.util.ArrayList;
import net.minecraft.network.chat.Component;

abstract class PeerCraftDialogScreen extends Screen {
    protected SteampunkDialog dialog;
    private final long openedAt = System.currentTimeMillis();
    private final int desiredHeight;
    private int footerActions;
    private int bodyOffset;
    private int bodyLines;
    private int bodyRows;
    protected PeerCraftDialogScreen(Component title, int desiredHeight) {
        super(title);
        this.desiredHeight = desiredHeight;
    }
    @Override protected void init() {
        footerActions = 0;
        dialog = new SteampunkDialog(width, height, desiredHeight, title);
    }
    @Override public void renderBackground(PoseStack pose) {
        boolean inWorld = minecraft != null && minecraft.level != null;
        if (inWorld) super.renderBackground(pose);
        dialog.background(pose, font, width, height, System.currentTimeMillis() - openedAt, inWorld);
    }
    protected Button dialogAction(Component label, Button.OnPress callback, boolean primary, int index, int count) {
        footerActions = Math.max(footerActions, count);
        Btn.Builder builder = Btn.builder(label, callback).bounds(dialog.contentX(),
                dialog.top + dialog.height - 12 - dialog.buttonHeight() - (count - 1 - index) * dialog.buttonPitch(),
                dialog.contentWidth(), dialog.buttonHeight());
        return addButton((primary ? builder.primary() : builder).build());
    }
    protected int bodyBottom() {
        return dialog.top + dialog.height - 22 - (footerActions == 0 ? 0 : dialog.buttonHeight() + (footerActions - 1) * dialog.buttonPitch());
    }
    /** Wrapping and scrolling keep the full explanation available above the action buttons. */
    protected void drawBody(PoseStack pose, List<String> paragraphs, int color) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : paragraphs) lines.addAll(PeerCraftUi.wrap(font, paragraph, Math.max(1, dialog.contentWidth() - 10)));
        bodyRows = Math.max(0, (bodyBottom() - dialog.contentTop()) / 12);
        bodyLines = lines.size();
        bodyOffset = Math.max(0, Math.min(bodyOffset, bodyLines - bodyRows));
        int shown = Math.min(bodyRows, bodyLines - bodyOffset);
        for (int i = 0; i < shown; i++) {
            GuiComponent.drawCenteredString(pose, font, lines.get(i + bodyOffset), width / 2, dialog.contentTop() + i * 12, color);
        }
        if (bodyRows > 0 && bodyLines > bodyRows) {
            int trackHeight = bodyRows * 12;
            int thumbHeight = Math.max(8, trackHeight * bodyRows / bodyLines);
            int y = dialog.contentTop() + (trackHeight - thumbHeight) * bodyOffset / (bodyLines - bodyRows);
            GuiComponent.fill(pose, dialog.left + dialog.width - 7, dialog.contentTop(), dialog.left + dialog.width - 5, dialog.contentTop() + trackHeight, SteampunkPalette.BORDER);
            GuiComponent.fill(pose, dialog.left + dialog.width - 7, y, dialog.left + dialog.width - 5, y + thumbHeight, SteampunkPalette.ACCENT);
        }
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (x >= dialog.contentX() && x < dialog.left + dialog.width && y >= dialog.contentTop() && y < bodyBottom() && bodyRows > 0 && bodyLines > bodyRows && delta != 0) {
            bodyOffset = Math.max(0, Math.min(bodyLines - bodyRows, bodyOffset - (int) Math.signum(delta) * 3));
            return true;
        }
        return super.mouseScrolled(x, y, delta);
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (bodyRows > 0 && bodyLines > bodyRows && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN || key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP)) {
            bodyOffset = Math.max(0, Math.min(bodyLines - bodyRows, bodyOffset + (key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN ? bodyRows : -bodyRows)));
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

}
