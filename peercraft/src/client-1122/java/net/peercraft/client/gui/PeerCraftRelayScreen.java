package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.config.PeerCraftSettings;
import net.peercraft.config.PeerCraftSettingsStore;
import net.peercraft.network.ownrelay.OwnRelayClient;
import net.peercraft.network.ownrelay.OwnRelayProtocol;
import org.lwjgl.input.Keyboard;

/** Forge 1.12.2 backport of {@code src/main/.../PeerCraftRelayScreen.java}. */
public class PeerCraftRelayScreen extends PeerCraftDialogScreen {
    private final GuiScreen lastScreen;
    private GuiTextField codeBox;
    private IdButton checkButton;
    private String statusMessage = "";
    private int statusColor = PeerCraftUi.TEXT_MUTED;
    private int descY, statusY;
    private int checkGeneration;

    public PeerCraftRelayScreen(GuiScreen lastScreen) {
        super(PeerCraftLang.tr("peercraft.gui.relay.title"), 300);
        this.lastScreen = lastScreen;
    }

    @Override
    public void initGui() {
        String value = codeBox == null ? PeerCraftConfig.ownRelayCode() : codeBox.getText();
        boolean checking = checkButton != null && !checkButton.enabled;
        super.initGui();
        int descHeight = 3 * 12;
        int desiredHeight = dialog.headerHeight + 6 + descHeight + 6 + 12 + 4 * dialog.buttonPitch() + dialog.buttonHeight() + 26 + 12;
        dialog = new SteampunkDialog(width, height, desiredHeight, PeerCraftLang.tr("peercraft.gui.relay.title"));
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();
        int x = dialog.contentX(), w = dialog.contentWidth(), h = dialog.buttonHeight();
        descY = dialog.contentTop();
        int y = descY + descHeight + 6 + 12;
        codeBox = new SteampunkField(0, this.fontRenderer, x, y, w, h);
        codeBox.setMaxStringLength(320);
        codeBox.setText(value);
        codeBox.setFocused(true);
        y += dialog.buttonPitch();
        int half = (w - 6) / 2;
        checkButton = IdButton.builder(PeerCraftLang.tr("peercraft.gui.relay.check"), this::onCheck).bounds(x, y, half, h).build();
        checkButton.enabled = !checking;
        this.buttonList.add(checkButton);
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.relay.guide"), this::openGuide)
                .bounds(x + half + 6, y, w - half - 6, h).build());
        y += dialog.buttonPitch();
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.relay.save"), this::onSave)
                .primary().bounds(x, y, w, h).build());
        y += dialog.buttonPitch();
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.common.back"), this::close)
                .bounds(x, y, w, h).build());
        statusY = y + dialog.buttonPitch() + 2;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    private void close() { PeerCraftUi.setScreen(this.mc, this.lastScreen); }

    private void openGuide() {
        String url = PeerCraftConfig.relayGuideUrl();
        try {
            org.lwjgl.Sys.openURL(url);
        } catch (Throwable t) {
            try { java.awt.Desktop.getDesktop().browse(new java.net.URI(url)); } catch (Throwable ignored) { }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws java.io.IOException {
        if (button instanceof IdButton) ((IdButton) button).onPress.run();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) { close(); return; }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) { onSave(); return; }
        if (codeBox.textboxKeyTyped(typedChar, keyCode)) return;
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        codeBox.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void updateScreen() {
        codeBox.updateCursorCounter();
    }

    private String code() { return codeBox.getText().trim(); }

    private boolean validate() {
        if (code().isEmpty() || OwnRelayProtocol.parseCode(code()) != null) return true;
        setStatus(PeerCraftLang.tr("peercraft.gui.relay.check.invalid_code"), PeerCraftUi.TEXT_ERROR);
        return false;
    }

    private void onCheck() {
        if (code().isEmpty()) { setStatus(PeerCraftLang.tr("peercraft.gui.relay.empty"), PeerCraftUi.TEXT_ERROR); return; }
        if (!validate()) return;
        final String code = code();
        final int generation = ++checkGeneration;
        checkButton.enabled = false;
        setStatus(PeerCraftLang.tr("peercraft.gui.relay.check.checking"), PeerCraftUi.TEXT_MUTED);
        Thread thread = new Thread(() -> {
            OwnRelayClient.PingResult result = OwnRelayClient.ping(code);
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (generation != checkGeneration || !PeerCraftUi.isCurrentScreen(this)) return;
                checkButton.enabled = true;
                if (result.ok) setStatus(PeerCraftLang.tr("peercraft.gui.relay.check.ok", result.millis), PeerCraftUi.TEXT_SUCCESS);
                else setStatus(PeerCraftLang.tr("peercraft.gui.relay.check." + result.reason), PeerCraftUi.TEXT_ERROR);
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
        close();
    }

    private void setStatus(String message, int color) { statusMessage = message; statusColor = color; }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        dialog.status(this.fontRenderer, PeerCraftLang.tr("peercraft.gui.relay.desc"), descY, 36, PeerCraftUi.TEXT_TITLE);
        label("peercraft.gui.relay.field", codeBox.y - 12);
        codeBox.drawTextBox();
        dialog.status(this.fontRenderer, statusMessage, statusY, 26, statusColor);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
