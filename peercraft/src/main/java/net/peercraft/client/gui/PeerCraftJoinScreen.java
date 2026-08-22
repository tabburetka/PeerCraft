package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;

import java.util.Locale;

// The "Join by code" screen — opened via a button from the title screen
// (TitleScreenMixin). The room code and (optionally) the rendezvous server address are
// set right here and applied the moment "Connect" is clicked, not at game startup —
// this is exactly what fixes the bug where "the address only updates on next launch,
// not on button click".
public class PeerCraftJoinScreen extends Screen {

    private final Screen lastScreen;

    private EditBox roomCodeBox;
    private EditBox overrideBox;
    private Button connectButton;
    private Button overrideToggleButton;
    private boolean overrideVisible = false;
    private Component statusMessage = Component.empty();

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

    public PeerCraftJoinScreen(Screen lastScreen) {
        super(Component.translatable("peercraft.gui.join.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 70;

        this.roomCodeBox = new EditBox(this.font, centerX - 100, y, 200, 20, Component.translatable("peercraft.gui.join.room_code_field"));
        this.roomCodeBox.setMaxLength(32);
        this.roomCodeBox.setHint(Component.translatable("peercraft.gui.join.room_code_hint"));
        String prefillCode = PeerCraftConfig.roomCode();
        if (!prefillCode.isBlank()) {
            this.roomCodeBox.setValue(prefillCode);
        }
        this.addRenderableWidget(this.roomCodeBox);
        this.setInitialFocus(this.roomCodeBox);

        y += 26;
        this.connectButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.join.connect"), b -> onConnect())
                .bounds(centerX - 100, y, 200, 20)
                .build());

        y += 26;
        this.overrideToggleButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.join.override_address_show"), b -> toggleOverride())
                .bounds(centerX - 100, y, 200, 20)
                .build());

        y += 26;
        this.overrideBox = new EditBox(this.font, centerX - 100, y, 200, 20, Component.translatable("peercraft.gui.join.override_field"));
        this.overrideBox.setMaxLength(64);
        this.overrideBox.setHint(Component.literal("host:port"));
        this.overrideBox.setValue(PeerCraftConfig.rendezvousHost() + ":" + PeerCraftConfig.rendezvousPort());
        this.overrideBox.setVisible(this.overrideVisible);
        this.addRenderableWidget(this.overrideBox);

        y += 30;
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.common.back"), b -> this.minecraft.setScreen(this.lastScreen))
                .bounds(centerX - 100, y, 200, 20)
                .build());
    }

    private void toggleOverride() {
        this.overrideVisible = !this.overrideVisible;
        this.overrideBox.setVisible(this.overrideVisible);
        this.overrideToggleButton.setMessage(Component.translatable(
                this.overrideVisible ? "peercraft.gui.join.override_address_hide" : "peercraft.gui.join.override_address_show"));
    }

    private void onConnect() {
        String code = this.roomCodeBox.getValue().trim().toUpperCase(Locale.ROOT);
        if (code.isBlank()) {
            this.statusMessage = Component.translatable("peercraft.gui.join.enter_code_error");
            return;
        }

        HostPort target = (this.overrideVisible && !this.overrideBox.getValue().isBlank())
                ? parseHostPort(this.overrideBox.getValue().trim(), PeerCraftConfig.rendezvousPort())
                : new HostPort(PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort());

        this.connectButton.active = false;
        this.statusMessage = Component.translatable("peercraft.gui.join.connecting");
        P2PBridge.INSTANCE.startClientViaRendezvous(code, target.host(), target.port(), this.listener);
    }

    private record HostPort(String host, int port) {
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
        Minecraft.getInstance().execute(action);
    }

    // The player may have already left this screen (Back/Esc) before the rendezvous
    // server's/hole-punching async callback fired — in that case, just ignore the result.
    private boolean stillOnThisScreen() {
        return Minecraft.getInstance().screen == this;
    }

    private void updateStatus(String message) {
        if (!stillOnThisScreen()) {
            return;
        }
        this.statusMessage = Component.literal(message);
    }

    private void handleFailed(String reason) {
        if (!stillOnThisScreen()) {
            return;
        }
        this.statusMessage = Component.literal(reason);
        this.connectButton.active = true;
    }

    private void handleConnected() {
        if (!stillOnThisScreen()) {
            return;
        }
        int port = P2PBridge.INSTANCE.getProxyPort();
        ServerAddress address = new ServerAddress("127.0.0.1", port);
        ServerData serverData = new ServerData("PeerCraft", "127.0.0.1:" + port, ServerData.Type.OTHER);
        // transferState must be null for a normal join — a non-null value (even an empty
        // one) makes the client treat this as a resumed "server transfer" instead of a
        // fresh connection, which our target never agrees to and fails with "Server does
        // not accept transfers". Confirmed against JoinMultiplayerScreen's own call site
        // (aconst_null immediately precedes its startConnecting invocation).
        ConnectScreen.startConnecting(this.lastScreen, this.minecraft, address, serverData, false, null);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 90, 0xFFFFFF);
        graphics.drawCenteredString(this.font, this.statusMessage, this.width / 2, this.height / 2 + 60, 0xFFFF55);
    }
}
