package net.peercraft.client.gui;

// Minecraft 1.16.5 Fabric backport of src/main/.../client/gui/ModSyncConfirmScreen.java.
// Deltas: render(GuiGraphics) -> render(PoseStack); Button.builder -> Btn.builder;
// addRenderableWidget -> addButton; removeWidget -> clear from this.buttons/this.children;
// Checkbox.builder(...).onValueChange(...) -> ModSyncCheckbox (1.16.5 Checkbox + callback);
// mouseScrolled 4-arg -> 3-arg; switch expression -> classic switch; String.isBlank() ->
// trim().isEmpty(); Component.empty() -> new TextComponent(""). Keep in sync with the original.

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.client.gui.screens.Screen;
import net.peercraft.network.modsync.ModEntry;
import net.peercraft.network.modsync.ModSyncPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The trust gate: before mod sync writes anything to disk, this lists every jar it's about to
 * fetch as a scrollable list. Client-side mods carry a checkbox so the player can leave them
 * out; mods needed to join the host's world (client+server and server-only) have no checkbox.
 * A filter button cycles all / client-only / required. What the player unchecks is remembered
 * (see {@code ModSyncDeclinedStore}) so this screen doesn't reappear on every re-join.
 * Skipped entirely when {@code peercraft.modSync.autoAccept} is set.
 */
public class ModSyncConfirmScreen extends Screen {

    private static final int LIST_TOP = 68;
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_ROWS_SHOWN = 8;

    private enum ViewFilter {ALL, CLIENT_ONLY, REQUIRED}

    private final ModSyncPlan plan;
    private final List<ModSyncPlan.PlannedMod> allMods;
    /** Ids of client-side mods the player has unchecked. Only CLIENT-env ids ever land here. */
    private final Set<String> deselected;
    private final Consumer<Set<String>> onAccept;
    private final Runnable onCancel;

    private ViewFilter filter = ViewFilter.ALL;
    private int scroll;
    private Button filterButton;
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();

    public ModSyncConfirmScreen(ModSyncPlan plan,
                                Set<String> initiallyDeselected,
                                Consumer<Set<String>> onAccept,
                                Runnable onCancel) {
        super(new TranslatableComponent("peercraft.modsync.confirm.title"));
        this.plan = plan;
        this.onAccept = onAccept;
        this.onCancel = onCancel;

        this.allMods = new ArrayList<>(plan.mods());
        this.allMods.sort(Comparator
                .comparingInt((ModSyncPlan.PlannedMod m) -> isClient(m) ? 1 : 0)
                .thenComparing(m -> m.entry().id()));

        this.deselected = new LinkedHashSet<>();
        for (ModSyncPlan.PlannedMod m : this.allMods) {
            if (isClient(m) && initiallyDeselected.contains(m.entry().id())) {
                this.deselected.add(m.entry().id());
            }
        }
    }

