package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.network.account.AccountClient;
import org.lwjgl.glfw.GLFW;

/** Rename for unlicensed accounts only — see AccountService.rename, which rejects licensed accounts server-side too. */
public class PeerCraftRenameScreen extends Screen {

    private final Screen lastScreen;
    private SteampunkDialog dialog;
    private int statusY, successHintHeight;

    private EditBox newNameBox;
    private Button saveButton;
    private Component statusMessage = Component.empty();
    private int statusColor = PeerCraftUi.TEXT_MUTED;
    //? if >=1.21.1 {
    private final long animationStart = System.nanoTime();
    //?}

    public PeerCraftRenameScreen(Screen lastScreen) {
        super(Component.translatable("peercraft.gui.rename.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        AccountClient.AccountSession current = AccountSessionHolder.current();
        String firstValue = newNameBox == null ? (current == null ? "" : current.displayName()) : newNameBox.getValue();
        Component subtitle = Component.literal(title.getString().replace("PeerCraft — ", ""));
        dialog = new SteampunkDialog(width, height, 220, subtitle);
        int fieldRow = dialog.buttonPitch() + 12;
        int desiredHeight = dialog.headerHeight + 6 + 1 * fieldRow + 2 * dialog.buttonPitch() + 26 + 12;
        dialog = new SteampunkDialog(width, height, desiredHeight, subtitle);
        int x = dialog.contentX(), w = dialog.contentWidth(), h = dialog.buttonHeight();
        int y = dialog.contentTop() + 12;
        newNameBox = new SteampunkSettingsTheme.Field(font, x, y, w, h, Component.translatable("peercraft.gui.rename.field"));
        newNameBox.setMaxLength(16);
        newNameBox.setHint(Component.translatable("peercraft.gui.register.nickname_hint"));
        newNameBox.setValue(firstValue);
        addRenderableWidget(newNameBox);
        setInitialFocus(newNameBox);
        y = dialog.contentTop() + 1 * fieldRow;
        saveButton = addRenderableWidget(SteampunkSettingsTheme.action(x, y, w, h,
                Component.translatable("peercraft.gui.rename.save"), b -> onSave(), true));
        y += dialog.buttonPitch();
        addRenderableWidget(SteampunkSettingsTheme.action(x, y, w, h,
                Component.translatable("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(minecraft, lastScreen), false));
        statusY = y + dialog.buttonPitch() + 2;
    }

    // Screen.keyPressed switched from (int,int,int) to a KeyEvent record parameter in 1.21.9.
    //? if <1.21.9 {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
    //?} else {
    /*
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (super.keyPressed(event)) {
            return true;
        }
        int keyCode = event.key();
    */
    //?}
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && this.saveButton.active) {
            onSave();
            return true;
        }
        return false;
    }

    private void onSave() {
        String newName = this.newNameBox.getValue().trim();
        if (newName.length() < 3 || newName.length() > 16) {
            this.statusMessage = Component.translatable("peercraft.gui.register.nickname_length_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        if (!PeerCraftUi.isValidUsername(newName)) {
            this.statusMessage = Component.translatable("peercraft.gui.register.nickname_charset_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }

        this.saveButton.active = false;
        this.statusMessage = Component.translatable("peercraft.gui.rename.saving");
        this.statusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.renameDisplayName(newName, new AccountClient.RenameCallback() {
            @Override
            public void onSuccess(String appliedName) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    AccountClient.AccountSession updated = AccountSessionHolder.current();
                    if (updated != null) {
                        AccountSessionHolder.persist(updated);
                    }
                    PeerCraftUi.setScreen(minecraft, new PeerCraftAccountScreen(lastScreen));
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    statusMessage = Component.literal(reason);
                    statusColor = PeerCraftUi.TEXT_ERROR;
                    saveButton.active = true;
                });
            }
        });
    }

    private void runOnClientThread(Runnable action) {
        Minecraft.getInstance().execute(action);
    }

    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    //? if <26.1 {
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        dialog.background(graphics, font, width, height, (System.nanoTime() - animationStart) / 1_000_000L);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, Component.translatable("peercraft.gui.rename.field"), dialog.contentX(), newNameBox.getY() - 12, SteampunkSettingsTheme.MUTED, false);
        dialog.status(graphics, font, statusMessage, statusY, 26, statusColor, mouseX, mouseY);
    }
    //?} else {
    /*@Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        dialog.background(graphics, font, width, height, (System.nanoTime() - animationStart) / 1_000_000L);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(font, Component.translatable("peercraft.gui.rename.field"), dialog.contentX(), newNameBox.getY() - 12, SteampunkSettingsTheme.MUTED, false);
        dialog.status(graphics, font, statusMessage, statusY, 26, statusColor, mouseX, mouseY);
    }*/
    //?}
}
