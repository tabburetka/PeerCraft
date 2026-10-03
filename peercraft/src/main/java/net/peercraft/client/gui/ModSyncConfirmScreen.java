package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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

    private int LIST_TOP = 82;
    private int filterY;
    private static final int ROW_HEIGHT = 28;

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
        super(Component.translatable("peercraft.modsync.confirm.title"));
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
        filterY = 44 + this.font.split(Component.translatable("peercraft.modsync.confirm.trust_reminder"), contentWidth()).size() * 10 + 4;
        LIST_TOP = filterY + 28;
        this.filterButton = SteampunkSettingsTheme.action(contentLeft(), filterY, contentWidth(), 20, filterLabel(), b -> cycleFilter(), false);
        this.addRenderableWidget(this.filterButton);
        rebuildRows();

        int y = this.height - 52;
        this.addRenderableWidget(SteampunkSettingsTheme.action(contentLeft(), y, (contentWidth() - 8) / 2, 20, Component.translatable("peercraft.modsync.confirm.accept"), b -> accept(), true));
        this.addRenderableWidget(SteampunkSettingsTheme.action(contentLeft() + (contentWidth() + 8) / 2, y, (contentWidth() - 8) / 2, 20, Component.translatable("peercraft.modsync.confirm.cancel"), b -> onCancel.run(), false));
    }

    @Override
    public void onClose() {
        onCancel.run();
    }

    private void accept() {
        onAccept.accept(new LinkedHashSet<>(deselected));
    }

    private Component filterLabel() {
        String key = switch (filter) {
            case CLIENT_ONLY -> "peercraft.modsync.confirm.filter_client";
            case REQUIRED -> "peercraft.modsync.confirm.filter_required";
            default -> "peercraft.modsync.confirm.filter_all";
        };
        return Component.translatable(key);
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

    private int panelWidth() { return Math.max(1, Math.min(460, this.width - 16)); }
    private int contentWidth() { return Math.max(1, panelWidth() - 32); }
    private int contentLeft() { return (this.width - panelWidth()) / 2 + 12; }
    private String rowDetails(ModSyncPlan.PlannedMod mod) {
        return Component.translatable(isClient(mod) ? "peercraft.modsync.confirm.tag_client"
                : "peercraft.modsync.confirm.tag_required").getString()
                + " · " + humanSize(mod.entry().sizeBytes()) + " · " + sourceLabel(mod);
    }

    private int visibleRows() {
        int avail = (this.height - 96) - LIST_TOP;
        return Math.max(0, avail / ROW_HEIGHT);
    }

    /** Client-side rows get a fresh {@link Checkbox} each rebuild — the state lives in {@link #deselected}. */
    private void rebuildRows() {
        for (AbstractWidget w : rowWidgets) {
            this.removeWidget(w);
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
            SteampunkSettingsTheme.Toggle cb = new SteampunkSettingsTheme.Toggle(contentLeft(), rowY + 3, 18, 18,
                    Component.empty(), !deselected.contains(id), val -> {
                        if (val) deselected.remove(id); else deselected.add(id);
                    });
            cb.setNarrationLabel(Component.literal(m.entry().id()));
            this.addRenderableWidget(cb);
            rowWidgets.add(cb);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int total = visibleMods().size();
        int visible = visibleRows();
        if (scrollY != 0 && total > visible
                && mouseX >= contentLeft() && mouseX <= contentLeft() + contentWidth()
                && mouseY >= LIST_TOP && mouseY < LIST_TOP + visible * ROW_HEIGHT) {
            int next = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), total - visible));
            if (next != scroll) {
                scroll = next;
                rebuildRows();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean scrollByKey(int keyCode) {
        if (keyCode != 266 && keyCode != 267) return false;
        int max = Math.max(0, visibleMods().size() - visibleRows());
        scroll = Math.max(0, Math.min(max, scroll + (keyCode == 266 ? -1 : 1) * Math.max(1, visibleRows())));
        rebuildRows();
        return true;
    }
    //? if <1.21.9 {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return scrollByKey(keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
    }
    //?} else {
    /*@Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return scrollByKey(event.key()) || super.keyPressed(event);
    }*/
    //?}
    private Component hoveredDetails(int mouseX, int mouseY, List<ModSyncPlan.PlannedMod> mods) {
        int row = (mouseY - LIST_TOP) / ROW_HEIGHT;
        if (mouseX < contentLeft() || mouseX >= contentLeft() + contentWidth()
                || mouseY < LIST_TOP || row >= visibleRows() || row + scroll >= mods.size()) return null;
        ModSyncPlan.PlannedMod mod = mods.get(row + scroll);
        return Component.literal(rowName(mod.entry()) + "\n" + mod.entry().fileName() + "\n" + rowDetails(mod));
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

    private static String rowName(ModEntry e) {
        return e.version().isBlank() ? e.id() : e.id() + "  " + e.version();
    }

    private final long animationStart = System.nanoTime();

    //? if <26.1 {
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = panelWidth();
        SteampunkSettingsTheme.screenBackground(graphics, this.width, this.height,
                (this.width - panelWidth) / 2, 8, panelWidth, this.height - 16,
                (System.nanoTime() - this.animationStart) / 1_000_000L);
    }
    //?} else {
    /*@Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = panelWidth();
        SteampunkSettingsTheme.screenBackground(graphics, this.width, this.height,
                (this.width - panelWidth) / 2, 8, panelWidth, this.height - 16,
                (System.nanoTime() - this.animationStart) / 1_000_000L);
    }*/
    //?}

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(this.title.getString(), contentWidth()), centerX, 14, 0xFFE9DFCB);
        graphics.drawCenteredString(this.font,
                this.font.plainSubstrByWidth(Component.translatable("peercraft.modsync.confirm.intro2", allMods.size()).getString(), contentWidth()), centerX, 28, 0xFFA99D89);
        int warningY = 42;
        for (net.minecraft.util.FormattedCharSequence line : this.font.split(
                Component.translatable("peercraft.modsync.confirm.trust_reminder"), contentWidth())) {
            graphics.drawString(this.font, line, centerX - this.font.width(line) / 2, warningY, 0xFFE8AF50, false);
            warningY += 10;
        }

        List<ModSyncPlan.PlannedMod> vis = visibleMods();
        int rows = Math.min(visibleRows(), Math.max(0, vis.size() - scroll));
        for (int i = 0; i < rows; i++) {
            ModSyncPlan.PlannedMod m = vis.get(scroll + i);
            ModEntry e = m.entry();
            int textY = LIST_TOP + i * ROW_HEIGHT + 2;
            if (!isClient(m)) graphics.drawString(this.font, "—", contentLeft() + 3, textY + 5, 0xFFA99D89, false);
            int textX = contentLeft() + 24;
            int textWidth = Math.max(1, contentWidth() - 24);
            graphics.drawString(this.font, this.font.plainSubstrByWidth(rowName(e), textWidth), textX, textY, 0xFFE9DFCB, false);
            graphics.drawString(this.font, this.font.plainSubstrByWidth(rowDetails(m), textWidth), textX, textY + 11, 0xFFA99D89, false);
        }
        drawScrollbar(graphics, centerX, vis.size());
        Component details = hoveredDetails(mouseX, mouseY, vis);
        if (details != null) {
            //? if <1.21.6 {
            graphics.renderTooltip(this.font, details, mouseX, mouseY);
            //?} else {
            /*graphics.setTooltipForNextFrame(this.font, details, mouseX, mouseY);*/
            //?}
        }

        ModSyncPlan sel = plan.excluding(deselected);
        graphics.drawCenteredString(this.font,
                Component.translatable("peercraft.modsync.confirm.count_selected", sel.count(), plan.count()),
                centerX, this.height - 86, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("peercraft.modsync.confirm.total", humanSize(sel.totalBytes())),
                centerX, this.height - 72, 0xFFFFD966);
    }

    //? if <26.1 {
    private void drawScrollbar(GuiGraphics graphics, int centerX, int total) {
        int visible = visibleRows();
        if (visible <= 0 || total <= visible) {
            return;
        }
        int trackHeight = visible * ROW_HEIGHT;
        int left = contentLeft() + contentWidth() + 3;
        int right = left + 6;
        graphics.fill(left, LIST_TOP, right, LIST_TOP + trackHeight, 0xFF2C241B);
        int maxScroll = total - visible;
        int s = Math.max(0, Math.min(scroll, maxScroll));
        int thumbHeight = Math.max(16, trackHeight * visible / total);
        int thumbY = LIST_TOP + (trackHeight - thumbHeight) * s / maxScroll;
        graphics.fill(left, thumbY, right, thumbY + thumbHeight, 0xFFBB8B4B);
    }
    //?} else {
    /*    private void drawScrollbar(GuiGraphicsExtractor graphics, int centerX, int total) {
        int visible = visibleRows();
        if (visible <= 0 || total <= visible) {
            return;
        }
        int trackHeight = visible * ROW_HEIGHT;
        int left = contentLeft() + contentWidth() + 3;
        int right = left + 6;
        graphics.fill(left, LIST_TOP, right, LIST_TOP + trackHeight, 0xFF2C241B);
        int maxScroll = total - visible;
        int s = Math.max(0, Math.min(scroll, maxScroll));
        int thumbHeight = Math.max(16, trackHeight * visible / total);
        int thumbY = LIST_TOP + (trackHeight - thumbHeight) * s / maxScroll;
        graphics.fill(left, thumbY, right, thumbY + thumbHeight, 0xFFBB8B4B);
    }*/
    //?}
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        graphics.centeredText(this.font, this.font.plainSubstrByWidth(this.title.getString(), contentWidth()), centerX, 14, 0xFFE9DFCB);
        graphics.centeredText(this.font,
                this.font.plainSubstrByWidth(Component.translatable("peercraft.modsync.confirm.intro2", allMods.size()).getString(), contentWidth()), centerX, 28, 0xFFA99D89);
        int warningY = 42;
        for (net.minecraft.util.FormattedCharSequence line : this.font.split(
                Component.translatable("peercraft.modsync.confirm.trust_reminder"), contentWidth())) {
            graphics.text(this.font, line, centerX - this.font.width(line) / 2, warningY, 0xFFE8AF50, false);
            warningY += 10;
        }

        List<ModSyncPlan.PlannedMod> vis = visibleMods();
        int rows = Math.min(visibleRows(), Math.max(0, vis.size() - scroll));
        for (int i = 0; i < rows; i++) {
            ModSyncPlan.PlannedMod m = vis.get(scroll + i);
            ModEntry e = m.entry();
            int textY = LIST_TOP + i * ROW_HEIGHT + 2;
            if (!isClient(m)) graphics.text(this.font, "—", contentLeft() + 3, textY + 5, 0xFFA99D89, false);
            int textX = contentLeft() + 24;
            int textWidth = Math.max(1, contentWidth() - 24);
            graphics.text(this.font, this.font.plainSubstrByWidth(rowName(e), textWidth), textX, textY, 0xFFE9DFCB, false);
            graphics.text(this.font, this.font.plainSubstrByWidth(rowDetails(m), textWidth), textX, textY + 11, 0xFFA99D89, false);
        }
        drawScrollbar(graphics, centerX, vis.size());
        Component details = hoveredDetails(mouseX, mouseY, vis);
        if (details != null) graphics.setTooltipForNextFrame(this.font, details, mouseX, mouseY);

        ModSyncPlan sel = plan.excluding(deselected);
        graphics.centeredText(this.font,
                Component.translatable("peercraft.modsync.confirm.count_selected", sel.count(), plan.count()),
                centerX, this.height - 86, 0xFFFFFFFF);
        graphics.centeredText(this.font,
                Component.translatable("peercraft.modsync.confirm.total", humanSize(sel.totalBytes())),
                centerX, this.height - 72, 0xFFFFD966);
    }

    private void drawScrollbar(GuiGraphicsExtractor graphics, int centerX, int total) {
        int visible = visibleRows();
        if (visible <= 0 || total <= visible) {
            return;
        }
        int trackHeight = visible * ROW_HEIGHT;
        int left = contentLeft() + contentWidth() + 3;
        int right = left + 6;
        graphics.fill(left, LIST_TOP, right, LIST_TOP + trackHeight, 0xFF2C241B);
        int maxScroll = total - visible;
        int s = Math.max(0, Math.min(scroll, maxScroll));
        int thumbHeight = Math.max(16, trackHeight * visible / total);
        int thumbY = LIST_TOP + (trackHeight - thumbHeight) * s / maxScroll;
        graphics.fill(left, thumbY, right, thumbY + thumbHeight, 0xFFBB8B4B);
    }*/
    //?}
}
