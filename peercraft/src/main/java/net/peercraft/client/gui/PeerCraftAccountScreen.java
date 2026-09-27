package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.network.account.AccountClient;
//? if =1.21.1 {
import java.util.ArrayList;
import java.util.List;
//?}

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
    private boolean returningToLastScreen;
    //? if =1.21.1 {
    private final long animationStart = System.nanoTime();
    private SteampunkDialog dialog;
    private int identityY;
    private int identityHeight;
    private int statusY;
    private int footerDividerY;
    //?}

    public PeerCraftAccountScreen(Screen lastScreen) {
        super(Component.translatable("peercraft.gui.account.title"));
        this.lastScreen = lastScreen;
        this.statusMessage = Component.empty();
    }

    @Override
    protected void init() {
        //? if =1.21.1
        boolean wasLoggingIn = this.loginLicensedButton != null && !this.loginLicensedButton.active;
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
            //? if =1.21.1
            this.loginLicensedButton.active = !wasLoggingIn;
            y += 26;
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.account.register"), b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftRegisterScreen(this)))
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.account.login_by_code"), b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftLoginByCodeScreen(this)))
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
        } else {
            if (!session.licensed()) {
                this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.account.change_nickname"), b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftRenameScreen(this)))
                        .bounds(centerX - 100, y, 200, 20).build());
                y += 26;
            }
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.account.logout"), b -> confirmLogout())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
        }

        y += 4;
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.common.back"), b -> returnToLastScreen())
                .bounds(centerX - 100, y, 200, 20).build());
        //? if =1.21.1
        layoutThemedAccount(session);
    }

    @Override
    public void onClose() {
        returnToLastScreen();
    }

    /** A stale async callback must never create another account screen after the user leaves. */
    private void returnToLastScreen() {
        if (this.returningToLastScreen) {
            return;
        }
        this.returningToLastScreen = true;
        PeerCraftUi.setScreen(this.minecraft, this.lastScreen);
    }

    private void onCopyFriendCode(String code) {
        Minecraft.getInstance().keyboardHandler.setClipboard(code);
        this.statusMessage = Component.translatable("peercraft.gui.account.code_copied");
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
        }, Component.translatable("peercraft.gui.account.logout_confirm_title"), Component.translatable("peercraft.gui.account.logout_confirm_message")));
    }

    private void onLoginLicensed() {
        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        // User.Type/getType() (offline-mode detection) was removed in 1.21.9 — Mojang fully
        // retired legacy accounts, so this branch can no longer trigger from that point on.
        //? if <1.21.9 {
        if (user.getType() == User.Type.LEGACY) {
            this.statusMessage = Component.translatable("peercraft.gui.account.offline_mode_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        //?}

        this.loginLicensedButton.active = false;
        this.statusMessage = Component.translatable("peercraft.gui.account.confirming_license");
        this.statusColor = PeerCraftUi.TEXT_MUTED;
        // Minecraft.getMinecraftSessionService() was replaced by services().sessionService() in 1.21.9.
        //? if <1.21.9 {
        com.mojang.authlib.minecraft.MinecraftSessionService sessionService = mc.getMinecraftSessionService();
        //?} else {
        /*com.mojang.authlib.minecraft.MinecraftSessionService sessionService = mc.services().sessionService();*/
        //?}
        AccountClient.INSTANCE.loginLicensed(mc.getGameProfile(), user.getAccessToken(), sessionService, new AccountClient.AuthCallback() {
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
        return PeerCraftUi.isCurrentScreen(this);
    }

    //? if =1.21.1 {
    private void layoutThemedAccount(AccountClient.AccountSession session) {
        List<Button> actions = new ArrayList<>();
        Button copyCode = null;
        for (var child : new ArrayList<>(children())) {
            if (child instanceof Button button) {
                if (button.getWidth() == 14 && button.getHeight() == 14) {
                    copyCode = button;
                } else {
                    actions.add(button);
                }
            }
        }
        boolean compact = this.height < 300;
        int header = compact ? 36 : 46;
        int pitch = compact ? 22 : 30;
        int statusHeight = compact ? 18 : 27;
        this.identityHeight = session == null ? 0 : compact ? 40 : 56;
        int desiredHeight = header + this.identityHeight + 6 + actions.size() * pitch
                + (compact ? 6 : 8) + statusHeight + (compact ? 8 : 10);
        this.dialog = new SteampunkDialog(this.width, this.height, desiredHeight,
                Component.translatable("peercraft.gui.account.subtitle"));
        this.identityY = this.dialog.contentTop();
        int y = this.identityY + (session == null ? 0 : this.identityHeight + 6);
        for (int i = 0; i < actions.size(); i++) {
            Button original = actions.get(i);
            if (i == actions.size() - 1) {
                this.footerDividerY = y - 2;
                y += compact ? 4 : 6;
            }
            removeWidget(original);
            this.addRenderableWidget(SteampunkSettingsTheme.decorate(original, this.dialog.contentX(), y,
                    this.dialog.contentWidth(), this.dialog.buttonHeight(), original == this.loginLicensedButton && session == null));
            y += pitch;
        }
        if (copyCode != null) {
            removeWidget(copyCode);
            int size = compact ? 16 : 18;
            int codeY = this.identityY + (compact ? 26 : 38);
            this.addRenderableWidget(SteampunkSettingsTheme.decorate(copyCode,
                    this.dialog.contentX() + this.dialog.contentWidth() - size - 5, codeY - 4, size, size, false));
        }
        this.statusY = y + 2;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.dialog.background(graphics, this.font, this.width, this.height,
                (System.nanoTime() - this.animationStart) / 1_000_000L);
        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            drawIdentity(graphics, session);
        }
        this.dialog.divider(graphics, this.footerDividerY);
    }

    private void drawIdentity(GuiGraphics graphics, AccountClient.AccountSession session) {
        int x = this.dialog.contentX();
        SteampunkSettingsTheme.frame(graphics, x, this.identityY, this.dialog.contentWidth(), this.identityHeight,
                0xFF15120F, 0xFF49331F);
        int iconSize = this.dialog.compact ? 18 : 24;
        int insetY = this.dialog.compact ? 5 : 8;
        SteampunkSettingsTheme.frame(graphics, x + 7, this.identityY + insetY, iconSize, iconSize,
                0xFF2C241B, SteampunkSettingsTheme.BORDER);
        drawSkinFace(graphics, x + 7, this.identityY + insetY, iconSize);
        String name = session.displayName();
        int textX = x + iconSize + 14;
        Component caption = Component.translatable("peercraft.gui.account.logged_in_as", name);
        String display = this.font.plainSubstrByWidth(caption.getString(), this.dialog.contentWidth() - iconSize - 22);
        graphics.drawString(this.font, display, textX, this.identityY + insetY, SteampunkSettingsTheme.TEXT, false);
        graphics.drawString(this.font, Component.translatable(session.licensed()
                        ? "peercraft.gui.account.licensed_status" : "peercraft.gui.account.unlicensed_status"),
                textX, this.identityY + (this.dialog.compact ? 15 : 23), SteampunkSettingsTheme.MUTED, false);
        graphics.drawString(this.font, Component.translatable("peercraft.gui.account.friend_code", session.friendCode()),
                x + 8, this.identityY + (this.dialog.compact ? 26 : 38), SteampunkSettingsTheme.ACCENT, false);
    }

    /** Draw the local Minecraft skin's face and hat layer; SkinManager provides a default while it loads. */
    private void drawSkinFace(GuiGraphics graphics, int x, int y, int size) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.resources.ResourceLocation texture = mc.getSkinManager().getInsecureSkin(mc.getGameProfile()).texture();
        graphics.blit(texture, x, y, 0, 8, 8, size, size, 64, 64);
        graphics.blit(texture, x, y, 0, 40, 8, size, size, 64, 64);
    }
    //?}

    // 26.1 renamed GuiGraphics -> GuiGraphicsExtractor and replaced Screen#render with
    // #extractRenderState; drawString/drawCenteredString became text/centeredText.
    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if !=1.21.1 && <1.21.6
        /*this.renderBackground(graphics, mouseX, mouseY, partialTick);*/
        super.render(graphics, mouseX, mouseY, partialTick);
        //? if =1.21.1 {
        int color = this.statusColor == PeerCraftUi.TEXT_SUCCESS ? SteampunkSettingsTheme.ACCENT
                : this.statusColor == PeerCraftUi.TEXT_MUTED ? SteampunkSettingsTheme.MUTED : this.statusColor;
        this.dialog.status(graphics, this.font, this.statusMessage, this.statusY,
                this.dialog.top + this.dialog.height - this.statusY - 6, color, mouseX, mouseY);
        //?} else {
        /*int centerX = this.width / 2;
        int titleY = this.height / 2 - 90;
        graphics.drawCenteredString(this.font, this.title, centerX, titleY, PeerCraftUi.TEXT_TITLE);

        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            String name = Component.translatable("peercraft.gui.account.logged_in_as", session.displayName()).getString();
            PeerCraftUi.drawNameWithBadgeCentered(graphics, this.font, name, session.licensed(), centerX, titleY + 16, PeerCraftUi.TEXT_TITLE);
            graphics.drawCenteredString(this.font, Component.translatable("peercraft.gui.account.friend_code", session.friendCode()), centerX, titleY + 30, PeerCraftUi.TEXT_ACCENT);
        }

        graphics.drawCenteredString(this.font, this.statusMessage, centerX, this.height / 2 + 70, this.statusColor);*/
        //?}
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int titleY = this.height / 2 - 90;
        graphics.centeredText(this.font, this.title, centerX, titleY, PeerCraftUi.TEXT_TITLE);

        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            String name = Component.translatable("peercraft.gui.account.logged_in_as", session.displayName()).getString();
            PeerCraftUi.drawNameWithBadgeCentered(graphics, this.font, name, session.licensed(), centerX, titleY + 16, PeerCraftUi.TEXT_TITLE);
            graphics.centeredText(this.font, Component.translatable("peercraft.gui.account.friend_code", session.friendCode()), centerX, titleY + 30, PeerCraftUi.TEXT_ACCENT);
        }

        graphics.centeredText(this.font, this.statusMessage, centerX, this.height / 2 + 70, this.statusColor);
    }*/
    //?}
}
