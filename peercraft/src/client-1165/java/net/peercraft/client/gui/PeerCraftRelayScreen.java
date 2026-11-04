package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.config.PeerCraftSettings;
import net.peercraft.config.PeerCraftSettingsStore;
import net.peercraft.network.ownrelay.OwnRelayClient;
import net.peercraft.network.ownrelay.OwnRelayProtocol;

/** Minecraft 1.16.5 backport of {@code src/main/.../PeerCraftRelayScreen.java}. */
public class PeerCraftRelayScreen extends PeerCraftDialogScreen {
    private final Screen lastScreen;
    private EditBox codeBox;
    private Button checkButton;
    private Component statusMessage = TextComponent.EMPTY;
    private int statusColor = PeerCraftUi.TEXT_MUTED;
    private int descY, statusY;
    private int checkGeneration;

    public PeerCraftRelayScreen(Screen lastScreen) {
        super(new TranslatableComponent("peercraft.gui.relay.title"), 300);
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        super.init();
        String value = codeBox == null ? PeerCraftConfig.ownRelayCode() : codeBox.getValue();
        int descHeight = 3 * 12;
        int desiredHeight = dialog.headerHeight + 6 + descHeight + 6 + 4 * dialog.buttonPitch() + dialog.buttonHeight() + 26 + 12;
        dialog = new SteampunkDialog(width, height, desiredHeight, title);
        int x = dialog.contentX(), w = dialog.contentWidth(), h = dialog.buttonHeight();
        descY = dialog.contentTop();
        int y = descY + descHeight + 6;
        codeBox = new SteampunkField(font, x, y, w, h, new TranslatableComponent("peercraft.gui.relay.field"));
        codeBox.setMaxLength(320);
        codeBox.setValue(value);
        addButton(codeBox);
        setFocused(codeBox);
        y += dialog.buttonPitch();
        int half = (w - 6) / 2;
        checkButton = addButton(Btn.builder(new TranslatableComponent("peercraft.gui.relay.check"), b -> onCheck())
                .bounds(x, y, half, h).build());
        addButton(Btn.builder(new TranslatableComponent("peercraft.gui.relay.guide"), b -> openGuide())
                .bounds(x + half + 6, y, w - half - 6, h).build());
        y += dialog.buttonPitch();
        addButton(Btn.builder(new TranslatableComponent("peercraft.gui.relay.save"), b -> onSave())
                .bounds(x, y, w, h).primary().build());
        y += dialog.buttonPitch();
        addButton(Btn.builder(new TranslatableComponent("peercraft.gui.common.back"), b -> onClose())
                .bounds(x, y, w, h).build());
        statusY = y + dialog.buttonPitch() + 2;
    }

    private void openGuide() {
        String url = PeerCraftConfig.relayGuideUrl();
        this.minecraft.setScreen(new ConfirmLinkScreen(confirmed -> {
            if (confirmed) Util.getPlatform().openUri(url);
            this.minecraft.setScreen(this);
        }, url, false));
    }

    @Override public void onClose() { PeerCraftUi.setScreen(minecraft, lastScreen); }

    private String code() { return codeBox.getValue().trim(); }

    private boolean validate() {
        if (code().isEmpty() || OwnRelayProtocol.parseCode(code()) != null) return true;
        setStatus(new TranslatableComponent("peercraft.gui.relay.check.invalid_code"), PeerCraftUi.TEXT_ERROR);
        return false;
    }

    private void onCheck() {
        if (code().isEmpty()) { setStatus(new TranslatableComponent("peercraft.gui.relay.empty"), PeerCraftUi.TEXT_ERROR); return; }
        if (!validate()) return;
        String code = code();
        int generation = ++checkGeneration;
        checkButton.active = false;
        setStatus(new TranslatableComponent("peercraft.gui.relay.check.checking"), PeerCraftUi.TEXT_MUTED);
        Thread thread = new Thread(() -> {
            OwnRelayClient.PingResult result = OwnRelayClient.ping(code);
            Minecraft.getInstance().execute(() -> {
                if (generation != checkGeneration || !PeerCraftUi.isCurrentScreen(this)) return;
                checkButton.active = true;
                if (result.ok) setStatus(new TranslatableComponent("peercraft.gui.relay.check.ok", result.millis), PeerCraftUi.TEXT_SUCCESS);
                else setStatus(new TranslatableComponent("peercraft.gui.relay.check." + result.reason), PeerCraftUi.TEXT_ERROR);
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

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        dialog.status(pose, font, new TranslatableComponent("peercraft.gui.relay.desc"), descY, 36, PeerCraftUi.TEXT_TITLE);
        super.render(pose, mouseX, mouseY, partialTick);
        dialog.status(pose, font, statusMessage, statusY, 26, statusColor);
    }
}
