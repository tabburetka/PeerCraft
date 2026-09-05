package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.client.modsync.ClientModSyncAgent;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;

import java.util.Locale;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../PeerCraftJoinScreen.java} — the "Join by
 * code" screen. Beyond the shared mechanical swaps: {@code HostPort} is a plain final class
 * (no records at {@code --release 8}); {@code ServerData} takes a {@code boolean isLan} rather
 * than {@code ServerData.Type}; there is no static {@code ConnectScreen.startConnecting} —
 * the connection is kicked off by {@code new ConnectScreen(last, mc, serverData)} which parses
 * the {@code host:port} out of the {@code ServerData.ip} string itself, so no {@code
 * ServerAddress} is needed on this side.
 */
public class PeerCraftJoinScreen extends Screen {

    private final Screen lastScreen;

    private EditBox roomCodeBox;
    private EditBox overrideBox;
    private Button connectButton;
    private Button overrideToggleButton;
    private boolean overrideVisible = false;
    private Component statusMessage = TextComponent.EMPTY;

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
        super(new TranslatableComponent("peercraft.gui.join.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 70;

        this.roomCodeBox = new EditBox(this.font, centerX - 100, y, 200, 20, new TranslatableComponent("peercraft.gui.join.room_code_field"));
        this.roomCodeBox.setMaxLength(32);
        PeerCraftUi.placeholder(this.roomCodeBox, new TranslatableComponent("peercraft.gui.join.room_code_hint").getString());
        String prefillCode = PeerCraftConfig.roomCode();
        if (!prefillCode.trim().isEmpty()) {
            this.roomCodeBox.setValue(prefillCode);
        }
        this.addButton(this.roomCodeBox);
        this.setFocused(this.roomCodeBox);

        y += 26;
        this.connectButton = this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.join.connect"), b -> onConnect())
                .bounds(centerX - 100, y, 200, 20)
                .build());

        y += 26;
        this.overrideToggleButton = this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.join.override_address_show"), b -> toggleOverride())
                .bounds(centerX - 100, y, 200, 20)
                .build());

        y += 26;
        this.overrideBox = new EditBox(this.font, centerX - 100, y, 200, 20, new TranslatableComponent("peercraft.gui.join.override_field"));
        this.overrideBox.setMaxLength(64);
        PeerCraftUi.placeholder(this.overrideBox, "host:port");
        this.overrideBox.setValue(PeerCraftConfig.rendezvousHost() + ":" + PeerCraftConfig.rendezvousPort());
        this.overrideBox.setVisible(this.overrideVisible);
        this.addButton(this.overrideBox);

        y += 30;
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                .bounds(centerX - 100, y, 200, 20)
                .build());
    }

    private void toggleOverride() {
        this.overrideVisible = !this.overrideVisible;
        this.overrideBox.setVisible(this.overrideVisible);
        this.overrideToggleButton.setMessage(new TranslatableComponent(
                this.overrideVisible ? "peercraft.gui.join.override_address_hide" : "peercraft.gui.join.override_address_show"));
    }

    private void onConnect() {
        String code = this.roomCodeBox.getValue().trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            this.statusMessage = new TranslatableComponent("peercraft.gui.join.enter_code_error");
            return;
        }

        HostPort target = (this.overrideVisible && !this.overrideBox.getValue().trim().isEmpty())
                ? parseHostPort(this.overrideBox.getValue().trim(), PeerCraftConfig.rendezvousPort())
                : new HostPort(PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort());

        this.connectButton.active = false;
        this.statusMessage = new TranslatableComponent("peercraft.gui.join.connecting");
        P2PBridge.INSTANCE.startClientViaRendezvous(code, target.host(), target.port(), this.listener,
                new ClientModSyncAgent(this, code));
    }

    private static final class HostPort {
        private final String host;
        private final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        String host() {
            return host;
        }

        int port() {
            return port;
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
        Minecraft.getInstance().execute(action);
    }

    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    private void updateStatus(String message) {
        if (!stillOnThisScreen()) {
            return;
        }
        // P2PBridge/RendezvousClient report progress as peercraft.p2p.* translation keys now,
        // not prose — resolve here (the screen is the layer allowed to touch i18n).
        this.statusMessage = new TranslatableComponent(message);
    }

    private void handleFailed(String reason) {
        if (!stillOnThisScreen()) {
            return;
        }
        this.statusMessage = new TranslatableComponent(reason);
        this.connectButton.active = true;
    }

    private void handleConnected() {
        if (!stillOnThisScreen()) {
            return;
        }
        int port = P2PBridge.INSTANCE.getProxyPort();
        // 1.16.5 ConnectScreen has no startConnecting(...) / ServerAddress-taking constructor —
        // it parses host:port straight out of ServerData.ip and starts the connection from its
        // own constructor, exactly how vanilla's JoinMultiplayerScreen.join(ServerData) does it.
        ServerData serverData = new ServerData("PeerCraft", "127.0.0.1:" + port, false);
        this.minecraft.setScreen(new ConnectScreen(this.lastScreen, this.minecraft, serverData));
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, this.width / 2, this.height / 2 - 90, 0xFFFFFFFF);
        GuiComponent.drawCenteredString(poseStack, this.font, this.statusMessage, this.width / 2, this.height / 2 + 60, 0xFFFFFF55);
    }
}
