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

    private static final int LIST_TOP = 82;
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
        this.filterButton = Button.builder(filterLabel(), b -> cycleFilter())
                .bounds(centerX + 62, 54, 150, 20).build();
        this.addRenderableWidget(this.filterButton);
        rebuildRows();

        int y = this.height - 52;
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.modsync.confirm.accept"), b -> accept())
                .bounds(centerX - 154, y, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.modsync.confirm.cancel"), b -> onCancel.run())
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

    private int visibleRows() {
        int avail = (this.height - 96) - LIST_TOP;
        return Math.max(0, Math.min(MAX_ROWS_SHOWN, avail / ROW_HEIGHT));
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
            Checkbox cb = Checkbox.builder(Component.empty(), this.font)
                    .pos(centerX - 210, rowY + 1)
                    .selected(!deselected.contains(id))
                    .onValueChange((box, val) -> {
                        if (val) {
                            deselected.remove(id);
                        } else {
                            deselected.add(id);
                        }
                    })
                    .build();
            this.addRenderableWidget(cb);
            rowWidgets.add(cb);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int total = visibleMods().size();
        int visible = visibleRows();
        if (scrollY != 0 && total > visible
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

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, 14, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("peercraft.modsync.confirm.intro2", allMods.size()), centerX, 28, 0xFFAAAAAA);
        graphics.drawCenteredString(this.font,
                Component.translatable("peercraft.modsync.confirm.trust_reminder"), centerX, 42, 0xFFFF5555);

        List<ModSyncPlan.PlannedMod> vis = visibleMods();
        int rows = Math.min(visibleRows(), Math.max(0, vis.size() - scroll));
        for (int i = 0; i < rows; i++) {
            ModSyncPlan.PlannedMod m = vis.get(scroll + i);
            ModEntry e = m.entry();
            int textY = LIST_TOP + i * ROW_HEIGHT + 6;
            boolean client = isClient(m);
            if (!client) {
                graphics.drawString(this.font, "—", centerX - 204, textY, 0xFF777777, false);
            }
            graphics.drawString(this.font, rowName(e), centerX - 186, textY, 0xFFFFFFFF, false);
            graphics.drawString(this.font, Component.translatable(client
                            ? "peercraft.modsync.confirm.tag_client"
                            : "peercraft.modsync.confirm.tag_required"),
                    centerX + 20, textY, client ? 0xFFAAAAAA : 0xFFFFD966, false);
            graphics.drawString(this.font, humanSize(e.sizeBytes()) + ", " + sourceLabel(m),
                    centerX + 96, textY, 0xFFAAAAAA, false);
        }
        drawScrollbar(graphics, centerX, vis.size());

        ModSyncPlan sel = plan.excluding(deselected);
        graphics.drawCenteredString(this.font,
                Component.translatable("peercraft.modsync.confirm.count_selected", sel.count(), plan.count()),
                centerX, this.height - 86, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("peercraft.modsync.confirm.total", humanSize(sel.totalBytes())),
                centerX, this.height - 72, 0xFFFFD966);
    }

    private void drawScrollbar(GuiGraphics graphics, int centerX, int total) {
        int visible = visibleRows();
        if (visible <= 0 || total <= visible) {
            return;
        }
        int trackHeight = visible * ROW_HEIGHT;
        int left = centerX + 214;
        int right = left + 6;
        graphics.fill(left, LIST_TOP, right, LIST_TOP + trackHeight, 0xFF000000);
        int maxScroll = total - visible;
        int s = Math.max(0, Math.min(scroll, maxScroll));
        int thumbHeight = Math.max(16, trackHeight * visible / total);
        int thumbY = LIST_TOP + (trackHeight - thumbHeight) * s / maxScroll;
        graphics.fill(left, thumbY, right, thumbY + thumbHeight, 0xFFA0A0A0);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        graphics.centeredText(this.font, this.title, centerX, 14, 0xFFFFFFFF);
        graphics.centeredText(this.font,
                Component.translatable("peercraft.modsync.confirm.intro2", allMods.size()), centerX, 28, 0xFFAAAAAA);
        graphics.centeredText(this.font,
                Component.translatable("peercraft.modsync.confirm.trust_reminder"), centerX, 42, 0xFFFF5555);

        List<ModSyncPlan.PlannedMod> vis = visibleMods();
        int rows = Math.min(visibleRows(), Math.max(0, vis.size() - scroll));
        for (int i = 0; i < rows; i++) {
            ModSyncPlan.PlannedMod m = vis.get(scroll + i);
            ModEntry e = m.entry();
            int textY = LIST_TOP + i * ROW_HEIGHT + 6;
            boolean client = isClient(m);
            if (!client) {
                graphics.text(this.font, "—", centerX - 204, textY, 0xFF777777, false);
            }
            graphics.text(this.font, rowName(e), centerX - 186, textY, 0xFFFFFFFF, false);
            graphics.text(this.font, Component.translatable(client
                            ? "peercraft.modsync.confirm.tag_client"
                            : "peercraft.modsync.confirm.tag_required"),
                    centerX + 20, textY, client ? 0xFFAAAAAA : 0xFFFFD966, false);
            graphics.text(this.font, humanSize(e.sizeBytes()) + ", " + sourceLabel(m),
                    centerX + 96, textY, 0xFFAAAAAA, false);
        }
        drawScrollbar(graphics, centerX, vis.size());

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
        int left = centerX + 214;
        int right = left + 6;
        graphics.fill(left, LIST_TOP, right, LIST_TOP + trackHeight, 0xFF000000);
        int maxScroll = total - visible;
        int s = Math.max(0, Math.min(scroll, maxScroll));
        int thumbHeight = Math.max(16, trackHeight * visible / total);
        int thumbY = LIST_TOP + (trackHeight - thumbHeight) * s / maxScroll;
        graphics.fill(left, thumbY, right, thumbY + thumbHeight, 0xFFA0A0A0);
    }*/
    //?}
}
