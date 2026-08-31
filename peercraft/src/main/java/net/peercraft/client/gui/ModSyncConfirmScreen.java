package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.network.modsync.ModSyncPlan;

/**
 * The trust gate: before mod sync writes anything to disk, this lists every jar it's about
 * to fetch (id, version, size, whether it's an HTTP download or a peer-to-peer transfer) and
 * makes the player click through. Skipped only when {@code peercraft.modSync.autoAccept} is set.
 */
public class ModSyncConfirmScreen extends Screen {

    private static final int MAX_LISTED = 10;

    private final ModSyncPlan plan;
    private final Runnable onAccept;
    private final Runnable onCancel;

    public ModSyncConfirmScreen(ModSyncPlan plan, Runnable onAccept, Runnable onCancel) {
        super(Component.translatable("peercraft.modsync.confirm.title"));
        this.plan = plan;
        this.onAccept = onAccept;
        this.onCancel = onCancel;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height - 52;
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.modsync.confirm.accept"), b -> onAccept.run())
                .bounds(centerX - 154, y, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.modsync.confirm.cancel"), b -> onCancel.run())
                .bounds(centerX + 4, y, 150, 20).build());
    }

    @Override
    public void onClose() {
        onCancel.run();
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.0f KB", kb);
        }
        double mb = kb / 1024.0;
        return String.format("%.1f MB", mb);
    }

    private String sourceLabel(ModSyncPlan.PlannedMod m) {
        return Component.translatable(m.source() == ModSyncPlan.Source.HTTP
                ? "peercraft.modsync.confirm.source_http"
                : "peercraft.modsync.confirm.source_p2p").getString();
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int y = 34;
        graphics.drawCenteredString(this.font, this.title, centerX, y, 0xFFFFFFFF);
        y += 16;
        graphics.drawCenteredString(this.font, Component.translatable("peercraft.modsync.confirm.intro", plan.count()), centerX, y, 0xFFAAAAAA);
        y += 18;
        int shown = Math.min(MAX_LISTED, plan.mods().size());
        for (int i = 0; i < shown; i++) {
            ModSyncPlan.PlannedMod m = plan.mods().get(i);
            String line = Component.translatable("peercraft.modsync.confirm.row",
                    m.entry().id(), m.entry().version(), humanSize(m.entry().sizeBytes()), sourceLabel(m)).getString();
            graphics.drawCenteredString(this.font, line, centerX, y, 0xFFFFFFFF);
            y += 12;
        }
        if (plan.mods().size() > shown) {
            graphics.drawCenteredString(this.font, "… +" + (plan.mods().size() - shown), centerX, y, 0xFFAAAAAA);
            y += 12;
        }
        y += 6;
        graphics.drawCenteredString(this.font, Component.translatable("peercraft.modsync.confirm.total", humanSize(plan.totalBytes())), centerX, y, 0xFFFFD966);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int y = 34;
        graphics.centeredText(this.font, this.title, centerX, y, 0xFFFFFFFF);
        y += 16;
        graphics.centeredText(this.font, Component.translatable("peercraft.modsync.confirm.intro", plan.count()), centerX, y, 0xFFAAAAAA);
        y += 18;
        int shown = Math.min(MAX_LISTED, plan.mods().size());
        for (int i = 0; i < shown; i++) {
            ModSyncPlan.PlannedMod m = plan.mods().get(i);
            String line = Component.translatable("peercraft.modsync.confirm.row",
                    m.entry().id(), m.entry().version(), humanSize(m.entry().sizeBytes()), sourceLabel(m)).getString();
            graphics.centeredText(this.font, line, centerX, y, 0xFFFFFFFF);
            y += 12;
        }
        if (plan.mods().size() > shown) {
            graphics.centeredText(this.font, "… +" + (plan.mods().size() - shown), centerX, y, 0xFFAAAAAA);
            y += 12;
        }
        y += 6;
        graphics.centeredText(this.font, Component.translatable("peercraft.modsync.confirm.total", humanSize(plan.totalBytes())), centerX, y, 0xFFFFD966);
    }*/
    //?}
}
