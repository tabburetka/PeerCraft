package net.peercraft.client.gui;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.gui.GuiYesNoCallback;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.network.account.AccountClient;


/**
 * Forge 1.7.10 backport of {@code src/main/.../PeerCraftAccountScreen.java} (twin of the
 * {@code src/client-1122} backport). Same shape as the 1.12.2 twin: {@code ConfirmScreen} →
 * {@code GuiYesNo} + this screen implementing {@link GuiYesNoCallback};
 * {@code keyboardHandler.setClipboard} → {@code GuiScreen.setClipboardString} (static, present
 * since 1.7.2).
 *
 * <p>1.7.10 stable_12 names (confirmed against the RFG-decompiled source): the session-service
 * getter is {@code Minecraft.func_152347_ac()} and {@code Session.getProfile()} is
 * {@code func_148256_e()} — neither carries a readable mapping. {@code Session.getToken()} and
 * {@code GuiScreen.setClipboardString} do.
 */
public class PeerCraftAccountScreen extends GuiScreen implements GuiYesNoCallback {

    private static final int DIALOG_LOGOUT = 1;

    private final GuiScreen lastScreen;
    private String statusMessage = "";
    private int statusColor = PeerCraftUi.TEXT_MUTED;
    private IdButton loginLicensedButton;

    public PeerCraftAccountScreen(GuiScreen lastScreen) {
        this.lastScreen = lastScreen;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int centerX = this.width / 2;

        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            String codeLine = PeerCraftLang.tr("peercraft.gui.account.friend_code", session.friendCode());
            int codeY = (this.height / 2 - 90) + 30;
            int copySize = 14;
            this.addButton(PeerCraftUi.squareGlyphButton(
                    centerX + this.fontRendererObj.getStringWidth(codeLine) / 2 + 6, codeY - 3, copySize,
                    "⧉", PeerCraftLang.tr("peercraft.gui.account.copy_code_tooltip"),
                    () -> onCopyFriendCode(session.friendCode())));
        }

        int y = session == null ? this.height / 2 - 70 : this.height / 2 - 40;
        if (session == null) {
            this.loginLicensedButton = this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.account.login_licensed"), this::onLoginLicensed)
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.account.register"),
                    () -> PeerCraftUi.setScreen(this.mc, new PeerCraftRegisterScreen(this))).bounds(centerX - 100, y, 200, 20).build());
            y += 26;
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.account.login_by_code"),
                    () -> PeerCraftUi.setScreen(this.mc, new PeerCraftLoginByCodeScreen(this))).bounds(centerX - 100, y, 200, 20).build());
            y += 26;
        } else {
            if (!session.licensed()) {
                this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.account.change_nickname"),
                        () -> PeerCraftUi.setScreen(this.mc, new PeerCraftRenameScreen(this))).bounds(centerX - 100, y, 200, 20).build());
                y += 26;
            }
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.account.logout"), this::confirmLogout)
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
        }

        y += 4;
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.common.back"),
                () -> PeerCraftUi.setScreen(this.mc, this.lastScreen)).bounds(centerX - 100, y, 200, 20).build());
    }

    @SuppressWarnings("unchecked")
    private <T extends GuiButton> T addButton(T button) {
        this.buttonList.add(button);
        return button;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    private void onCopyFriendCode(String code) {
        GuiScreen.setClipboardString(code);
        this.statusMessage = PeerCraftLang.tr("peercraft.gui.account.code_copied");
        this.statusColor = PeerCraftUi.TEXT_SUCCESS;
    }

    private void confirmLogout() {
        this.mc.displayGuiScreen(new GuiYesNo(this,
                PeerCraftLang.tr("peercraft.gui.account.logout_confirm_title"),
                PeerCraftLang.tr("peercraft.gui.account.logout_confirm_message"),
                DIALOG_LOGOUT));
    }

    @Override
    public void confirmClicked(boolean result, int id) {
        if (id == DIALOG_LOGOUT) {
            if (result) {
                AccountSessionHolder.logout();
                this.mc.displayGuiScreen(new PeerCraftAccountScreen(this.lastScreen));
            } else {
                this.mc.displayGuiScreen(this);
            }
        }
    }

    private void onLoginLicensed() {
        Minecraft mc = Minecraft.getMinecraft();
        this.loginLicensedButton.enabled = false;
        this.statusMessage = PeerCraftLang.tr("peercraft.gui.account.confirming_license");
        this.statusColor = PeerCraftUi.TEXT_MUTED;
        MinecraftSessionService sessionService = mc.func_152347_ac();
        AccountClient.INSTANCE.loginLicensed(mc.getSession().func_148256_e(), mc.getSession().getToken(), sessionService, new AccountClient.AuthCallback() {
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
        Minecraft.getMinecraft().func_152344_a(action);
    }

    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        int centerX = this.width / 2;
        int titleY = this.height / 2 - 90;
        this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.gui.account.title"), centerX, titleY, PeerCraftUi.TEXT_TITLE);

        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            String name = PeerCraftLang.tr("peercraft.gui.account.logged_in_as", session.displayName());
            PeerCraftUi.drawNameWithBadgeCentered(this.fontRendererObj, name, session.licensed(), centerX, titleY + 16, PeerCraftUi.TEXT_TITLE);
            this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.gui.account.friend_code", session.friendCode()), centerX, titleY + 30, PeerCraftUi.TEXT_ACCENT);
        }

        if (!this.statusMessage.isEmpty()) {
            this.drawCenteredString(this.fontRendererObj, this.statusMessage, centerX, this.height / 2 + 70, this.statusColor);
        }
    }
}
