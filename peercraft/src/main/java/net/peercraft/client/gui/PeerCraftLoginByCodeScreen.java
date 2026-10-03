package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.network.account.AccountClient;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** Login for an unlicensed account on a new device — friend code or stable account ID + password. */
public class PeerCraftLoginByCodeScreen extends Screen {

    private final Screen lastScreen;
    private SteampunkDialog dialog;
    private int statusY, successHintHeight;

    private EditBox friendCodeBox;
    private EditBox passwordBox;
    private Button loginButton;
    private Component statusMessage = Component.empty();
    private int statusColor = PeerCraftUi.TEXT_MUTED;
    //? if >=1.21.1 {
    private final long animationStart = System.nanoTime();
    //?}

    public PeerCraftLoginByCodeScreen(Screen lastScreen) {
        super(Component.translatable("peercraft.gui.login_code.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        String firstValue = friendCodeBox == null ? "" : friendCodeBox.getValue();
        String secondValue = passwordBox == null ? "" : passwordBox.getValue();
        Component subtitle = Component.literal(title.getString().replace("PeerCraft — ", ""));
        dialog = new SteampunkDialog(width, height, 220, subtitle);
        int fieldRow = dialog.buttonPitch() + 12;
        int desiredHeight = dialog.headerHeight + 6 + 2 * fieldRow + 2 * dialog.buttonPitch() + 26 + 12;
        dialog = new SteampunkDialog(width, height, desiredHeight, subtitle);
        int x = dialog.contentX(), w = dialog.contentWidth(), h = dialog.buttonHeight();
        int y = dialog.contentTop() + 12;
        friendCodeBox = new SteampunkSettingsTheme.Field(font, x, y, w, h, Component.translatable("peercraft.gui.login_code.friend_code_field"));
        friendCodeBox.setMaxLength(6);
        friendCodeBox.setHint(Component.translatable("peercraft.gui.login_code.friend_code_hint"));
        friendCodeBox.setValue(firstValue);
        addRenderableWidget(friendCodeBox);
        setInitialFocus(friendCodeBox);
        y += fieldRow;
        passwordBox = new SteampunkSettingsTheme.Field(font, x, y, w, h, Component.translatable("peercraft.gui.register.password_field"));
        passwordBox.setMaxLength(64);
        passwordBox.setHint(Component.translatable("peercraft.gui.register.password_hint"));
        passwordBox.setValue(secondValue);
        PeerCraftUi.maskAsPassword(passwordBox);
        addRenderableWidget(passwordBox);
        y = dialog.contentTop() + 2 * fieldRow;
        loginButton = addRenderableWidget(SteampunkSettingsTheme.action(x, y, w, h,
                Component.translatable("peercraft.gui.login_code.submit"), b -> onLogin(), true));
        y += dialog.buttonPitch();
        addRenderableWidget(SteampunkSettingsTheme.action(x, y, w, h,
                Component.translatable("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(minecraft, lastScreen), false));
        statusY = y + dialog.buttonPitch() + 2;
    }

    // Screen.keyPressed switched from (int,int,int) to a KeyEvent record parameter in 1.21.9.
    //? if <1.21.9 {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
    //?} else {
    /*
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (super.keyPressed(event)) {
            return true;
        }
        int keyCode = event.key();
    */
    //?}
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
            this.statusMessage = Component.translatable("peercraft.gui.login_code.code_length_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        if (password.isEmpty()) {
            this.statusMessage = Component.translatable("peercraft.gui.register.password_required");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }

        this.loginButton.active = false;
        this.statusMessage = Component.translatable("peercraft.gui.login_code.logging_in");
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
                    statusMessage = Component.literal(reason);
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

    //? if <26.1 {
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        dialog.background(graphics, font, width, height, (System.nanoTime() - animationStart) / 1_000_000L);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, Component.translatable("peercraft.gui.login_code.friend_code_field"), dialog.contentX(), friendCodeBox.getY() - 12, SteampunkSettingsTheme.MUTED, false);
        graphics.drawString(font, Component.translatable("peercraft.gui.register.password_field"), dialog.contentX(), passwordBox.getY() - 12, SteampunkSettingsTheme.MUTED, false);
        dialog.status(graphics, font, statusMessage, statusY, 26, statusColor, mouseX, mouseY);
    }
    //?} else {
    /*@Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        dialog.background(graphics, font, width, height, (System.nanoTime() - animationStart) / 1_000_000L);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(font, Component.translatable("peercraft.gui.login_code.friend_code_field"), dialog.contentX(), friendCodeBox.getY() - 12, SteampunkSettingsTheme.MUTED, false);
        graphics.text(font, Component.translatable("peercraft.gui.register.password_field"), dialog.contentX(), passwordBox.getY() - 12, SteampunkSettingsTheme.MUTED, false);
        dialog.status(graphics, font, statusMessage, statusY, 26, statusColor, mouseX, mouseY);
    }*/
    //?}
}
