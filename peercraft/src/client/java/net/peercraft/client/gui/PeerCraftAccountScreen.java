package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.network.account.AccountClient;

/**
 * Hub screen for the account system — opened from the title screen. Not logged in: three
 * ways to log in. Logged in: current identity, rename (unlicensed only), logout. Friends/
 * search/requests navigation is added here in a later phase (Phase 3) — this screen is
 * deliberately just auth for now.
 */
public class PeerCraftAccountScreen extends Screen {

    private final Screen lastScreen;
    private Component statusMessage;
    private int statusColor = PeerCraftUi.TEXT_MUTED;
    private Button loginLicensedButton;

    public PeerCraftAccountScreen(Screen lastScreen) {
        super(Component.translatable("peercraft.gui.account.title"));
        this.lastScreen = lastScreen;
        this.statusMessage = Component.empty();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;

        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            // Position must match the "Friend code: ..." line drawn in render() — kept in sync
            // by using the same titleY/codeY formula there.
            String codeLine = Component.translatable("peercraft.gui.account.friend_code", session.friendCode()).getString();
            int codeY = (this.height / 2 - 90) + 30;
            int copySize = 14;
            this.addRenderableWidget(PeerCraftUi.squareGlyphButton(
                    centerX + this.font.width(codeLine) / 2 + 6, codeY - 3, copySize,
                    "⧉", Component.translatable("peercraft.gui.account.copy_code_tooltip").getString(),
                    b -> onCopyFriendCode(session.friendCode())));
        }

        // Logged-in view draws two extra lines (name+badge, friend code) below the title —
        // buttons start lower here than in the logged-out view to leave room for them; see render().
        int y = session == null ? this.height / 2 - 70 : this.height / 2 - 40;
        if (session == null) {
            this.loginLicensedButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.account.login_licensed"), b -> onLoginLicensed())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.account.register"), b -> this.minecraft.setScreen(new PeerCraftRegisterScreen(this)))
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.account.login_by_code"), b -> this.minecraft.setScreen(new PeerCraftLoginByCodeScreen(this)))
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
        } else {
            if (!session.licensed()) {
                this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.account.change_nickname"), b -> this.minecraft.setScreen(new PeerCraftRenameScreen(this)))
                        .bounds(centerX - 100, y, 200, 20).build());
                y += 26;
            }
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.account.logout"), b -> confirmLogout())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
        }

        y += 4;
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.common.back"), b -> this.minecraft.setScreen(this.lastScreen))
                .bounds(centerX - 100, y, 200, 20).build());
    }

    private void onCopyFriendCode(String code) {
        Minecraft.getInstance().keyboardHandler.setClipboard(code);
        this.statusMessage = Component.translatable("peercraft.gui.account.code_copied");
        this.statusColor = PeerCraftUi.TEXT_SUCCESS;
    }

    private void confirmLogout() {
        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                AccountSessionHolder.logout();
                this.minecraft.setScreen(new PeerCraftAccountScreen(this.lastScreen));
            } else {
                this.minecraft.setScreen(this);
            }
        }, Component.translatable("peercraft.gui.account.logout_confirm_title"), Component.translatable("peercraft.gui.account.logout_confirm_message")));
    }

    private void onLoginLicensed() {
        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        if (user.getType() == User.Type.LEGACY) {
            this.statusMessage = Component.translatable("peercraft.gui.account.offline_mode_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }

        this.loginLicensedButton.active = false;
        this.statusMessage = Component.translatable("peercraft.gui.account.confirming_license");
        this.statusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.loginLicensed(mc.getGameProfile(), user.getAccessToken(), mc.getMinecraftSessionService(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                runOnClientThread(() -> {
                    AccountSessionHolder.persist(session);
                    if (stillOnThisScreen()) {
                        minecraft.setScreen(new PeerCraftAccountScreen(lastScreen));
                    }
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
                    loginLicensedButton.active = true;
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
        int centerX = this.width / 2;
        int titleY = this.height / 2 - 90;
        graphics.drawCenteredString(this.font, this.title, centerX, titleY, PeerCraftUi.TEXT_TITLE);

        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            String name = Component.translatable("peercraft.gui.account.logged_in_as", session.displayName()).getString();
            PeerCraftUi.drawNameWithBadgeCentered(graphics, this.font, name, session.licensed(), centerX, titleY + 16, PeerCraftUi.TEXT_TITLE);
            graphics.drawCenteredString(this.font, Component.translatable("peercraft.gui.account.friend_code", session.friendCode()), centerX, titleY + 30, PeerCraftUi.TEXT_ACCENT);
        }

        graphics.drawCenteredString(this.font, this.statusMessage, centerX, this.height / 2 + 70, this.statusColor);
    }
}
