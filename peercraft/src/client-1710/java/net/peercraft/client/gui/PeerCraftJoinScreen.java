package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.peercraft.client.modsync.ClientModSyncAgent;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;
import org.lwjgl.input.Keyboard;

import java.util.Locale;

/**
 * Forge 1.7.10 backport of {@code src/main/.../PeerCraftJoinScreen.java} (twin of the
 * {@code src/client-1122} backport). {@code ConnectScreen.startConnecting} →
 * Uses the FML connection entry point through {@code PeerCraftUi.connectLocal},
 * which initializes the modded handshake state before connecting.
 */
public class PeerCraftJoinScreen extends GuiScreen {

    private final GuiScreen lastScreen;

    private GuiTextField roomCodeBox;
    private SteampunkDialog dialog;
    private final long openedAt = System.currentTimeMillis();
    private IdButton connectButton;
    private String statusMessage = "";

    private P2PBridge.ConnectListener newListener() {
        return new P2PBridge.ConnectListener() {
            private P2PBridge.ClientJoinAttempt attempt;
            @Override public void onStarted(P2PBridge.ClientJoinAttempt started) {
                attempt = started;
                TransportNoticeController.trackJoin(started, PeerCraftJoinScreen.this, lastScreen);
            }
            private void dispatch(Runnable action) {
                runOnClientThread(() -> {
                    if (attempt == null || attempt.isCurrent()) action.run();
                });
            }
            @Override
            public void onStatus(String message) {
                dispatch(() -> updateStatus(message));
            }

            @Override
            public void onConnected() {
                dispatch(PeerCraftJoinScreen.this::handleConnected);
            }

            @Override
            public void onFailed(String reason) {
                dispatch(() -> handleFailed(reason));
            }
        };
    }

    public PeerCraftJoinScreen(GuiScreen lastScreen) {
        this.lastScreen = lastScreen;
    }

    @Override
    public void initGui() {
        String previousCode = this.roomCodeBox == null ? null : this.roomCodeBox.getText();
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();
        boolean compact = height < 300;
        int desiredHeight = (compact ? 36 : 46) + 6 + 14 + 2 * (compact ? 22 : 30)
                + 40 + 8 + (compact ? 18 : 24) + 12;
        this.dialog = new SteampunkDialog(width, height, desiredHeight, PeerCraftLang.tr("peercraft.gui.join.subtitle"));
        int x = dialog.contentX(), w = dialog.contentWidth(), y = dialog.contentTop() + 14;
        this.roomCodeBox = new SteampunkField(this.fontRendererObj, x, y, w, dialog.buttonHeight());
        this.roomCodeBox.setMaxStringLength(32);
        this.roomCodeBox.setText(previousCode == null ? PeerCraftConfig.roomCode() : previousCode);
        this.roomCodeBox.setFocused(true);
        this.connectButton = this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.join.connect"), this::onConnect)
                .primary().bounds(x, y + dialog.buttonPitch(), w, dialog.buttonHeight()).build());
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.common.back"),
                () -> PeerCraftUi.setScreen(this.mc, this.lastScreen))
                .bounds(x, dialog.top + dialog.height - dialog.buttonHeight() - 12, w, dialog.buttonHeight()).build());
    }

    private IdButton addButton(IdButton button) {
        this.buttonList.add(button);
        return button;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) { PeerCraftUi.setScreen(this.mc, this.lastScreen); return; }
        if (keyCode == Keyboard.KEY_RETURN && this.connectButton.enabled) { onConnect(); return; }
        if (this.roomCodeBox.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.roomCodeBox.mouseClicked(mouseX, mouseY, mouseButton);

    }

    @Override
    public void updateScreen() {
        this.roomCodeBox.updateCursorCounter();

    }

    private void onConnect() {
        if (PeerCraftProgressNoticeScreen.beforeConnecting(this, this::onConnect)) return;
        String code = this.roomCodeBox.getText().trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            this.statusMessage = PeerCraftLang.tr("peercraft.gui.join.enter_code_error");
            return;
        }

        this.connectButton.enabled = false;
        this.statusMessage = PeerCraftLang.tr("peercraft.gui.join.connecting");
        P2PBridge.INSTANCE.startClientViaRendezvous(code, PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(), newListener(),
                new ClientModSyncAgent(this, code));
    }

    private void runOnClientThread(Runnable action) {
        Minecraft.getMinecraft().func_152344_a(action);
    }

    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    private void updateStatus(String message) {
        if (stillOnThisScreen()) {
            // P2PBridge/RendezvousClient now emit translation keys (peercraft.p2p.*), not prose —
            // tr() resolves a key, and passes anything else through unchanged.
            this.statusMessage = PeerCraftLang.tr(message);
        }
    }

    void allowRetry(boolean cancelled) {
        if (this.connectButton != null) this.connectButton.enabled = true;
        if (cancelled) this.statusMessage = "";
    }

    private void handleFailed(String reason) {
        if (!stillOnThisScreen()) {
            return;
        }
        this.statusMessage = PeerCraftLang.tr(reason);
        this.connectButton.enabled = true;
    }

    private void handleConnected() {
        if (!stillOnThisScreen()) {
            return;
        }
        TransportNoticeController.connecting();
        int port = P2PBridge.INSTANCE.getProxyPort();
        // Use the FML entry point so modded client handshake state is initialized.
        PeerCraftUi.connectLocal(this.lastScreen, port);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        boolean inWorld = this.mc.theWorld != null;
        if (inWorld) this.drawDefaultBackground();
        dialog.background(this.fontRendererObj, width, height, System.currentTimeMillis() - openedAt, inWorld);
        this.fontRendererObj.drawStringWithShadow(PeerCraftLang.tr("peercraft.gui.join.room_code_field"), dialog.contentX(), dialog.contentTop(), net.peercraft.client.theme.SteampunkPalette.MUTED);
        this.roomCodeBox.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
        boolean idle = statusMessage.isEmpty();
        String message = idle ? PeerCraftLang.tr("peercraft.gui.join.code_help") : statusMessage;
        dialog.status(this.fontRendererObj, message,
                dialog.contentTop() + 14 + dialog.buttonPitch() * 2,
                Math.max(0, dialog.top + dialog.height - dialog.buttonHeight() - 20
                        - (dialog.contentTop() + 14 + dialog.buttonPitch() * 2)),
                idle ? net.peercraft.client.theme.SteampunkPalette.MUTED : net.peercraft.client.theme.SteampunkPalette.ACCENT);
    }
}
