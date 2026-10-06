package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.peercraft.client.modsync.ClientModSyncAgent;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;

import java.util.Locale;

// The "Join by code" screen — opened via a button from the title screen
// (TitleScreenMixin). The rendezvous address comes from the mod settings; this dialog
// only asks for a room code.
public class PeerCraftJoinScreen extends Screen {

    private final Screen lastScreen;

    private EditBox roomCodeBox;
    private Button connectButton;
    private Button backButton;
    private Component statusMessage = Component.empty();
    //? if >=1.21.1 {
    private final long animationStart = System.nanoTime();
    private SteampunkDialog dialog;
    private int codeLabelY;
    private int statusY;
    private int dividerY;
    //?}

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
        String previousCode = this.roomCodeBox == null ? null : this.roomCodeBox.getValue();
        //? if >=1.21.1
        boolean wasConnecting = this.connectButton != null && !this.connectButton.active;
        int centerX = this.width / 2;
        int y = this.height / 2 - 70;

        //? if >=1.21.1
        this.roomCodeBox = new SteampunkSettingsTheme.Field(this.font, centerX - 100, y, 200, 20, Component.translatable("peercraft.gui.join.room_code_field"));
        this.roomCodeBox.setMaxLength(32);
        this.roomCodeBox.setHint(Component.translatable("peercraft.gui.join.room_code_hint"));
        String prefillCode = PeerCraftConfig.roomCode().isEmpty() ? P2PBridge.INSTANCE.lastJoinedRoomCode() : PeerCraftConfig.roomCode();
        if (!prefillCode.isBlank()) {
            this.roomCodeBox.setValue(prefillCode);
        }
        this.addRenderableWidget(this.roomCodeBox);
        this.setInitialFocus(this.roomCodeBox);

        y += 26;
        //? if >=1.21.1 {
        this.connectButton = this.addRenderableWidget(SteampunkSettingsTheme.action(centerX - 100, y, 200, 20,
                Component.translatable("peercraft.gui.join.connect"), b -> onConnect(), true));
        //?} else {
        /*this.connectButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.join.connect"), b -> onConnect())
                .bounds(centerX - 100, y, 200, 20)
                .build());*/
        //?}

