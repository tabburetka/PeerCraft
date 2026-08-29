package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
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

import java.util.Locale;

/** Login for an unlicensed account on a new device — friend code + password, since nicknames aren't unique. */
public class PeerCraftLoginByCodeScreen extends Screen {

    private final Screen lastScreen;

    private EditBox friendCodeBox;
    private EditBox passwordBox;
    private Button loginButton;
    private Component statusMessage = TextComponent.EMPTY;
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftLoginByCodeScreen(Screen lastScreen) {
        super(new TranslatableComponent("peercraft.gui.login_code.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 50;

        this.friendCodeBox = new EditBox(this.font, centerX - 100, y, 200, 20, new TranslatableComponent("peercraft.gui.login_code.friend_code_field"));
        this.friendCodeBox.setMaxLength(6);
        this.friendCodeBox.setSuggestion(new TranslatableComponent("peercraft.gui.login_code.friend_code_hint").getString());
        this.addButton(this.friendCodeBox);
        this.setFocused(this.friendCodeBox);

        y += 26;
        this.passwordBox = new EditBox(this.font, centerX - 100, y, 200, 20, new TranslatableComponent("peercraft.gui.register.password_field"));
        this.passwordBox.setMaxLength(64);
        this.passwordBox.setSuggestion(new TranslatableComponent("peercraft.gui.register.password_hint").getString());
        PeerCraftUi.maskAsPassword(this.passwordBox);
        this.addButton(this.passwordBox);

        y += 26;
        this.loginButton = this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.login_code.submit"), b -> onLogin())
                .bounds(centerX - 100, y, 200, 20).build());

        y += 26;
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                .bounds(centerX - 100, y, 200, 20).build());
    }

    // Screen.keyPressed switched from (int,int,int) to a KeyEvent record parameter in 1.21.9.
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && this.loginButton.active) {
            onLogin();
            return true;
        }
        return false;
    }

    private void onLogin() {
        String friendCode = this.friendCodeBox.getValue().trim().toUpperCase(Locale.ROOT);
        String password = this.passwordBox.getValue();
        if (friendCode.length() != 6) {
            this.statusMessage = new TranslatableComponent("peercraft.gui.login_code.code_length_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        if (password.isEmpty()) {
            this.statusMessage = new TranslatableComponent("peercraft.gui.register.password_required");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }

        this.loginButton.active = false;
        this.statusMessage = new TranslatableComponent("peercraft.gui.login_code.logging_in");
        this.statusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.loginByFriendCode(friendCode, password.toCharArray(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    AccountSessionHolder.persist(session);
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
                    loginButton.active = true;
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

    // 26.1 renamed GuiGraphics -> GuiGraphicsExtractor and replaced Screen#render with
    // #extractRenderState; drawString/drawCenteredString became text/centeredText.
    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        // 1.21.6 made Screen call renderBackground() itself before render() runs — calling it
        // again here double-fires the (now once-per-frame) blur effect and crashes.
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, this.width / 2, this.height / 2 - 80, PeerCraftUi.TEXT_TITLE);
        GuiComponent.drawCenteredString(poseStack, this.font, this.statusMessage, this.width / 2, this.height / 2 + 60, this.statusColor);
    }
}
