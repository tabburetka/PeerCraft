package net.peercraft.client.gui;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.client.account.AccountProgressNotice;
import net.peercraft.network.account.AccountClient;

import java.io.IOException;

/** Legacy account hub. Skin completion updates only the face texture, never the navigation stack. */
public class PeerCraftAccountScreen extends PeerCraftDialogScreen {

    private net.minecraft.util.ResourceLocation skinTexture;
    private int identityY, identityHeight, statusY;
    private IdButton copyCodeButton;


    private final GuiScreen lastScreen;
    private String statusMessage = "";
    private int statusColor = PeerCraftUi.TEXT_MUTED;
    private IdButton loginLicensedButton;

    public PeerCraftAccountScreen(GuiScreen lastScreen) {
        super(PeerCraftLang.tr("peercraft.gui.account.title"), 340);
        this.lastScreen = lastScreen instanceof PeerCraftAccountScreen ? ((PeerCraftAccountScreen) lastScreen).lastScreen : lastScreen;
    }

    @Override
    public void initGui() {
        boolean loggingIn = loginLicensedButton != null && !loginLicensedButton.enabled;
        super.initGui();
        this.buttonList.clear();
        copyCodeButton = null;
        AccountClient.AccountSession session = AccountSessionHolder.current();
        boolean compact = height < 300;
        int header = (compact ? 36 : 46) + 6;
        int buttons = session == null ? 3 : session.licensed() ? 1 : 4;
        int identity = session == null ? 0 : (compact ? 36 : 56) + 6;
        int desiredHeight = header + identity + buttons * accountButtonPitch() + 3
                + accountButtonHeight() + 6 + 24 + 12;
        dialog = new SteampunkDialog(width, height, desiredHeight, PeerCraftLang.tr("peercraft.gui.account.title"));
        int x = dialog.contentX(), w = dialog.contentWidth(), y = dialog.contentTop();
        if (session == null) {
            this.loginLicensedButton = this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.account.login_licensed"), this::onLoginLicensed)
                    .primary().bounds(x, y, w, accountButtonHeight()).build());
            this.loginLicensedButton.enabled = !loggingIn;
            y += accountButtonPitch();
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.account.register"),
                    () -> PeerCraftUi.setScreen(this.mc, new PeerCraftRegisterScreen(this))).bounds(x, y, w, accountButtonHeight()).build());
            y += accountButtonPitch();
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.account.login_by_code"),
                    () -> PeerCraftUi.setScreen(this.mc, new PeerCraftLoginByCodeScreen(this))).bounds(x, y, w, accountButtonHeight()).build());
            y += accountButtonPitch();
        } else {
            identityY = y;
            identityHeight = dialog.compact ? 36 : 56;
            int size = dialog.compact ? 14 : 18;
            copyCodeButton = this.addButton(PeerCraftUi.squareGlyphButton(x + w - size - 5, y + identityHeight - size - 5,
                    size, "⧉", PeerCraftLang.tr("peercraft.gui.account.copy_code_tooltip"), () -> onCopyFriendCode(session.friendCode())));
            y += identityHeight + 6;
            if (!session.licensed()) {
                this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.account.copy_recovery_id"),
                        () -> onCopyAccountId(session)).bounds(x, y, w, accountButtonHeight()).build());
                y += accountButtonPitch();
                this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.progress_notice.open"),
                        () -> PeerCraftUi.setScreen(this.mc, new PeerCraftProgressNoticeScreen(this)))
                        .bounds(x, y, w, accountButtonHeight()).build());
                y += accountButtonPitch();
                this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.account.change_nickname"),
                        () -> PeerCraftUi.setScreen(this.mc, new PeerCraftRenameScreen(this))).bounds(x, y, w, accountButtonHeight()).build());
                y += accountButtonPitch();
            }
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.account.logout"), this::confirmLogout)
                    .bounds(x, y, w, accountButtonHeight()).build());
            y += accountButtonPitch();
            loadAvatar();
        }
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.common.back"),
                () -> PeerCraftUi.setScreen(this.mc, this.lastScreen)).bounds(x, y + 3, w, accountButtonHeight()).build());
        statusY = y + 3 + accountButtonHeight() + 6;
        if (session != null && !session.licensed() && AccountProgressNotice.firstDisplay(session.accountId())) {
            PeerCraftUi.setScreen(this.mc, new PeerCraftProgressNoticeScreen(this));
        }
    }

    private int accountButtonHeight() { return height < 240 ? 16 : dialog.buttonHeight(); }
    private int accountButtonPitch() { return height < 240 ? 18 : dialog.buttonPitch(); }
    private IdButton addButton(IdButton button) { this.buttonList.add(button); return button; }
    private void loadAvatar() {
        if (skinTexture != null) return;
        skinTexture = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin(mc.getSession().getProfile().getId());
        mc.getSkinManager().loadProfileTextures(mc.getSession().getProfile(), (type, texture, profileTexture) -> {
            if (type == com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.SKIN) runOnClientThread(() -> skinTexture = texture);
        }, false);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    private void onCopyFriendCode(String code) {
        GuiScreen.setClipboardString(code);
        this.statusMessage = PeerCraftLang.tr("peercraft.gui.account.code_copied");
        this.statusColor = PeerCraftUi.TEXT_SUCCESS;
    }

    private void onCopyAccountId(AccountClient.AccountSession session) {
        GuiScreen.setClipboardString(session.accountId().toString());
        this.statusMessage = PeerCraftLang.tr("peercraft.gui.account.recovery_id_copied");
        this.statusColor = PeerCraftUi.TEXT_SUCCESS;
    }

    private void confirmLogout() {
        this.mc.displayGuiScreen(new PeerCraftConfirmScreen(confirmed -> {
            if (confirmed) {
                AccountSessionHolder.logout();
                this.mc.displayGuiScreen(new PeerCraftAccountScreen(this.lastScreen));
            } else this.mc.displayGuiScreen(this);
        }, PeerCraftLang.tr("peercraft.gui.account.logout_confirm_title"),
                PeerCraftLang.tr("peercraft.gui.account.logout_confirm_message")));
    }

    private void onLoginLicensed() {
        Minecraft mc = Minecraft.getMinecraft();
        this.loginLicensedButton.enabled = false;
        this.statusMessage = PeerCraftLang.tr("peercraft.gui.account.confirming_license");
        this.statusColor = PeerCraftUi.TEXT_MUTED;
        MinecraftSessionService sessionService = mc.getSessionService();
        AccountClient.INSTANCE.loginLicensed(mc.getSession().getProfile(), mc.getSession().getToken(), sessionService, new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                runOnClientThread(() -> {
                    AccountSessionHolder.persist(session);
                    if (stillOnThisScreen()) {
                        PeerCraftUi.setScreen(mc, new PeerCraftAccountScreen(lastScreen));
                    }
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    statusMessage = reason;
                    statusColor = PeerCraftUi.TEXT_ERROR;
                    loginLicensedButton.enabled = true;
                });
            }
        });
    }

    private void runOnClientThread(Runnable action) {
        Minecraft.getMinecraft().addScheduledTask(action);
    }

    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            int x = dialog.contentX();
            SteampunkDialog.frame(x, identityY, dialog.contentWidth(), identityHeight,
                    net.peercraft.client.theme.SteampunkPalette.CONTROL, net.peercraft.client.theme.SteampunkPalette.BORDER);
            int size = dialog.compact ? 18 : 24, inset = dialog.compact ? 5 : 8;
            if (skinTexture != null) {
                org.lwjgl.opengl.GL11.glColor4f(1, 1, 1, 1);
                org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
                org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
                mc.getTextureManager().bindTexture(skinTexture);
                net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(x + 7, identityY + inset, 8, 8, 8, 8, size, size, 64, 64);
                net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(x + 7, identityY + inset, 40, 8, 8, 8, size, size, 64, 64);
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_BLEND);
            }
            String name = PeerCraftLang.tr("peercraft.gui.account.logged_in_as", session.displayName());
            this.fontRenderer.drawStringWithShadow(this.fontRenderer.trimStringToWidth(name, Math.max(1, dialog.contentWidth() - size - 22)),
                    x + size + 14, identityY + inset, PeerCraftUi.TEXT_TITLE);
            String kind = PeerCraftLang.tr(session.licensed() ? "peercraft.gui.account.licensed_status" : "peercraft.gui.account.unlicensed_status");
            this.fontRenderer.drawStringWithShadow(this.fontRenderer.trimStringToWidth(kind, Math.max(1, dialog.contentWidth() - size - 22)),
                    x + size + 14, identityY + (dialog.compact ? 15 : 23), PeerCraftUi.TEXT_MUTED);
            String code = PeerCraftLang.tr("peercraft.gui.account.friend_code", session.friendCode());
            this.fontRenderer.drawStringWithShadow(this.fontRenderer.trimStringToWidth(code, Math.max(1, dialog.contentWidth() - (dialog.compact ? 32 : 36))),
                    x + 8, identityY + (dialog.compact ? 27 : 40), PeerCraftUi.TEXT_ACCENT);
        }
        dialog.status(this.fontRenderer, statusMessage, statusY, Math.max(0, dialog.top + dialog.height - 6 - statusY), statusColor);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
    @Override protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) { PeerCraftUi.setScreen(mc, lastScreen); return; }
        super.keyTyped(typedChar, keyCode);
    }
}