        y += 30;
        //? if >=1.21.1 {
        this.backButton = this.addRenderableWidget(SteampunkSettingsTheme.action(centerX - 100, y, 200, 20,
                Component.translatable("peercraft.gui.common.back"), b -> onClose(), false));
        //?} else {
        /*this.backButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.common.back"), b -> onClose())
                .bounds(centerX - 100, y, 200, 20)
                .build());*/
        //?}
        if (previousCode != null) this.roomCodeBox.setValue(previousCode);
        //? if >=1.21.1 {
        this.connectButton.active = !wasConnecting;
        layoutThemedJoin();
        //?}
    }

    @Override
    public void onClose() {
        PeerCraftUi.setScreen(this.minecraft, this.lastScreen);
    }

    private void onConnect() {
        if (PeerCraftProgressNoticeScreen.beforeConnecting(this, this::onConnect)) return;
        String code = this.roomCodeBox.getValue().trim().toUpperCase(Locale.ROOT);
        if (code.isBlank()) {
            this.statusMessage = Component.translatable("peercraft.gui.join.enter_code_error");
            return;
        }

        this.connectButton.active = false;
        this.statusMessage = Component.translatable("peercraft.gui.join.connecting");
        P2PBridge.INSTANCE.startClientViaRendezvous(code, PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(), this.listener,
                new ClientModSyncAgent(this, code));
    }

    private void runOnClientThread(Runnable action) {
        Minecraft.getInstance().execute(action);
    }

    // The player may have already left this screen (Back/Esc) before the rendezvous
    // server's/hole-punching async callback fired — in that case, just ignore the result.
    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    private void updateStatus(String message) {
        if (!stillOnThisScreen()) {
            return;
        }
        // P2PBridge/RendezvousClient report progress as peercraft.p2p.* translation keys now,
        // not prose — resolve here (the screen is the layer allowed to touch i18n).
        this.statusMessage = Component.translatable(message);
    }

    private void handleFailed(String reason) {
        if (!stillOnThisScreen()) {
            return;
        }
        this.statusMessage = Component.translatable(reason);
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

    //? if >=1.21.1 {
    private void layoutThemedJoin() {
        boolean compact = this.height < 300;
        int header = compact ? 36 : 46;
        int fieldHeight = compact ? 20 : 24;
        int buttonHeight = compact ? 18 : 24;
        int statusHeight = 27;
        int desiredHeight = header + 6 + 12 + fieldHeight + 8 + buttonHeight + 8
                + statusHeight + 8 + buttonHeight + 10;
        this.dialog = new SteampunkDialog(this.width, this.height, desiredHeight,
                Component.translatable("peercraft.gui.join.subtitle"));
        this.codeLabelY = this.dialog.contentTop();
        this.roomCodeBox.setX(this.dialog.contentX());
        this.roomCodeBox.setY(this.codeLabelY + 12);
        this.roomCodeBox.setWidth(this.dialog.contentWidth());
        this.roomCodeBox.setHeight(fieldHeight);
        this.connectButton.setX(this.dialog.contentX());
        this.connectButton.setY(this.roomCodeBox.getY() + fieldHeight + 8);
        this.connectButton.setWidth(this.dialog.contentWidth());
        this.connectButton.setHeight(buttonHeight);
        this.statusY = this.connectButton.getY() + buttonHeight + 8;
        this.dividerY = this.statusY + statusHeight + 2;
        this.backButton.setX(this.dialog.contentX());
        this.backButton.setY(this.dividerY + 6);
        this.backButton.setWidth(this.dialog.contentWidth());
        this.backButton.setHeight(buttonHeight);
    }

    //? if <26.1 {
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.dialog.background(graphics, this.font, this.width, this.height,
                (System.nanoTime() - this.animationStart) / 1_000_000L);
        graphics.drawString(this.font, Component.translatable("peercraft.gui.join.room_code_field"),
                this.dialog.contentX(), this.codeLabelY, SteampunkSettingsTheme.TEXT, false);
        this.dialog.divider(graphics, this.dividerY);
    }
    //?} else {
    /* */
    //?}

    //? if <1.21.9 {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)
                && getFocused() == this.roomCodeBox && this.connectButton.active) {
            onConnect();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    //?} else {
    /*    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
        if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)
                && getFocused() == this.roomCodeBox && this.connectButton.active) {
            onConnect();
            return true;
        }
        return super.keyPressed(event);
    }*/
    //?}
    //?}

    // 26.1 renamed GuiGraphics -> GuiGraphicsExtractor and replaced Screen#render with
    // #extractRenderState; drawString/drawCenteredString became text/centeredText.
    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.1 && <1.21.6
        /*this.renderBackground(graphics, mouseX, mouseY, partialTick);*/
        super.render(graphics, mouseX, mouseY, partialTick);
        //? if >=1.21.1 {
        boolean idle = this.statusMessage.getString().isEmpty();
        Component message = idle ? Component.translatable("peercraft.gui.join.code_help") : this.statusMessage;
        int color = idle ? SteampunkSettingsTheme.MUTED
                : this.connectButton.active ? PeerCraftUi.TEXT_ERROR : SteampunkSettingsTheme.ACCENT;
        this.dialog.status(graphics, this.font, message, this.statusY, 27, color, mouseX, mouseY);
        //?} else {
        /*graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 90, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, this.statusMessage, this.width / 2, this.height / 2 + 60, 0xFFFFFF55);*/
        //?}
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        boolean idle = this.statusMessage.getString().isEmpty();
        Component message = idle ? Component.translatable("peercraft.gui.join.code_help") : this.statusMessage;
        int color = idle ? SteampunkSettingsTheme.MUTED
                : this.connectButton.active ? PeerCraftUi.TEXT_ERROR : SteampunkSettingsTheme.ACCENT;
        this.dialog.status(graphics, this.font, message, this.statusY, 27, color, mouseX, mouseY);

    }*/
    //?}
    //? if >=26.1 {
    /*    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.dialog.background(graphics, this.font, this.width, this.height,
                (System.nanoTime() - this.animationStart) / 1_000_000L);
        graphics.text(this.font, Component.translatable("peercraft.gui.join.room_code_field"),
                this.dialog.contentX(), this.codeLabelY, SteampunkSettingsTheme.TEXT, false);
        this.dialog.divider(graphics, this.dividerY);
    }*/
    //?}

}
