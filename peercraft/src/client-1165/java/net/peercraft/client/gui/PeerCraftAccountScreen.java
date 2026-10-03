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
import net.peercraft.client.account.AccountProgressNotice;
import net.peercraft.network.account.AccountClient;

/**
 * Hub screen for the account system — opened from the title screen. Not logged in: three
 * ways to log in. Logged in: current identity, rename (unlicensed only), logout. Friends/
 * search/requests navigation is added here in a later phase (Phase 3) — this screen is
 * deliberately just auth for now.
 */
public class PeerCraftAccountScreen extends PeerCraftDialogScreen {

    private final Screen lastScreen;
    private Component statusMessage;
    private int statusColor = PeerCraftUi.TEXT_MUTED;
    private Button loginLicensedButton;
    private net.minecraft.resources.ResourceLocation skinTexture;
    private int identityY;
    private int identityHeight;
    private int statusY;

    public PeerCraftAccountScreen(Screen lastScreen) {
        super(new TranslatableComponent("peercraft.gui.account.title"), 340);
        this.lastScreen = lastScreen instanceof PeerCraftAccountScreen ? ((PeerCraftAccountScreen) lastScreen).lastScreen : lastScreen;
        this.statusMessage = TextComponent.EMPTY;
    }

    @Override
    protected void init() {
        super.init();
        boolean loggingIn = loginLicensedButton != null && !loginLicensedButton.active;
        AccountClient.AccountSession session = AccountSessionHolder.current();
        boolean compact = height < 300;
        int header = (compact ? 36 : 46) + 6;
        int buttons = session == null ? 3 : session.licensed() ? 1 : 4;
        int identity = session == null ? 0 : (compact ? 36 : 56) + 6;
        int desiredHeight = header + identity + buttons * accountButtonPitch() + 3
                + accountButtonHeight() + 6 + 24 + 12;
        dialog = new SteampunkDialog(width, height, desiredHeight, new TranslatableComponent("peercraft.gui.account.title"));
        int y = dialog.contentTop();
        if (session == null) {
            loginLicensedButton = addButton(Btn.builder(new TranslatableComponent("peercraft.gui.account.login_licensed"), b -> onLoginLicensed())
                    .bounds(dialog.contentX(), y, dialog.contentWidth(), accountButtonHeight()).primary().build());
            loginLicensedButton.active = !loggingIn;
            y += accountButtonPitch();
            addButton(Btn.builder(new TranslatableComponent("peercraft.gui.account.register"), b -> PeerCraftUi.setScreen(minecraft, new PeerCraftRegisterScreen(this)))
                    .bounds(dialog.contentX(), y, dialog.contentWidth(), accountButtonHeight()).build());
            y += accountButtonPitch();
            addButton(Btn.builder(new TranslatableComponent("peercraft.gui.account.login_by_code"), b -> PeerCraftUi.setScreen(minecraft, new PeerCraftLoginByCodeScreen(this)))
                    .bounds(dialog.contentX(), y, dialog.contentWidth(), accountButtonHeight()).build());
            y += accountButtonPitch();
        } else {
            identityY = y;
            identityHeight = dialog.compact ? 36 : 56;
            int size = dialog.compact ? 14 : 18;
            addButton(PeerCraftUi.squareGlyphButton(dialog.contentX() + dialog.contentWidth() - size - 5, y + identityHeight - size - 5,
                    size, "⧉", new TranslatableComponent("peercraft.gui.account.copy_code_tooltip").getString(), b -> onCopyFriendCode(session.friendCode())));
            y += identityHeight + 6;
            if (!session.licensed()) {
                addButton(Btn.builder(new TranslatableComponent("peercraft.gui.account.copy_recovery_id"), b -> onCopyAccountId(session))
                        .bounds(dialog.contentX(), y, dialog.contentWidth(), accountButtonHeight()).build());
                y += accountButtonPitch();
                addButton(Btn.builder(new TranslatableComponent("peercraft.gui.progress_notice.open"), b -> PeerCraftUi.setScreen(minecraft, new PeerCraftProgressNoticeScreen(this)))
                        .bounds(dialog.contentX(), y, dialog.contentWidth(), accountButtonHeight()).build());
                y += accountButtonPitch();
                addButton(Btn.builder(new TranslatableComponent("peercraft.gui.account.change_nickname"), b -> PeerCraftUi.setScreen(minecraft, new PeerCraftRenameScreen(this)))
                        .bounds(dialog.contentX(), y, dialog.contentWidth(), accountButtonHeight()).build());
                y += accountButtonPitch();
            }
            addButton(Btn.builder(new TranslatableComponent("peercraft.gui.account.logout"), b -> confirmLogout())
                    .bounds(dialog.contentX(), y, dialog.contentWidth(), accountButtonHeight()).build());
            y += accountButtonPitch();
            if (skinTexture == null) {
                skinTexture = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin(minecraft.getUser().getGameProfile().getId());
                minecraft.getSkinManager().registerSkins(minecraft.getUser().getGameProfile(), (type, texture, profileTexture) -> {
                    if (type == com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.SKIN) skinTexture = texture;
                }, false);
            }
        }
        addButton(Btn.builder(new TranslatableComponent("peercraft.gui.common.back"), b -> onClose())
                .bounds(dialog.contentX(), y + 3, dialog.contentWidth(), accountButtonHeight()).build());
        statusY = y + 3 + accountButtonHeight() + 6;
        if (session != null && !session.licensed() && AccountProgressNotice.firstDisplay(session.accountId())) {
            PeerCraftUi.setScreen(minecraft, new PeerCraftProgressNoticeScreen(this));
        }
    }

