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
    /** null while the form is showing; set to the assigned code once registration succeeds. */
    private final String registeredFriendCode;

    private EditBox nicknameBox;
    private EditBox passwordBox;
    private Button registerButton;
    private Component statusMessage = Component.empty();
    private int statusColor = PeerCraftUi.TEXT_MUTED;
    //? if =1.21.1 {
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
        int centerX = this.width / 2;
        int y = this.height / 2 - 50;

        if (this.registeredFriendCode != null) {
            this.addRenderableWidget(PeerCraftUi.themedAction(centerX - 100, this.height / 2 + 8, 200, 20,
                    Component.translatable("peercraft.gui.register.copy_code"), b -> onCopyCode(), false));
            this.addRenderableWidget(PeerCraftUi.themedAction(centerX - 100, this.height / 2 + 34, 200, 20,
                    Component.translatable("peercraft.gui.register.continue"), b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftAccountScreen(this.lastScreen)), true));
            return;
        }

        //? if =1.21.1 {
        this.nicknameBox = PeerCraftUi.themedField(this.font, centerX - 100, y, 200, 20, Component.translatable("peercraft.gui.register.nickname_field"));
        //?} else {
        /*this.nicknameBox = new EditBox(this.font, centerX - 100, y, 200, 20, Component.translatable("peercraft.gui.register.nickname_field"));*/
        //?}
        this.nicknameBox.setMaxLength(16);
        this.nicknameBox.setHint(Component.translatable("peercraft.gui.register.nickname_hint"));
        this.addRenderableWidget(this.nicknameBox);
        this.setInitialFocus(this.nicknameBox);

        y += 26;
        //? if =1.21.1 {
        this.passwordBox = PeerCraftUi.themedField(this.font, centerX - 100, y, 200, 20, Component.translatable("peercraft.gui.register.password_field"));
        //?} else {
        /*this.passwordBox = new EditBox(this.font, centerX - 100, y, 200, 20, Component.translatable("peercraft.gui.register.password_field"));*/
        //?}
        this.passwordBox.setMaxLength(64);
        this.passwordBox.setHint(Component.translatable("peercraft.gui.register.password_hint"));
        PeerCraftUi.maskAsPassword(this.passwordBox);
        this.addRenderableWidget(this.passwordBox);

        y += 26;
        //? if =1.21.1 {
        this.registerButton = this.addRenderableWidget(PeerCraftUi.themedAction(centerX - 100, y, 200, 20,
                Component.translatable("peercraft.gui.register.submit"), b -> onRegister(), true));
        //?} else {
        /*this.registerButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.register.submit"), b -> onRegister())
                .bounds(centerX - 100, y, 200, 20).build());*/
        //?}

        y += 26;
        //? if =1.21.1 {
        this.addRenderableWidget(PeerCraftUi.themedAction(centerX - 100, y, 200, 20,
                Component.translatable("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen), false));
        //?} else {
        /*this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                .bounds(centerX - 100, y, 200, 20).build());*/
        //?}
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

    // 26.1 renamed GuiGraphics -> GuiGraphicsExtractor and replaced Screen#render with
    // #extractRenderState; drawString/drawCenteredString became text/centeredText (fill unchanged).
    //? if <26.1 {
    //? if =1.21.1 {
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = Math.min(320, this.width - 16);
        int panelHeight = Math.min(this.height - 16, this.registeredFriendCode == null ? 194 : 164);
        SteampunkSettingsTheme.screenBackground(graphics, this.width, this.height,
                (this.width - panelWidth) / 2, (this.height - panelHeight) / 2, panelWidth, panelHeight,
                (System.nanoTime() - this.animationStart) / 1_000_000L);
    }
    //?}
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 1.21.6 made Screen call renderBackground() itself before render() runs — calling it
        // again here double-fires the (now once-per-frame) blur effect and crashes.
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        if (this.registeredFriendCode != null) {
            graphics.drawCenteredString(this.font, Component.translatable("peercraft.gui.register.success_title"), centerX, this.height / 2 - 90, PeerCraftUi.TEXT_TITLE);
            graphics.drawCenteredString(this.font, Component.translatable("peercraft.gui.register.success_code_label"), centerX, this.height / 2 - 55, PeerCraftUi.TEXT_TITLE);

            graphics.fill(centerX - 60, this.height / 2 - 42, centerX + 60, this.height / 2 - 20, 0x80000000);
            graphics.drawCenteredString(this.font, Component.literal(this.registeredFriendCode), centerX, this.height / 2 - 36, PeerCraftUi.TEXT_ACCENT);

            graphics.drawCenteredString(this.font, Component.translatable("peercraft.gui.register.success_hint"), centerX, this.height / 2 - 8, PeerCraftUi.TEXT_MUTED);
            graphics.drawCenteredString(this.font, this.statusMessage, centerX, this.height / 2 + 60, this.statusColor);
        } else {
            graphics.drawCenteredString(this.font, this.title, centerX, this.height / 2 - 80, PeerCraftUi.TEXT_TITLE);
            graphics.drawCenteredString(this.font, this.statusMessage, centerX, this.height / 2 + 60, this.statusColor);
        }
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        if (this.registeredFriendCode != null) {
            graphics.centeredText(this.font, Component.translatable("peercraft.gui.register.success_title"), centerX, this.height / 2 - 90, PeerCraftUi.TEXT_TITLE);
            graphics.centeredText(this.font, Component.translatable("peercraft.gui.register.success_code_label"), centerX, this.height / 2 - 55, PeerCraftUi.TEXT_TITLE);

            graphics.fill(centerX - 60, this.height / 2 - 42, centerX + 60, this.height / 2 - 20, 0x80000000);
            graphics.centeredText(this.font, Component.literal(this.registeredFriendCode), centerX, this.height / 2 - 36, PeerCraftUi.TEXT_ACCENT);

            graphics.centeredText(this.font, Component.translatable("peercraft.gui.register.success_hint"), centerX, this.height / 2 - 8, PeerCraftUi.TEXT_MUTED);
            graphics.centeredText(this.font, this.statusMessage, centerX, this.height / 2 + 60, this.statusColor);
        } else {
            graphics.centeredText(this.font, this.title, centerX, this.height / 2 - 80, PeerCraftUi.TEXT_TITLE);
            graphics.centeredText(this.font, this.statusMessage, centerX, this.height / 2 + 60, this.statusColor);
        }
    }*/
    //?}
}
