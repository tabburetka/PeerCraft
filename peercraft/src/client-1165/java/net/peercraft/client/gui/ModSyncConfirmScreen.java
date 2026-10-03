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
public class ModSyncConfirmScreen extends PeerCraftDialogScreen {

    private int listTop;
    private int listBottom;
    private java.util.List<String> reminderLines;
    private static final int ROW_HEIGHT = 24;

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
        super(new TranslatableComponent("peercraft.modsync.confirm.title"), 500);
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
        super.init();
        rowWidgets.clear();
        reminderLines = PeerCraftUi.wrap(font, new TranslatableComponent("peercraft.modsync.confirm.trust_reminder").getString(), dialog.contentWidth());
        int filterY = dialog.contentTop() + 14 + reminderLines.size() * font.lineHeight + 4;
        filterButton = addButton(Btn.builder(filterLabel(), b -> cycleFilter())
                .bounds(dialog.contentX(), filterY, dialog.contentWidth(), dialog.buttonHeight()).build());
        dialogAction(new TranslatableComponent("peercraft.modsync.confirm.accept"), b -> accept(), true, 0, 2);
        dialogAction(new TranslatableComponent("peercraft.modsync.confirm.cancel"), b -> onCancel.run(), false, 1, 2);
        listTop = filterY + dialog.buttonPitch();
        listBottom = bodyBottom() - 24;
        rebuildRows();
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
        return Math.max(0, (listBottom - listTop) / ROW_HEIGHT);
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
            int rowY = listTop + i * ROW_HEIGHT;
            ModSyncCheckbox cb = new ModSyncCheckbox(dialog.contentX(), rowY + 4, 16, 16, new TextComponent(""),
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
                && mouseX >= dialog.contentX() && mouseX < dialog.left + dialog.width
                && mouseY >= listTop && mouseY < listTop + visible * ROW_HEIGHT) {
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
        renderBackground(poseStack);
        GuiComponent.drawCenteredString(poseStack, font, new TranslatableComponent("peercraft.modsync.confirm.intro2", allMods.size()), width / 2, dialog.contentTop(), PeerCraftUi.TEXT_MUTED);
        for (int i = 0; i < reminderLines.size(); i++) GuiComponent.drawCenteredString(poseStack, font, reminderLines.get(i), width / 2,
                dialog.contentTop() + 14 + i * font.lineHeight, PeerCraftUi.TEXT_ERROR);
        List<ModSyncPlan.PlannedMod> mods = visibleMods();
        int rows = Math.min(visibleRows(), Math.max(0, mods.size() - scroll));
        for (int i = 0; i < rows; i++) {
            ModSyncPlan.PlannedMod mod = mods.get(scroll + i);
            ModEntry entry = mod.entry();
            int y = listTop + i * ROW_HEIGHT;
            String name = rowName(entry);
            String detail = new TranslatableComponent(isClient(mod) ? "peercraft.modsync.confirm.tag_client" : "peercraft.modsync.confirm.tag_required").getString()
                    + " · " + humanSize(entry.sizeBytes()) + " · " + sourceLabel(mod);
            if (!isClient(mod)) GuiComponent.drawString(poseStack, font, "—", dialog.contentX() + 4, y + 6, PeerCraftUi.TEXT_MUTED);
            GuiComponent.drawString(poseStack, font, font.plainSubstrByWidth(name, dialog.contentWidth() - 26), dialog.contentX() + 22, y + 1, PeerCraftUi.TEXT_TITLE);
            GuiComponent.drawString(poseStack, font, font.plainSubstrByWidth(detail, dialog.contentWidth() - 26), dialog.contentX() + 22, y + 12,
                    isClient(mod) ? PeerCraftUi.TEXT_MUTED : PeerCraftUi.TEXT_ACCENT);
        }
        drawScrollbar(poseStack, width / 2, mods.size());
        ModSyncPlan selected = plan.excluding(deselected);
        GuiComponent.drawCenteredString(poseStack, font, new TranslatableComponent("peercraft.modsync.confirm.count_selected", selected.count(), plan.count()),
                width / 2, bodyBottom() - 22, PeerCraftUi.TEXT_TITLE);
        GuiComponent.drawCenteredString(poseStack, font, new TranslatableComponent("peercraft.modsync.confirm.total", humanSize(selected.totalBytes())),
                width / 2, bodyBottom() - 11, PeerCraftUi.TEXT_ACCENT);
        super.render(poseStack, mouseX, mouseY, partialTick);
        if (mouseX >= dialog.contentX() && mouseX < dialog.contentX() + dialog.contentWidth() && mouseY >= listTop && mouseY < listTop + rows * ROW_HEIGHT) {
            ModSyncPlan.PlannedMod mod = mods.get(scroll + (mouseY - listTop) / ROW_HEIGHT);
            renderTooltip(poseStack, new TextComponent(rowName(mod.entry()) + " · " + humanSize(mod.entry().sizeBytes()) + " · " + sourceLabel(mod)), mouseX, mouseY);
        }
    }

    private void drawScrollbar(PoseStack poseStack, int centerX, int total) {
        int visible = visibleRows();
        if (visible <= 0 || total <= visible) {
            return;
        }
        int trackHeight = visible * ROW_HEIGHT;
        int left = dialog.left + dialog.width - 7;
        int right = left + 2;
        GuiComponent.fill(poseStack, left, listTop, right, listTop + trackHeight, 0xFF49331F);
        int maxScroll = total - visible;
        int s = Math.max(0, Math.min(scroll, maxScroll));
        int thumbHeight = Math.max(16, trackHeight * visible / total);
        int thumbY = listTop + (trackHeight - thumbHeight) * s / maxScroll;
        GuiComponent.fill(poseStack, left, thumbY, right, thumbY + thumbHeight, PeerCraftUi.TEXT_ACCENT);
    }
}
