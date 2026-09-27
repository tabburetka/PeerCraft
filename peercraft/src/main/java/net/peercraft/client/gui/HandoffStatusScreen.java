package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Host-side progress screen for an in-flight handoff: waiting for the successor to accept,
 * sending the world, telling everyone to reconnect, done. Built by {@link HandoffPlayerPickerScreen}
 * and updated from the {@code HandoffCoordinator} callbacks (which fire off-thread, so every
 * mutator marshals through {@code minecraft.execute}).
 */
public class HandoffStatusScreen extends Screen {

    private final Screen backScreen;
    private final String successorName;

    private volatile String statusKey = "peercraft.handoff.status.offering";
    private volatile String terminalKey;   // non-null once done/aborted
    private volatile boolean success;
    private volatile long sentBytes;
    private volatile long totalBytes;

    public HandoffStatusScreen(Screen backScreen, String successorName) {
        super(Component.translatable("peercraft.handoff.picker.title"));
        this.backScreen = backScreen;
        this.successorName = successorName;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        int cx = this.width / 2;
        if (terminalKey == null) {
            return; // in progress — no buttons
        }
        if (success) {
            this.addRenderableWidget(Button.builder(Component.translatable("menu.returnToMenu"), b -> toTitle())
                    .bounds(cx - 100, this.height - 40, 200, 20).build());
        } else {
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.modsync.restart.back"),
                            b -> PeerCraftUi.setScreen(this.minecraft, backScreen))
                    .bounds(cx - 100, this.height - 40, 200, 20).build());
        }
    }

    /** Wire these into HandoffCoordinator.Callbacks. All safe to call from any thread. */
    public void onStatus(String key) {
        run(() -> {
            this.statusKey = key;
            rebuild();
        });
    }

    /** Host-side world-archive send progress. */
    public void onSendProgress(long sent, long total) {
        this.sentBytes = sent;
        this.totalBytes = total;
    }

    public void onDone(String key) {
        run(() -> {
            this.terminalKey = key;
            this.success = true;
            rebuild();
        });
    }

    public void onAborted(String key) {
        run(() -> {
            this.terminalKey = key;
            this.success = false;
            rebuild();
        });
    }

    private void run(Runnable r) {
        if (this.minecraft != null) {
            this.minecraft.execute(r);
        } else {
            r.run();
        }
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    // Just swapping to TitleScreen (the old behavior) left the old world's IntegratedServer —
    // and this player's own connection to it — running behind the scenes: visible as mobs
    // still walking around through the title screen's transparent bits, and it kept the
    // superseded rendezvous room alive too. Mirrors vanilla's own "Save and Quit to Title"
    // (PauseScreen's disconnect button): disconnect the level, then the client, then show the
    // title screen — ClientLevel.disconnect() gained a reason-Component param and
    // Minecraft.disconnect(Screen) was replaced by disconnectWithSavingScreen() at 1.21.6.
    private void toTitle() {
        net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.cancel();
        if (this.minecraft.level == null) { PeerCraftUi.setScreen(this.minecraft, new TitleScreen()); return; }
        //? if <1.21.6 {
        this.minecraft.level.disconnect();
        this.minecraft.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        //?} else {
        /*this.minecraft.level.disconnect(Component.translatable("menu.savingLevel"));
        this.minecraft.disconnectWithSavingScreen();
        *///?}
        PeerCraftUi.setScreen(this.minecraft, new TitleScreen());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return terminalKey != null && !success;
    }

    @Override
    public void onClose() {
        if (terminalKey != null && !success) {
            PeerCraftUi.setScreen(this.minecraft, backScreen);
        }
    }

    private List<String> lines() {
        String key = terminalKey != null ? terminalKey : statusKey;
        String text = Component.translatable(key, successorName).getString();
        List<String> out = PeerCraftUi.wrap(this.font, text, Math.min(this.width - 60, 360));
        long total = this.totalBytes;
        if (terminalKey == null && total > 0) {
            long sent = Math.min(this.sentBytes, total);
            int pct = (int) Math.round(100.0 * sent / total);
            out.add(PeerCraftUi.humanSize(sent) + " / " + PeerCraftUi.humanSize(total) + "  (" + pct + "%)");
        }
        return out;
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 24;
        graphics.drawCenteredString(this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 22;
        int color = terminalKey == null ? 0xFFCCCCCC : (success ? 0xFF55FF55 : 0xFFFF5555);
        for (String line : lines()) {
            graphics.drawCenteredString(this.font, line, cx, y, color);
            y += 12;
        }
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 24;
        graphics.centeredText(this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 22;
        int color = terminalKey == null ? 0xFFCCCCCC : (success ? 0xFF55FF55 : 0xFFFF5555);
        for (String line : lines()) {
            graphics.centeredText(this.font, line, cx, y, color);
            y += 12;
        }
    }*/
    //?}
}
