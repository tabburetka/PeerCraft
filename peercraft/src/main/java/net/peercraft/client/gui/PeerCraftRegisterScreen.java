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

/**
 * Registration for unlicensed ("pirate") accounts — nickname + password. On success, shows
 * the assigned friend code prominently instead of silently returning to the previous
 * screen. A separate public account-ID recovery card is also saved on successful login. The code also gets a highlighted box and a one-click
 * copy button, since asking a player to retype six characters correctly by hand is where a
 * lost-code support request starts.
 */
public class PeerCraftRegisterScreen extends Screen {

    private final Screen lastScreen;
    private SteampunkDialog dialog;
    private int statusY, successHintHeight;
    /** null while the form is showing; set to the assigned code once registration succeeds. */
    private final String registeredFriendCode;

    private EditBox nicknameBox;
    private EditBox passwordBox;
    private Button registerButton;
    private Component statusMessage = Component.empty();
    private int statusColor = PeerCraftUi.TEXT_MUTED;
    //? if >=1.21.1 {
    private final long animationStart = System.nanoTime();
    //?}

    public PeerCraftRegisterScreen(Screen lastScreen) {
        this(lastScreen, null);
    }

    private PeerCraftRegisterScreen(Screen lastScreen, String registeredFriendCode) {
        super(Component.translatable("peercraft.gui.register.title"));
        this.lastScreen = lastScreen;
        this.registeredFriendCode = registeredFriendCode;
    }

