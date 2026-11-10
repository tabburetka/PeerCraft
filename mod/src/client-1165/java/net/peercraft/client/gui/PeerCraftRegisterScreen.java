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

/**
 * Registration for unlicensed ("pirate") accounts — nickname + password. On success, shows
 * the assigned friend code prominently (it's the only recovery path if this device's saved
 * session is ever lost — see the accounts plan's accepted risk on this) instead of silently
 * returning to the previous screen. The code also gets a highlighted box and a one-click
 * copy button, since asking a player to retype six characters correctly by hand is where a
 * lost-code support request starts.
 */
public class PeerCraftRegisterScreen extends PeerCraftDialogScreen {

    private final Screen lastScreen;
    /** null while the form is showing; set to the assigned code once registration succeeds. */
    private final String registeredFriendCode;

    private EditBox nicknameBox;
    private EditBox passwordBox;
    private Button registerButton;
    private Component statusMessage = TextComponent.EMPTY;
    private int statusColor = PeerCraftUi.TEXT_MUTED;
    private int successHintHeight;
    private int successActionY;

    public PeerCraftRegisterScreen(Screen lastScreen) {
        this(lastScreen, null);
    }

    private PeerCraftRegisterScreen(Screen lastScreen, String registeredFriendCode) {
        super(new TranslatableComponent("peercraft.gui.register.title"), 280);
        this.lastScreen = lastScreen;
        this.registeredFriendCode = registeredFriendCode;
    }

    @Override
    protected void init() {
        super.init();
        boolean compact = height < 300;
        int desiredHeight = (compact ? 36 : 46) + 6 + 6 + 3 * (compact ? 22 : 30)
                + (compact ? 18 : 24) + 8 + 26 + 12;
        if (registeredFriendCode != null) {
            successHintHeight = Math.max(1, PeerCraftUi.wrap(font,
                    new TranslatableComponent("peercraft.gui.register.success_hint").getString(), dialog.contentWidth()).size()) * font.lineHeight;
            desiredHeight = (compact ? 36 : 46) + 6 + 57 + successHintHeight + 8
                    + (compact ? 22 : 30) + (compact ? 18 : 24) + 8 + 26 + 12;
        }
        dialog = new SteampunkDialog(width, height, desiredHeight, title);
        String previousnicknameBox = nicknameBox == null ? null : nicknameBox.getValue();
        String previouspasswordBox = passwordBox == null ? null : passwordBox.getValue();

        int centerX = this.width / 2;
        int y = dialog.contentTop() + 6;

        if (this.registeredFriendCode != null) {
            successActionY = dialog.contentTop() + 57 + successHintHeight + 8;
            this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.register.copy_code"), b -> onCopyCode())
                    .bounds(dialog.contentX(), successActionY, dialog.contentWidth(), dialog.buttonHeight()).build());
            this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.register.continue"), b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftAccountScreen(this.lastScreen)))
                    .bounds(dialog.contentX(), successActionY + dialog.buttonPitch(), dialog.contentWidth(), dialog.buttonHeight()).primary().build());
            return;
        }

        this.nicknameBox = new SteampunkField(this.font, dialog.contentX(), y, dialog.contentWidth(), dialog.buttonHeight(), new TranslatableComponent("peercraft.gui.register.nickname_field"));
        this.nicknameBox.setMaxLength(16);
        PeerCraftUi.placeholder(this.nicknameBox, new TranslatableComponent("peercraft.gui.register.nickname_hint").getString());
        if (previousnicknameBox != null) this.nicknameBox.setValue(previousnicknameBox);
        this.addButton(this.nicknameBox);
        this.setFocused(this.nicknameBox);

        y += dialog.buttonPitch();
        this.passwordBox = new SteampunkField(this.font, dialog.contentX(), y, dialog.contentWidth(), dialog.buttonHeight(), new TranslatableComponent("peercraft.gui.register.password_field"));
        this.passwordBox.setMaxLength(64);
        PeerCraftUi.placeholder(this.passwordBox, new TranslatableComponent("peercraft.gui.register.password_hint").getString());
        PeerCraftUi.maskAsPassword(this.passwordBox);
        if (previouspasswordBox != null) this.passwordBox.setValue(previouspasswordBox);
        this.addButton(this.passwordBox);

        y += dialog.buttonPitch();
        this.registerButton = this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.register.submit"), b -> onRegister())
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
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && this.registeredFriendCode == null && this.registerButton.active) {
            onRegister();
            return true;
        }
        return false;
    }

    private void onCopyCode() {
        Minecraft.getInstance().keyboardHandler.setClipboard(this.registeredFriendCode);
        this.statusMessage = new TranslatableComponent("peercraft.gui.register.code_copied");
        this.statusColor = PeerCraftUi.TEXT_SUCCESS;
    }

    private void onRegister() {
        String nickname = this.nicknameBox.getValue().trim();
        String password = this.passwordBox.getValue();
        if (nickname.length() < 3 || nickname.length() > 16) {
            this.statusMessage = new TranslatableComponent("peercraft.gui.register.nickname_length_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        if (!PeerCraftUi.isValidUsername(nickname)) {
            this.statusMessage = new TranslatableComponent("peercraft.gui.register.nickname_charset_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        if (password.isEmpty()) {
            this.statusMessage = new TranslatableComponent("peercraft.gui.register.password_required");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }

        this.registerButton.active = false;
        this.statusMessage = new TranslatableComponent("peercraft.gui.register.registering");
        this.statusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.registerUnlicensed(nickname, password.toCharArray(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    AccountSessionHolder.persist(session);
                    PeerCraftUi.setScreen(minecraft, new PeerCraftRegisterScreen(lastScreen, session.friendCode()));
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    // `reason` comes from the account server, not authored here — can't localize it.
                    statusMessage = new TextComponent(reason);
                    statusColor = PeerCraftUi.TEXT_ERROR;
                    registerButton.active = true;
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
    // #extractRenderState; drawString/drawCenteredString became text/centeredText (fill unchanged).
    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        // 1.21.6 made Screen call renderBackground() itself before render() runs — calling it
        // again here double-fires the (now once-per-frame) blur effect and crashes.
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        if (this.registeredFriendCode != null) {
            GuiComponent.drawCenteredString(poseStack, this.font, new TranslatableComponent("peercraft.gui.register.success_title"), centerX, dialog.contentTop(), PeerCraftUi.TEXT_TITLE);
            GuiComponent.drawCenteredString(poseStack, this.font, new TranslatableComponent("peercraft.gui.register.success_code_label"), centerX, dialog.contentTop() + 16, PeerCraftUi.TEXT_TITLE);

            GuiComponent.fill(poseStack, dialog.contentX(), dialog.contentTop() + 30, dialog.contentX() + dialog.contentWidth(), dialog.contentTop() + 52, 0x80000000);
            GuiComponent.drawCenteredString(poseStack, this.font, new TextComponent(this.registeredFriendCode), centerX, dialog.contentTop() + 37, PeerCraftUi.TEXT_ACCENT);

            dialog.status(poseStack, this.font, new TranslatableComponent("peercraft.gui.register.success_hint"), dialog.contentTop() + 57, successHintHeight, PeerCraftUi.TEXT_MUTED);
            dialog.status(poseStack, this.font, this.statusMessage, dialog.top + dialog.height - 36, 26, this.statusColor);
        } else {
                dialog.status(poseStack, this.font, this.statusMessage, dialog.top + dialog.height - 36, 26, this.statusColor);
        }
    }
    @Override public void onClose() { PeerCraftUi.setScreen(minecraft, lastScreen); }

}
