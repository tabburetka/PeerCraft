package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.config.PeerCraftSettings;
import net.peercraft.config.PeerCraftSettingsStore;
import net.peercraft.network.ownrelay.OwnRelayClient;
import net.peercraft.network.ownrelay.OwnRelayProtocol;

/** Enter, check and save the code printed by a self-hosted peercraft-relay. */
public class PeerCraftRelayScreen extends Screen {
    private final Screen lastScreen;
    private SteampunkDialog dialog;
    private EditBox codeBox;
    private Button checkButton;
    private Component statusMessage = Component.empty();
    private int statusColor = PeerCraftUi.TEXT_MUTED;
    private int descY, statusY;
    private int checkGeneration;
    private final long animationStart = System.nanoTime();

    public PeerCraftRelayScreen(Screen lastScreen) {
        super(Component.translatable("peercraft.gui.relay.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        String value = codeBox == null ? PeerCraftConfig.ownRelayCode() : codeBox.getValue();
        Component subtitle = Component.translatable("peercraft.gui.relay.subtitle");
        dialog = new SteampunkDialog(width, height, 260, subtitle);
        int descHeight = 3 * 12;
        int fieldRow = dialog.buttonPitch() + 12;
        int desiredHeight = dialog.headerHeight + 6 + descHeight + 6 + fieldRow + 4 * dialog.buttonPitch() + 26 + 12;
        dialog = new SteampunkDialog(width, height, desiredHeight, subtitle);
        int x = dialog.contentX(), w = dialog.contentWidth(), h = dialog.buttonHeight();
        descY = dialog.contentTop();
        int y = descY + descHeight + 6 + 12;
        codeBox = new SteampunkSettingsTheme.Field(font, x, y, w, h, Component.translatable("peercraft.gui.relay.field"));
        codeBox.setMaxLength(320);
        codeBox.setHint(Component.translatable("peercraft.gui.relay.hint"));
        codeBox.setValue(value);
        addRenderableWidget(codeBox);
        setInitialFocus(codeBox);
        y += dialog.buttonPitch();
        int half = (w - 6) / 2;
        checkButton = addRenderableWidget(SteampunkSettingsTheme.action(x, y, half, h,
                Component.translatable("peercraft.gui.relay.check"), b -> onCheck(), false));
        addRenderableWidget(SteampunkSettingsTheme.action(x + half + 6, y, w - half - 6, h,
                Component.translatable("peercraft.gui.relay.guide"),
                ConfirmLinkScreen.confirmLink(this, PeerCraftConfig.relayGuideUrl()), false));
        y += dialog.buttonPitch();
        addRenderableWidget(SteampunkSettingsTheme.action(x, y, w, h,
                Component.translatable("peercraft.gui.relay.save"), b -> onSave(), true));
        y += dialog.buttonPitch();
        addRenderableWidget(SteampunkSettingsTheme.action(x, y, w, h,
                Component.translatable("peercraft.gui.common.back"), b -> onClose(), false));
        statusY = y + dialog.buttonPitch() + 2;
    }

    @Override
    public void onClose() {
        PeerCraftUi.setScreen(this.minecraft, this.lastScreen);
    }

    private String code() { return codeBox.getValue().trim(); }

    private boolean validate() {
        if (code().isEmpty() || OwnRelayProtocol.parseCode(code()) != null) return true;
        setStatus(Component.translatable("peercraft.gui.relay.check.invalid_code"), PeerCraftUi.TEXT_ERROR);
        return false;
    }

    private void onCheck() {
        if (code().isEmpty()) { setStatus(Component.translatable("peercraft.gui.relay.empty"), PeerCraftUi.TEXT_ERROR); return; }
        if (!validate()) return;
        String code = code();
        int generation = ++checkGeneration;
        checkButton.active = false;
        setStatus(Component.translatable("peercraft.gui.relay.check.checking"), PeerCraftUi.TEXT_MUTED);
        Thread thread = new Thread(() -> {
            OwnRelayClient.PingResult result = OwnRelayClient.ping(code);
            Minecraft.getInstance().execute(() -> {
                if (generation != checkGeneration || !PeerCraftUi.isCurrentScreen(this)) return;
                checkButton.active = true;
                if (result.ok) setStatus(Component.translatable("peercraft.gui.relay.check.ok", result.millis), PeerCraftUi.TEXT_SUCCESS);
                else setStatus(Component.translatable("peercraft.gui.relay.check." + result.reason), PeerCraftUi.TEXT_ERROR);
            });
        }, "PeerCraft-RelayCheck");
        thread.setDaemon(true);
        thread.start();
    }

    private void onSave() {
        if (!validate()) return;
        PeerCraftSettings settings = PeerCraftSettingsStore.load();
        settings.set("relay.ownCode", code().isEmpty() ? null : code());
        PeerCraftSettingsStore.save(settings);
        PeerCraftConfig.applyOverrides(settings.toOverrideMap());
        onClose();
    }

    private void setStatus(Component message, int color) { statusMessage = message; statusColor = color; }

    //? if <26.1 {
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        dialog.background(graphics, font, width, height, (System.nanoTime() - animationStart) / 1_000_000L);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        dialog.status(graphics, font, Component.translatable("peercraft.gui.relay.desc"), descY, 36, SteampunkSettingsTheme.TEXT, mouseX, mouseY);
        graphics.drawString(font, Component.translatable("peercraft.gui.relay.field"), dialog.contentX(), codeBox.getY() - 12, SteampunkSettingsTheme.MUTED, false);
        dialog.status(graphics, font, statusMessage, statusY, 26, statusColor, mouseX, mouseY);
    }
    //?} else {
    /*@Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        dialog.background(graphics, font, width, height, (System.nanoTime() - animationStart) / 1_000_000L);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        dialog.status(graphics, font, Component.translatable("peercraft.gui.relay.desc"), descY, 36, SteampunkSettingsTheme.TEXT, mouseX, mouseY);
        graphics.text(font, Component.translatable("peercraft.gui.relay.field"), dialog.contentX(), codeBox.getY() - 12, SteampunkSettingsTheme.MUTED, false);
        dialog.status(graphics, font, statusMessage, statusY, 26, statusColor, mouseX, mouseY);
    }*/
    //?}
}
