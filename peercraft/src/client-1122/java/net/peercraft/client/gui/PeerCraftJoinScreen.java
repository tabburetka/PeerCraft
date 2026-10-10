package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.resources.I18n;
import net.peercraft.client.modsync.ClientModSyncAgent;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.Locale;

/**
 * Forge 1.12.2 backport of {@code src/main/.../PeerCraftJoinScreen.java} (cf. 1.16.5 twin).
 * 1.12.2: {@code ConnectScreen}/{@code startConnecting} → {@code GuiConnecting(parent, mc,
 * host, port)}; {@code ServerData(name, ip, boolean)} unchanged in shape.
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
        this.roomCodeBox = new SteampunkField(0, this.fontRenderer, x, y, w, dialog.buttonHeight());
        this.roomCodeBox.setMaxStringLength(32);
        this.roomCodeBox.setText(previousCode == null ? PeerCraftConfig.roomCode() : previousCode);
        this.roomCodeBox.setFocused(true);
        this.connectButton = this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.join.connect"), this::onConnect)
                .primary().bounds(x, y + dialog.buttonPitch(), w, dialog.buttonHeight()).build());
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.common.back"),
                () -> PeerCraftUi.setScreen(this.mc, this.lastScreen))
                .bounds(x, dialog.top + dialog.height - dialog.buttonHeight() - 12, w, dialog.buttonHeight()).build());
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) { PeerCraftUi.setScreen(this.mc, this.lastScreen); return; }
        if (keyCode == Keyboard.KEY_RETURN && this.connectButton.enabled) { onConnect(); return; }
        if (this.roomCodeBox.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
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
        Minecraft.getMinecraft().addScheduledTask(action);
    }

    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    private void updateStatus(String message) {
        if (stillOnThisScreen()) {
            // P2PBridge/RendezvousClient now emit peercraft.p2p.* translation keys, not prose —
            // tr() resolves a key and passes anything else through unchanged.
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
        // GuiConnecting parses host:port itself; ServerData is only for the display name / history.
        this.mc.displayGuiScreen(new GuiConnecting(this.lastScreen, this.mc, "127.0.0.1", port));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        boolean inWorld = this.mc.world != null;
        if (inWorld) this.drawDefaultBackground();
        dialog.background(this.fontRenderer, width, height, System.currentTimeMillis() - openedAt, inWorld);
        this.fontRenderer.drawStringWithShadow(PeerCraftLang.tr("peercraft.gui.join.room_code_field"), dialog.contentX(), dialog.contentTop(), net.peercraft.client.theme.SteampunkPalette.MUTED);
        this.roomCodeBox.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
        boolean idle = statusMessage.isEmpty();
        String message = idle ? PeerCraftLang.tr("peercraft.gui.join.code_help") : statusMessage;
        dialog.status(this.fontRenderer, message,
                dialog.contentTop() + 14 + dialog.buttonPitch() * 2,
                Math.max(0, dialog.top + dialog.height - dialog.buttonHeight() - 20
                        - (dialog.contentTop() + 14 + dialog.buttonPitch() * 2)),
                idle ? net.peercraft.client.theme.SteampunkPalette.MUTED : net.peercraft.client.theme.SteampunkPalette.ACCENT);
    }
}