    @Override
    protected void init() {
        String firstValue = nicknameBox == null ? "" : nicknameBox.getValue();
        String secondValue = passwordBox == null ? "" : passwordBox.getValue();
        Component subtitle = Component.literal(title.getString().replace("PeerCraft — ", ""));
        dialog = new SteampunkDialog(width, height, 220, subtitle);
        int fieldRow = dialog.buttonPitch() + 12;
        int desiredHeight = dialog.headerHeight + 6 + 2 * fieldRow + 2 * dialog.buttonPitch() + 26 + 12;
        if (registeredFriendCode != null) {
            subtitle = Component.translatable("peercraft.gui.register.success_title");
            successHintHeight = Math.max(1, PeerCraftUi.wrap(font,
                    Component.translatable("peercraft.gui.register.success_hint").getString(), dialog.contentWidth()).size()) * 12;
            desiredHeight = dialog.headerHeight + 6 + 48 + successHintHeight + 8 + 2 * dialog.buttonPitch() + 26 + 12;
        }
        dialog = new SteampunkDialog(width, height, desiredHeight, subtitle);
        int x = dialog.contentX(), w = dialog.contentWidth(), h = dialog.buttonHeight();
        int y = dialog.contentTop() + 12;
        if (registeredFriendCode != null) {
            int actionY = dialog.contentTop() + 48 + successHintHeight + 8;
            addRenderableWidget(SteampunkSettingsTheme.action(x, actionY, w, h,
                    Component.translatable("peercraft.gui.register.copy_code"), b -> onCopyCode(), false));
            addRenderableWidget(SteampunkSettingsTheme.action(x, actionY + dialog.buttonPitch(), w, h,
                    Component.translatable("peercraft.gui.register.continue"), b -> PeerCraftUi.setScreen(minecraft, new PeerCraftAccountScreen(lastScreen)), true));
            statusY = actionY + 2 * dialog.buttonPitch() + 2;
            return;
        }
        nicknameBox = new SteampunkSettingsTheme.Field(font, x, y, w, h, Component.translatable("peercraft.gui.register.nickname_field"));
        nicknameBox.setMaxLength(16);
        nicknameBox.setHint(Component.translatable("peercraft.gui.register.nickname_hint"));
        nicknameBox.setValue(firstValue);
        addRenderableWidget(nicknameBox);
        setInitialFocus(nicknameBox);
        y += fieldRow;
        passwordBox = new SteampunkSettingsTheme.Field(font, x, y, w, h, Component.translatable("peercraft.gui.register.password_field"));
        passwordBox.setMaxLength(64);
        passwordBox.setHint(Component.translatable("peercraft.gui.register.password_hint"));
        passwordBox.setValue(secondValue);
        PeerCraftUi.maskAsPassword(passwordBox);
        addRenderableWidget(passwordBox);
        y = dialog.contentTop() + 2 * fieldRow;
        registerButton = addRenderableWidget(SteampunkSettingsTheme.action(x, y, w, h,
                Component.translatable("peercraft.gui.register.submit"), b -> onRegister(), true));
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
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && this.registeredFriendCode == null && this.registerButton.active) {
            onRegister();
            return true;
        }
        return false;
    }

    private void onCopyCode() {
        Minecraft.getInstance().keyboardHandler.setClipboard(this.registeredFriendCode);
        this.statusMessage = Component.translatable("peercraft.gui.register.code_copied");
        this.statusColor = PeerCraftUi.TEXT_SUCCESS;
    }

    private void onRegister() {
        String nickname = this.nicknameBox.getValue().trim();
        String password = this.passwordBox.getValue();
        if (nickname.length() < 3 || nickname.length() > 16) {
            this.statusMessage = Component.translatable("peercraft.gui.register.nickname_length_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        if (!PeerCraftUi.isValidUsername(nickname)) {
            this.statusMessage = Component.translatable("peercraft.gui.register.nickname_charset_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        if (password.isEmpty()) {
            this.statusMessage = Component.translatable("peercraft.gui.register.password_required");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }

        this.registerButton.active = false;
        this.statusMessage = Component.translatable("peercraft.gui.register.registering");
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
                    statusMessage = Component.literal(reason);
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

    //? if <26.1 {
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        dialog.background(graphics, font, width, height, (System.nanoTime() - animationStart) / 1_000_000L);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (registeredFriendCode != null) {
            int y = dialog.contentTop();
            graphics.drawCenteredString(font, Component.translatable("peercraft.gui.register.success_code_label"), width / 2, y, SteampunkSettingsTheme.TEXT);
            SteampunkSettingsTheme.frame(graphics, dialog.contentX(), y + 14, dialog.contentWidth(), 24, net.peercraft.client.theme.SteampunkPalette.CONTROL, SteampunkSettingsTheme.BORDER);
            graphics.drawCenteredString(font, registeredFriendCode, width / 2, y + 22, SteampunkSettingsTheme.ACCENT);
            dialog.status(graphics, font, Component.translatable("peercraft.gui.register.success_hint"), y + 48,
                    successHintHeight, SteampunkSettingsTheme.MUTED, mouseX, mouseY);
        } else {
        graphics.drawString(font, Component.translatable("peercraft.gui.register.nickname_field"), dialog.contentX(), nicknameBox.getY() - 12, SteampunkSettingsTheme.MUTED, false);
        graphics.drawString(font, Component.translatable("peercraft.gui.register.password_field"), dialog.contentX(), passwordBox.getY() - 12, SteampunkSettingsTheme.MUTED, false);
        }
        dialog.status(graphics, font, statusMessage, statusY, 26, statusColor, mouseX, mouseY);
    }
    //?} else {
    /*@Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        dialog.background(graphics, font, width, height, (System.nanoTime() - animationStart) / 1_000_000L);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (registeredFriendCode != null) {
            int y = dialog.contentTop();
            graphics.centeredText(font, Component.translatable("peercraft.gui.register.success_code_label"), width / 2, y, SteampunkSettingsTheme.TEXT);
            SteampunkSettingsTheme.frame(graphics, dialog.contentX(), y + 14, dialog.contentWidth(), 24, net.peercraft.client.theme.SteampunkPalette.CONTROL, SteampunkSettingsTheme.BORDER);
            graphics.centeredText(font, registeredFriendCode, width / 2, y + 22, SteampunkSettingsTheme.ACCENT);
            dialog.status(graphics, font, Component.translatable("peercraft.gui.register.success_hint"), y + 48,
                    successHintHeight, SteampunkSettingsTheme.MUTED, mouseX, mouseY);
        } else {
        graphics.text(font, Component.translatable("peercraft.gui.register.nickname_field"), dialog.contentX(), nicknameBox.getY() - 12, SteampunkSettingsTheme.MUTED, false);
        graphics.text(font, Component.translatable("peercraft.gui.register.password_field"), dialog.contentX(), passwordBox.getY() - 12, SteampunkSettingsTheme.MUTED, false);
        }
        dialog.status(graphics, font, statusMessage, statusY, 26, statusColor, mouseX, mouseY);
    }*/
    //?}
}
