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

/** Login for an unlicensed account on a new device — friend code or stable account ID + password. */
public class PeerCraftLoginByCodeScreen extends PeerCraftDialogScreen {

    private final Screen lastScreen;

    private EditBox friendCodeBox;
    private EditBox passwordBox;
    private Button loginButton;
    private Component statusMessage = TextComponent.EMPTY;
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftLoginByCodeScreen(Screen lastScreen) {
        super(new TranslatableComponent("peercraft.gui.login_code.title"), 270);
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        super.init();
        boolean compact = height < 300;
        int desiredHeight = (compact ? 36 : 46) + 6 + 6 + 3 * (compact ? 22 : 30)
                + (compact ? 18 : 24) + 8 + 26 + 12;
        dialog = new SteampunkDialog(width, height, desiredHeight, title);
        String previousfriendCodeBox = friendCodeBox == null ? null : friendCodeBox.getValue();
        String previouspasswordBox = passwordBox == null ? null : passwordBox.getValue();

        int centerX = this.width / 2;
        int y = dialog.contentTop() + 6;

        this.friendCodeBox = new SteampunkField(this.font, dialog.contentX(), y, dialog.contentWidth(), dialog.buttonHeight(), new TranslatableComponent("peercraft.gui.login_code.friend_code_field"));
        this.friendCodeBox.setMaxLength(6);
        PeerCraftUi.placeholder(this.friendCodeBox, new TranslatableComponent("peercraft.gui.login_code.friend_code_hint").getString());
        if (previousfriendCodeBox != null) this.friendCodeBox.setValue(previousfriendCodeBox);
        this.addButton(this.friendCodeBox);
        this.setFocused(this.friendCodeBox);

        y += dialog.buttonPitch();
        this.passwordBox = new SteampunkField(this.font, dialog.contentX(), y, dialog.contentWidth(), dialog.buttonHeight(), new TranslatableComponent("peercraft.gui.register.password_field"));
        this.passwordBox.setMaxLength(64);
        PeerCraftUi.placeholder(this.passwordBox, new TranslatableComponent("peercraft.gui.register.password_hint").getString());
        PeerCraftUi.maskAsPassword(this.passwordBox);
        if (previouspasswordBox != null) this.passwordBox.setValue(previouspasswordBox);
        this.addButton(this.passwordBox);

        y += dialog.buttonPitch();
        this.loginButton = this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.login_code.submit"), b -> onLogin())
                .bounds(dialog.contentX(), y, dialog.contentWidth(), dialog.buttonHeight()).primary().build());

        y += dialog.buttonPitch();
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                .bounds(dialog.contentX(), y, dialog.contentWidth(), dialog.buttonHeight()).build());
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
        dialog.status(poseStack, this.font, this.statusMessage, dialog.top + dialog.height - 36, 26, this.statusColor);
    }
    @Override public void onClose() { PeerCraftUi.setScreen(minecraft, lastScreen); }

}
