package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.peercraft.client.modsync.ClientModSyncAgent;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;
import org.lwjgl.input.Keyboard;

import java.util.Locale;

/**
 * Forge 1.7.10 backport of {@code src/main/.../PeerCraftJoinScreen.java} (twin of the
 * {@code src/client-1122} backport). {@code ConnectScreen.startConnecting} →
 * {@code new GuiConnecting(parent, mc, host, port)} — the same 4-arg constructor exists on
 * 1.7.10 and parses {@code host:port} itself, so no {@code ServerData} is needed.
 */
public class PeerCraftJoinScreen extends GuiScreen {

    private final GuiScreen lastScreen;

    private GuiTextField roomCodeBox;
    private GuiTextField overrideBox;
    private IdButton connectButton;
    private IdButton overrideToggleButton;
    private boolean overrideVisible = false;
    private String statusMessage = "";

    private final P2PBridge.ConnectListener listener = new P2PBridge.ConnectListener() {
        @Override
        public void onStatus(String message) {
            runOnClientThread(() -> updateStatus(message));
        }

        @Override
        public void onConnected() {
            runOnClientThread(PeerCraftJoinScreen.this::handleConnected);
        }

        @Override
        public void onFailed(String reason) {
            runOnClientThread(() -> handleFailed(reason));
        }
    };

    public PeerCraftJoinScreen(GuiScreen lastScreen) {
        this.lastScreen = lastScreen;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();
        int centerX = this.width / 2;
        int y = this.height / 2 - 70;

        this.roomCodeBox = new GuiTextField(this.fontRendererObj, centerX - 100, y, 200, 20);
        this.roomCodeBox.setMaxStringLength(32);
        String prefillCode = PeerCraftConfig.roomCode();
        if (!prefillCode.trim().isEmpty()) {
            this.roomCodeBox.setText(prefillCode);
        }
        this.roomCodeBox.setFocused(true);

        y += 26;
        this.connectButton = this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.join.connect"), this::onConnect)
                .bounds(centerX - 100, y, 200, 20).build());

        y += 26;
        this.overrideToggleButton = this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.join.override_address_show"), this::toggleOverride)
                .bounds(centerX - 100, y, 200, 20).build());

        y += 26;
        this.overrideBox = new GuiTextField(this.fontRendererObj, centerX - 100, y, 200, 20);
        this.overrideBox.setMaxStringLength(64);
        this.overrideBox.setText(PeerCraftConfig.rendezvousHost() + ":" + PeerCraftConfig.rendezvousPort());
        this.overrideBox.setVisible(this.overrideVisible);

        y += 30;
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.common.back"),
                () -> PeerCraftUi.setScreen(this.mc, this.lastScreen)).bounds(centerX - 100, y, 200, 20).build());
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    private void toggleOverride() {
        this.overrideVisible = !this.overrideVisible;
        this.overrideBox.setVisible(this.overrideVisible);
        this.overrideToggleButton.displayString = PeerCraftLang.tr(
                this.overrideVisible ? "peercraft.gui.join.override_address_hide" : "peercraft.gui.join.override_address_show");
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

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (this.roomCodeBox.textboxKeyTyped(typedChar, keyCode)
                || (this.overrideVisible && this.overrideBox.textboxKeyTyped(typedChar, keyCode))) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.roomCodeBox.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.overrideVisible) {
            this.overrideBox.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    public void updateScreen() {
        this.roomCodeBox.updateCursorCounter();
        this.overrideBox.updateCursorCounter();
    }

    private void onConnect() {
        String code = this.roomCodeBox.getText().trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            this.statusMessage = PeerCraftLang.tr("peercraft.gui.join.enter_code_error");
            return;
        }

        HostPort target = (this.overrideVisible && !this.overrideBox.getText().trim().isEmpty())
                ? parseHostPort(this.overrideBox.getText().trim(), PeerCraftConfig.rendezvousPort())
                : new HostPort(PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort());

        this.connectButton.enabled = false;
        this.statusMessage = PeerCraftLang.tr("peercraft.gui.join.connecting");
        P2PBridge.INSTANCE.startClientViaRendezvous(code, target.host, target.port, this.listener,
                new ClientModSyncAgent(this, code));
    }

    private static final class HostPort {
        private final String host;
        private final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static HostPort parseHostPort(String text, int defaultPort) {
        int idx = text.lastIndexOf(':');
        if (idx > 0 && idx < text.length() - 1) {
            try {
                return new HostPort(text.substring(0, idx), Integer.parseInt(text.substring(idx + 1)));
            } catch (NumberFormatException ignored) {
                // not a number after ':' — treat it as part of the host, not a port separator
            }
        }
        return new HostPort(text, defaultPort);
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
        int port = P2PBridge.INSTANCE.getProxyPort();
        // GuiConnecting parses host:port itself; ServerData is only for the display name / history.
        this.mc.displayGuiScreen(new GuiConnecting(this.lastScreen, this.mc, "127.0.0.1", port));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.roomCodeBox.drawTextBox();
        if (this.overrideVisible) {
            this.overrideBox.drawTextBox();
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.gui.join.title"), this.width / 2, this.height / 2 - 90, 0xFFFFFFFF);
        if (!this.statusMessage.isEmpty()) {
            this.drawCenteredString(this.fontRendererObj, this.statusMessage, this.width / 2, this.height / 2 + 60, 0xFFFFFF55);
        }
    }
}