    private static boolean isClient(ModSyncPlan.PlannedMod m) {
        return m.entry().env() == ModEntry.Env.CLIENT;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.filterButton = Btn.builder(filterLabel(), (Button.OnPress) b -> cycleFilter())
                .bounds(centerX + 62, 42, 150, 20).build();
        this.addButton(this.filterButton);
        rebuildRows();

        int y = this.height - 52;
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.modsync.confirm.accept"), (Button.OnPress) b -> accept())
                .bounds(centerX - 154, y, 150, 20).build());
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.modsync.confirm.cancel"), (Button.OnPress) b -> onCancel.run())
                .bounds(centerX + 4, y, 150, 20).build());
    }

    @Override
    public void onClose() {
        onCancel.run();
    }

    private void accept() {
        onAccept.accept(new LinkedHashSet<>(deselected));
    }

    private Component filterLabel() {
        String key;
        switch (filter) {
            case CLIENT_ONLY:
                key = "peercraft.modsync.confirm.filter_client";
                break;
            case REQUIRED:
                key = "peercraft.modsync.confirm.filter_required";
                break;
            default:
                key = "peercraft.modsync.confirm.filter_all";
                break;
        }
        return new TranslatableComponent(key);
    }

    private void cycleFilter() {
        filter = ViewFilter.values()[(filter.ordinal() + 1) % ViewFilter.values().length];
        scroll = 0;
        if (filterButton != null) {
            filterButton.setMessage(filterLabel());
        }
        rebuildRows();
    }

    private List<ModSyncPlan.PlannedMod> visibleMods() {
        List<ModSyncPlan.PlannedMod> out = new ArrayList<>();
        for (ModSyncPlan.PlannedMod m : allMods) {
            boolean client = isClient(m);
            if (filter == ViewFilter.CLIENT_ONLY && !client) {
                continue;
            }
            if (filter == ViewFilter.REQUIRED && client) {
                continue;
            }
            out.add(m);
        }
        return out;
    }

    private int visibleRows() {
        int avail = (this.height - 96) - LIST_TOP;
        return Math.max(0, Math.min(MAX_ROWS_SHOWN, avail / ROW_HEIGHT));
    }

    /** Client-side rows get a fresh checkbox each rebuild — the state lives in {@link #deselected}. */
    private void rebuildRows() {
        for (AbstractWidget w : rowWidgets) {
            this.buttons.remove(w);
            this.children.remove(w);
        }
        rowWidgets.clear();

        List<ModSyncPlan.PlannedMod> vis = visibleMods();
        int maxScroll = Math.max(0, vis.size() - visibleRows());
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int centerX = this.width / 2;
        int rows = Math.min(visibleRows(), vis.size() - scroll);
        for (int i = 0; i < rows; i++) {
            ModSyncPlan.PlannedMod m = vis.get(scroll + i);
            if (!isClient(m)) {
                continue;
            }
            final String id = m.entry().id();
            int rowY = LIST_TOP + i * ROW_HEIGHT;
            ModSyncCheckbox cb = new ModSyncCheckbox(centerX - 210, rowY + 1, 20, 20, new TextComponent(""),
                    !deselected.contains(id),
                    val -> {
                        if (val) {
                            deselected.remove(id);
                        } else {
                            deselected.add(id);
                        }
                    });
            this.addButton(cb);
            rowWidgets.add(cb);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int total = visibleMods().size();
        int visible = visibleRows();
        if (delta != 0 && total > visible
                && mouseY >= LIST_TOP && mouseY < LIST_TOP + visible * ROW_HEIGHT) {
            int next = Math.max(0, Math.min(scroll - (int) Math.signum(delta), total - visible));
            if (next != scroll) {
                scroll = next;
                rebuildRows();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
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
        return new TranslatableComponent(m.source() == ModSyncPlan.Source.HTTP
                ? "peercraft.modsync.confirm.source_http"
                : "peercraft.modsync.confirm.source_p2p").getString();
    }

    private static String rowName(ModEntry e) {
        return e.version().trim().isEmpty() ? e.id() : e.id() + "  " + e.version();
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, centerX, 14, 0xFFFFFFFF);
        GuiComponent.drawCenteredString(poseStack, this.font,
                new TranslatableComponent("peercraft.modsync.confirm.intro2", allMods.size()), centerX, 28, 0xFFAAAAAA);

        List<ModSyncPlan.PlannedMod> vis = visibleMods();
        int rows = Math.min(visibleRows(), Math.max(0, vis.size() - scroll));
        for (int i = 0; i < rows; i++) {
            ModSyncPlan.PlannedMod m = vis.get(scroll + i);
            ModEntry e = m.entry();
            int textY = LIST_TOP + i * ROW_HEIGHT + 6;
            boolean client = isClient(m);
            if (!client) {
                GuiComponent.drawString(poseStack, this.font, "—", centerX - 204, textY, 0xFF777777);
            }
            GuiComponent.drawString(poseStack, this.font, rowName(e), centerX - 186, textY, 0xFFFFFFFF);
            GuiComponent.drawString(poseStack, this.font, new TranslatableComponent(client
                            ? "peercraft.modsync.confirm.tag_client"
                            : "peercraft.modsync.confirm.tag_required").getString(),
                    centerX + 20, textY, client ? 0xFFAAAAAA : 0xFFFFD966);
            GuiComponent.drawString(poseStack, this.font, humanSize(e.sizeBytes()) + ", " + sourceLabel(m),
                    centerX + 96, textY, 0xFFAAAAAA);
        }
        drawScrollbar(poseStack, centerX, vis.size());

        ModSyncPlan sel = plan.excluding(deselected);
        GuiComponent.drawCenteredString(poseStack, this.font,
                new TranslatableComponent("peercraft.modsync.confirm.count_selected", sel.count(), plan.count()),
                centerX, this.height - 86, 0xFFFFFFFF);
        GuiComponent.drawCenteredString(poseStack, this.font,
                new TranslatableComponent("peercraft.modsync.confirm.total", humanSize(sel.totalBytes())),
                centerX, this.height - 72, 0xFFFFD966);
    }

    private void drawScrollbar(PoseStack poseStack, int centerX, int total) {
        int visible = visibleRows();
        if (visible <= 0 || total <= visible) {
            return;
        }
        int trackHeight = visible * ROW_HEIGHT;
        int left = centerX + 214;
        int right = left + 6;
        GuiComponent.fill(poseStack, left, LIST_TOP, right, LIST_TOP + trackHeight, 0xFF000000);
        int maxScroll = total - visible;
        int s = Math.max(0, Math.min(scroll, maxScroll));
        int thumbHeight = Math.max(16, trackHeight * visible / total);
        int thumbY = LIST_TOP + (trackHeight - thumbHeight) * s / maxScroll;
        GuiComponent.fill(poseStack, left, thumbY, right, thumbY + thumbHeight, 0xFFA0A0A0);
    }
}
