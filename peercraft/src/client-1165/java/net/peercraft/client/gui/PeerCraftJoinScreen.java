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
public class PeerCraftJoinScreen extends PeerCraftDialogScreen {

    private final Screen lastScreen;

    private EditBox roomCodeBox;
    private Button connectButton;
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
        super(new TranslatableComponent("peercraft.gui.join.title"), 220);
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        super.init();
        boolean compact = height < 300;
        int desiredHeight = (compact ? 36 : 46) + 6 + 12 + 2 * (compact ? 22 : 30)
                + 40 + 8 + (compact ? 18 : 24) + 12;
        dialog = new SteampunkDialog(width, height, desiredHeight, title);
        String previousCode = this.roomCodeBox == null ? PeerCraftConfig.roomCode() : this.roomCodeBox.getValue();
        int y = dialog.contentTop() + 12;
        this.roomCodeBox = new SteampunkField(this.font, dialog.contentX(), y, dialog.contentWidth(), dialog.buttonHeight(), new TranslatableComponent("peercraft.gui.join.room_code_field"));
        this.roomCodeBox.setMaxLength(32);
        PeerCraftUi.placeholder(this.roomCodeBox, new TranslatableComponent("peercraft.gui.join.room_code_hint").getString());
        this.roomCodeBox.setValue(previousCode);
        this.addButton(this.roomCodeBox);
        this.setFocused(this.roomCodeBox);
        y += dialog.buttonPitch();
        this.connectButton = this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.join.connect"), b -> onConnect())
                .bounds(dialog.contentX(), y, dialog.contentWidth(), dialog.buttonHeight()).primary().build());
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.common.back"), b -> onClose())
                .bounds(dialog.contentX(), dialog.top + dialog.height - dialog.buttonHeight() - 12, dialog.contentWidth(), dialog.buttonHeight()).build());
    }



    private void onConnect() {
        if (PeerCraftProgressNoticeScreen.beforeConnecting(this, this::onConnect)) return;
        String code = this.roomCodeBox.getValue().trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            this.statusMessage = new TranslatableComponent("peercraft.gui.join.enter_code_error");
            return;
        }

        this.connectButton.active = false;
        this.statusMessage = new TranslatableComponent("peercraft.gui.join.connecting");
        P2PBridge.INSTANCE.startClientViaRendezvous(code, PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(), this.listener,
                new ClientModSyncAgent(this, code));
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
        GuiComponent.drawString(poseStack, this.font, new TranslatableComponent("peercraft.gui.join.room_code_field"), dialog.contentX(), dialog.contentTop(), PeerCraftUi.TEXT_MUTED);
        boolean idle = this.statusMessage.getString().isEmpty();
        Component message = idle ? new TranslatableComponent("peercraft.gui.join.code_help") : this.statusMessage;
        dialog.status(poseStack, this.font, message, dialog.contentTop() + 12 + dialog.buttonPitch() * 2, 40,
                idle ? net.peercraft.client.theme.SteampunkPalette.MUTED : PeerCraftUi.TEXT_ACCENT);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    @Override public void onClose() {
        PeerCraftUi.setScreen(this.minecraft, this.lastScreen);
    }
}
