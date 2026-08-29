package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.network.account.AccountClient;
import org.lwjgl.glfw.GLFW;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../PeerCraftRenameScreen.java}. Rename for
 * unlicensed accounts only. Differences from the shared source: {@code Component.translatable}
 * → {@code TranslatableComponent}, {@code Component.literal} → {@code TextComponent},
 * {@code Button.builder(...).bounds(...).build()} → {@link Btn}, {@code addRenderableWidget}
 * → {@code addButton}, {@code setInitialFocus} → {@code setFocused}, and {@code render} draws
 * through static {@code GuiComponent} calls with a {@link PoseStack}.
 */
public class PeerCraftRenameScreen extends Screen {

    private final Screen lastScreen;

    private EditBox newNameBox;
    private Button saveButton;
    private Component statusMessage = TextComponent.EMPTY;
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftRenameScreen(Screen lastScreen) {
        super(new TranslatableComponent("peercraft.gui.rename.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 30;

        this.newNameBox = new EditBox(this.font, centerX - 100, y, 200, 20, new TranslatableComponent("peercraft.gui.rename.field"));
        this.newNameBox.setMaxLength(16);
        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            this.newNameBox.setValue(session.displayName());
        }
        this.addButton(this.newNameBox);
        this.setFocused(this.newNameBox);

        y += 26;
        this.saveButton = this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.rename.save"), b -> onSave())
                .bounds(centerX - 100, y, 200, 20).build());

        y += 26;
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
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
            this.statusMessage = new TranslatableComponent("peercraft.gui.register.nickname_length_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        if (!PeerCraftUi.isValidUsername(newName)) {
            this.statusMessage = new TranslatableComponent("peercraft.gui.register.nickname_charset_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }

        this.saveButton.active = false;
        this.statusMessage = new TranslatableComponent("peercraft.gui.rename.saving");
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
                    PeerCraftUi.setScreen(minecraft, new PeerCraftAccountScreen(lastScreen));
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    statusMessage = new TextComponent(reason);
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
        return PeerCraftUi.isCurrentScreen(this);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, this.width / 2, this.height / 2 - 60, PeerCraftUi.TEXT_TITLE);
        GuiComponent.drawCenteredString(poseStack, this.font, this.statusMessage, this.width / 2, this.height / 2 + 40, this.statusColor);
    }
}
