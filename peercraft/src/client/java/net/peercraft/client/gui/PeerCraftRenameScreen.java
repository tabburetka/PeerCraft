package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.network.account.AccountClient;
import org.lwjgl.glfw.GLFW;

/** Rename for unlicensed accounts only — see AccountService.rename, which rejects licensed accounts server-side too. */
public class PeerCraftRenameScreen extends Screen {

    private final Screen lastScreen;

    private EditBox newNameBox;
    private Button saveButton;
    private Component statusMessage = Component.empty();
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftRenameScreen(Screen lastScreen) {
        super(Component.translatable("peercraft.gui.rename.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 30;

        this.newNameBox = new EditBox(this.font, centerX - 100, y, 200, 20, Component.translatable("peercraft.gui.rename.field"));
        this.newNameBox.setMaxLength(16);
        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            this.newNameBox.setValue(session.displayName());
        }
        this.addRenderableWidget(this.newNameBox);
        this.setInitialFocus(this.newNameBox);

        y += 26;
        this.saveButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.rename.save"), b -> onSave())
                .bounds(centerX - 100, y, 200, 20).build());

        y += 26;
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.common.back"), b -> this.minecraft.setScreen(this.lastScreen))
                .bounds(centerX - 100, y, 200, 20).build());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && this.saveButton.active) {
            onSave();
            return true;
        }
        return false;
    }

    private void onSave() {
        String newName = this.newNameBox.getValue().trim();
        if (newName.length() < 3 || newName.length() > 16) {
            this.statusMessage = Component.translatable("peercraft.gui.register.nickname_length_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }

        this.saveButton.active = false;
        this.statusMessage = Component.translatable("peercraft.gui.rename.saving");
        this.statusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.renameDisplayName(newName, new AccountClient.RenameCallback() {
            @Override
            public void onSuccess(String appliedName) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    AccountClient.AccountSession updated = AccountSessionHolder.current();
                    if (updated != null) {
                        AccountSessionHolder.persist(updated);
                    }
                    minecraft.setScreen(new PeerCraftAccountScreen(lastScreen));
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    statusMessage = Component.literal(reason);
                    statusColor = PeerCraftUi.TEXT_ERROR;
                    saveButton.active = true;
                });
            }
        });
    }

    private void runOnClientThread(Runnable action) {
        Minecraft.getInstance().execute(action);
    }

    private boolean stillOnThisScreen() {
        return Minecraft.getInstance().screen == this;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 60, PeerCraftUi.TEXT_TITLE);
        graphics.drawCenteredString(this.font, this.statusMessage, this.width / 2, this.height / 2 + 40, this.statusColor);
    }
}
