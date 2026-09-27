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

    private EditBox newNameBox;
    private Button saveButton;
    private Component statusMessage = Component.empty();
    private int statusColor = PeerCraftUi.TEXT_MUTED;
    //? if =1.21.1 {
    private final long animationStart = System.nanoTime();
    //?}

    public PeerCraftRenameScreen(Screen lastScreen) {
        super(Component.translatable("peercraft.gui.rename.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 30;

        //? if =1.21.1 {
        this.newNameBox = new SteampunkSettingsTheme.Field(this.font, centerX - 100, y, 200, 20, Component.translatable("peercraft.gui.rename.field"));
        //?} else {
        /*this.newNameBox = new EditBox(this.font, centerX - 100, y, 200, 20, Component.translatable("peercraft.gui.rename.field"));*/
        //?}
        this.newNameBox.setMaxLength(16);
        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            this.newNameBox.setValue(session.displayName());
        }
        this.addRenderableWidget(this.newNameBox);
        this.setInitialFocus(this.newNameBox);

        y += 26;
        //? if =1.21.1 {
        this.saveButton = this.addRenderableWidget(SteampunkSettingsTheme.action(centerX - 100, y, 200, 20,
                Component.translatable("peercraft.gui.rename.save"), b -> onSave(), true));
        //?} else {
        /*this.saveButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.rename.save"), b -> onSave())
                .bounds(centerX - 100, y, 200, 20).build());*/
        //?}

        y += 26;
        //? if =1.21.1 {
        this.addRenderableWidget(SteampunkSettingsTheme.action(centerX - 100, y, 200, 20,
                Component.translatable("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen), false));
        //?} else {
        /*this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                .bounds(centerX - 100, y, 200, 20).build());*/
        //?}
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

    // 26.1 renamed GuiGraphics -> GuiGraphicsExtractor and replaced Screen#render with
    // #extractRenderState (render-state extraction pipeline); drawString/drawCenteredString became
    // text/centeredText. The background is painted by Screen itself (as since 1.21.6).
    //? if <26.1 {
    //? if =1.21.1 {
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = Math.min(320, this.width - 16);
        int panelHeight = Math.min(this.height - 16, 166);
        SteampunkSettingsTheme.screenBackground(graphics, this.width, this.height,
                (this.width - panelWidth) / 2, (this.height - panelHeight) / 2, panelWidth, panelHeight,
                (System.nanoTime() - this.animationStart) / 1_000_000L);
    }
    //?}
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 1.21.6 made Screen call renderBackground() itself before render() runs — calling it
        // again here double-fires the (now once-per-frame) blur effect and crashes.
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 60, PeerCraftUi.TEXT_TITLE);
        graphics.drawCenteredString(this.font, this.statusMessage, this.width / 2, this.height / 2 + 40, this.statusColor);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 60, PeerCraftUi.TEXT_TITLE);
        graphics.centeredText(this.font, this.statusMessage, this.width / 2, this.height / 2 + 40, this.statusColor);
    }*/
    //?}
}