    private int accountButtonHeight() { return height < 240 ? 16 : dialog.buttonHeight(); }
    private int accountButtonPitch() { return height < 240 ? 18 : dialog.buttonPitch(); }

    private void onCopyFriendCode(String code) {
        Minecraft.getInstance().keyboardHandler.setClipboard(code);
        this.statusMessage = new TranslatableComponent("peercraft.gui.account.code_copied");
        this.statusColor = PeerCraftUi.TEXT_SUCCESS;
    }

    private void onCopyAccountId(AccountClient.AccountSession session) {
        Minecraft.getInstance().keyboardHandler.setClipboard(session.accountId().toString());
        this.statusMessage = new TranslatableComponent("peercraft.gui.account.recovery_id_copied");
        this.statusColor = PeerCraftUi.TEXT_SUCCESS;
    }

    private void confirmLogout() {
        PeerCraftUi.setScreen(this.minecraft, new PeerCraftConfirmScreen(confirmed -> {
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
        renderBackground(poseStack);
        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            int x = dialog.contentX();
            SteampunkDialog.frame(poseStack, x, identityY, dialog.contentWidth(), identityHeight, 0xFF15120F, 0xFF49331F);
            int size = dialog.compact ? 18 : 24;
            int inset = dialog.compact ? 5 : 8;
            if (skinTexture != null) {
                com.mojang.blaze3d.systems.RenderSystem.color4f(1, 1, 1, 1);
                minecraft.getTextureManager().bind(skinTexture);
                GuiComponent.blit(poseStack, x + 7, identityY + inset, size, size, 8, 8, 8, 8, 64, 64);
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                GuiComponent.blit(poseStack, x + 7, identityY + inset, size, size, 40, 8, 8, 8, 64, 64);
            }
            String caption = new TranslatableComponent("peercraft.gui.account.logged_in_as", session.displayName()).getString();
            GuiComponent.drawString(poseStack, font, font.plainSubstrByWidth(caption, dialog.contentWidth() - size - 22), x + size + 14, identityY + inset, PeerCraftUi.TEXT_TITLE);
            GuiComponent.drawString(poseStack, font, new TranslatableComponent(session.licensed() ? "peercraft.gui.account.licensed_status" : "peercraft.gui.account.unlicensed_status"),
                    x + size + 14, identityY + (dialog.compact ? 15 : 23), PeerCraftUi.TEXT_MUTED);
            GuiComponent.drawString(poseStack, font, new TranslatableComponent("peercraft.gui.account.friend_code", session.friendCode()), x + 8, identityY + (dialog.compact ? 27 : 40), PeerCraftUi.TEXT_ACCENT);
        }
        dialog.status(poseStack, font, statusMessage, statusY, Math.max(0, dialog.top + dialog.height - 6 - statusY), statusColor);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }
    @Override public void onClose() { PeerCraftUi.setScreen(minecraft, lastScreen); }
}
