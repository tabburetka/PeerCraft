package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
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
        super(new TranslatableComponent("peercraft.gui.account.title"));
        this.lastScreen = lastScreen;
        this.statusMessage = TextComponent.EMPTY;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;

        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            // Position must match the "Friend code: ..." line drawn in render() — kept in sync
            // by using the same titleY/codeY formula there.
            String codeLine = new TranslatableComponent("peercraft.gui.account.friend_code", session.friendCode()).getString();
            int codeY = (this.height / 2 - 90) + 30;
            int copySize = 14;
            this.addButton(PeerCraftUi.squareGlyphButton(
                    centerX + this.font.width(codeLine) / 2 + 6, codeY - 3, copySize,
                    "⧉", new TranslatableComponent("peercraft.gui.account.copy_code_tooltip").getString(),
                    b -> onCopyFriendCode(session.friendCode())));
        }

        // Logged-in view draws two extra lines (name+badge, friend code) below the title —
        // buttons start lower here than in the logged-out view to leave room for them; see render().
        int y = session == null ? this.height / 2 - 70 : this.height / 2 - 40;
        if (session == null) {
            this.loginLicensedButton = this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.account.login_licensed"), b -> onLoginLicensed())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
            this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.account.register"), b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftRegisterScreen(this)))
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
            this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.account.login_by_code"), b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftLoginByCodeScreen(this)))
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
        } else {
            if (!session.licensed()) {
                this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.account.change_nickname"), b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftRenameScreen(this)))
                        .bounds(centerX - 100, y, 200, 20).build());
                y += 26;
            }
            this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.account.logout"), b -> confirmLogout())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
        }

        y += 4;
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                .bounds(centerX - 100, y, 200, 20).build());
    }

    private void onCopyFriendCode(String code) {
        Minecraft.getInstance().keyboardHandler.setClipboard(code);
        this.statusMessage = new TranslatableComponent("peercraft.gui.account.code_copied");
        this.statusColor = PeerCraftUi.TEXT_SUCCESS;
    }

    private void confirmLogout() {
        PeerCraftUi.setScreen(this.minecraft, new ConfirmScreen(confirmed -> {
            if (confirmed) {
                AccountSessionHolder.logout();
                PeerCraftUi.setScreen(this.minecraft, new PeerCraftAccountScreen(this.lastScreen));
            } else {
                PeerCraftUi.setScreen(this.minecraft, this);
            }
        }, new TranslatableComponent("peercraft.gui.account.logout_confirm_title"), new TranslatableComponent("peercraft.gui.account.logout_confirm_message")));
    }

    private void onLoginLicensed() {
        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        // 1.16.5's User exposes no accessor for its account type, so the up-front
        // offline-mode ("legacy" account) guard the src/main version does can't run here — an
        // offline-launched player just gets the Mojang auth failure from the server instead,
        // same end state as on 1.21.9+ where Mojang retired legacy accounts and the guard
        // became dead code anyway.

        this.loginLicensedButton.active = false;
        this.statusMessage = new TranslatableComponent("peercraft.gui.account.confirming_license");
        this.statusColor = PeerCraftUi.TEXT_MUTED;
        // Minecraft.getMinecraftSessionService() was replaced by services().sessionService() in 1.21.9.
        com.mojang.authlib.minecraft.MinecraftSessionService sessionService = mc.getMinecraftSessionService();
        AccountClient.INSTANCE.loginLicensed(mc.getUser().getGameProfile(), user.getAccessToken(), sessionService, new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                runOnClientThread(() -> {
                    AccountSessionHolder.persist(session);
                    if (stillOnThisScreen()) {
                        PeerCraftUi.setScreen(minecraft, new PeerCraftAccountScreen(lastScreen));
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
                    statusMessage = new TextComponent(reason);
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
        int centerX = this.width / 2;
        int titleY = this.height / 2 - 90;
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, centerX, titleY, PeerCraftUi.TEXT_TITLE);

        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            String name = new TranslatableComponent("peercraft.gui.account.logged_in_as", session.displayName()).getString();
            PeerCraftUi.drawNameWithBadgeCentered(poseStack, this.font, name, session.licensed(), centerX, titleY + 16, PeerCraftUi.TEXT_TITLE);
            GuiComponent.drawCenteredString(poseStack, this.font, new TranslatableComponent("peercraft.gui.account.friend_code", session.friendCode()), centerX, titleY + 30, PeerCraftUi.TEXT_ACCENT);
        }

        GuiComponent.drawCenteredString(poseStack, this.font, this.statusMessage, centerX, this.height / 2 + 70, this.statusColor);
    }
}
