package net.peercraft.client.gui;

// Forge 1.12.2 backport of src/main/.../client/gui/ModSyncConfirmScreen.java. Deltas:
//   * Screen -> GuiScreen; Component -> String (PeerCraftLang); this.font -> this.fontRenderer.
//   * Checkbox.builder(...) -> ToggleButton (the GuiCheckBox shim); addRenderableWidget /
//     removeWidget -> this.buttonList add/remove, tracked in rowWidgets.
//   * The filter Button -> CycleTextButton (self-relabelling), onChange sets `filter`.
//   * mouseScrolled(...) -> handleMouseInput() reading Mouse.getEventDWheel().
//   * render(GuiGraphics) -> drawScreen(...); graphics.fill(...) -> Gui.drawRect(...).
//   * String.isBlank() -> trim().isEmpty(); switch expression -> colon switch.
// Keep in sync with the original where the shared behaviour is unchanged.

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.peercraft.network.modsync.ModEntry;
import net.peercraft.network.modsync.ModSyncPlan;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The trust gate: before mod sync writes anything to disk, this lists every jar it's about to
 * fetch as a scrollable list. Client-side mods carry a checkbox so the player can leave them
 * out; mods needed to join the host's world have no checkbox. A filter button cycles all /
 * client-only / required. What the player unchecks is remembered ({@code ModSyncDeclinedStore}).
 */
public class ModSyncConfirmScreen extends PeerCraftDialogScreen {

    private int listTop;
    private static final int ROW_HEIGHT = 28;

    private enum ViewFilter {ALL, CLIENT_ONLY, REQUIRED}

    private final String titleText = PeerCraftLang.tr("peercraft.modsync.confirm.title");
    private final ModSyncPlan plan;
    private final List<ModSyncPlan.PlannedMod> allMods;
    /** Ids of client-side downloads and of removals the player has unchecked. */
    private final Set<String> deselected;
    private final Consumer<Set<String>> onAccept;
    private final Runnable onCancel;

    private ViewFilter filter = ViewFilter.ALL;
    private int scroll;
    private final List<GuiButton> rowWidgets = new ArrayList<>();

    public ModSyncConfirmScreen(ModSyncPlan plan,
                                Set<String> initiallyDeselected,
                                Consumer<Set<String>> onAccept,
                                Runnable onCancel) {
        super(PeerCraftLang.tr("peercraft.modsync.confirm.title"), 500, 440);
        this.plan = plan;
        this.onAccept = onAccept;
        this.onCancel = onCancel;

        this.allMods = new ArrayList<>(plan.mods());
        for (ModSyncPlan.Removal r : plan.removals()) {
            removalIds.add(r.id());
            this.allMods.add(ModSyncPlan.PlannedMod.p2p(new ModEntry(r.id(), r.version(), 0, new byte[0], r.fileName(), r.env(), "", "")));
        }
        this.allMods.sort(Comparator
                .comparingInt((ModSyncPlan.PlannedMod m) -> isRemoval(m) ? 2 : isClient(m) ? 1 : 0)
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

    /** Rows standing in for {@link ModSyncPlan#removals()}; nothing is downloaded for them. */
    private final Set<String> removalIds = new LinkedHashSet<>();

    private boolean isRemoval(ModSyncPlan.PlannedMod m) {
        return removalIds.contains(m.entry().id());
    }

    /** Client-side downloads and every removal can be unticked; join-required downloads cannot. */
    private boolean selectable(ModSyncPlan.PlannedMod m) {
        return isClient(m) || isRemoval(m);
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear(); this.rowWidgets.clear();
        int y = dialog.contentTop();
        this.buttonList.add(CycleTextButton.create(dialog.contentX(), y, dialog.contentWidth(), dialog.buttonHeight(),
                Arrays.asList(ViewFilter.values()), filter, this::filterLabel, value -> {
                    filter = value; scroll = 0; rebuildRows();
                }));
        listTop = y + dialog.buttonPitch() + (summaryLines().size() + warningLines().size()) * 12 + 4;
        dialogAction(PeerCraftLang.tr("peercraft.modsync.confirm.accept"), this::accept, true, 0, 2);
        dialogAction(PeerCraftLang.tr("peercraft.modsync.confirm.cancel"), onCancel, false, 1, 2);
        rebuildRows();
    }
    private List<String> warningLines() {
        return PeerCraftUi.wrap(this.fontRenderer, PeerCraftLang.tr("peercraft.modsync.confirm.trust_reminder"), Math.max(1, dialog.contentWidth() - 8));
    }
    private List<String> summaryLines() {
        int count = 0;
        for (ModSyncPlan.PlannedMod mod : allMods) {
            if (!isRemoval(mod) && mod.catalogStatus() != ModSyncPlan.CatalogStatus.PUBLISHED) count++;
        }
        return count == 0 ? java.util.Collections.emptyList()
                : PeerCraftUi.wrap(this.fontRenderer, PeerCraftLang.tr("peercraft.modsync.confirm.catalog_summary", count, plan.count()),
                        Math.max(1, dialog.contentWidth() - 8));
    }
    private int selectionInfoY() { return backY() - dialog.buttonPitch() - 28; }

    private void accept() {
        onAccept.accept(new LinkedHashSet<>(deselected));
    }

    private String filterLabel(ViewFilter f) {
        switch (f) {
            case CLIENT_ONLY:
                return PeerCraftLang.tr("peercraft.modsync.confirm.filter_client");
            case REQUIRED:
                return PeerCraftLang.tr("peercraft.modsync.confirm.filter_required");
            default:
                return PeerCraftLang.tr("peercraft.modsync.confirm.filter_all");
        }
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
        return Math.max(0, (selectionInfoY() - 6 - listTop) / ROW_HEIGHT);
    }

    /** Client-side rows get a fresh checkbox each rebuild — the state lives in {@link #deselected}. */
    private void rebuildRows() {
        for (GuiButton w : rowWidgets) {
            this.buttonList.remove(w);
        }
        rowWidgets.clear();

        List<ModSyncPlan.PlannedMod> vis = visibleMods();
        int maxScroll = Math.max(0, vis.size() - visibleRows());
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int centerX = this.width / 2;
        int rows = Math.min(visibleRows(), vis.size() - scroll);
        for (int i = 0; i < rows; i++) {
            ModSyncPlan.PlannedMod m = vis.get(scroll + i);
            if (!selectable(m)) {
                continue;
            }
            final String id = m.entry().id();
            int rowY = listTop + i * ROW_HEIGHT;
            ToggleButton cb = new ToggleButton(dialog.contentX(), rowY + 5, "", !deselected.contains(id),
                    val -> {
                        if (val) {
                            deselected.remove(id);
                        } else {
                            deselected.add(id);
                        }
                    });
            cb.width = 16; cb.height = 16;
            this.buttonList.add(cb);
            rowWidgets.add(cb);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof ToggleButton) {
            ((ToggleButton) button).fire();
        } else if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_NEXT || keyCode == Keyboard.KEY_PRIOR) {
            scroll += (keyCode == Keyboard.KEY_NEXT ? 1 : -1) * Math.max(1, visibleRows()); rebuildRows(); return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            onCancel.run();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        int x = Mouse.getEventX() * width / mc.displayWidth;
        int y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (wheel != 0 && x >= dialog.contentX() && x < dialog.left + dialog.width && y >= listTop && y < selectionInfoY() - 6) {
            scroll -= (int) Math.signum(wheel) * 3; rebuildRows();
        }
    }

    private static String humanSize(long bytes) {
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
        return PeerCraftLang.tr(m.source() == ModSyncPlan.Source.HTTP
                ? "peercraft.modsync.confirm.source_http"
                : "peercraft.modsync.confirm.source_p2p");
    }

    private String catalogLabel(ModSyncPlan.PlannedMod m) {
        switch (m.catalogStatus()) {
            case PUBLISHED: return PeerCraftLang.tr("peercraft.modsync.catalog.published");
            case NOT_FOUND: return PeerCraftLang.tr("peercraft.modsync.catalog.not_found");
            default: return PeerCraftLang.tr("peercraft.modsync.catalog.unavailable");
        }
    }

    private int catalogColor(ModSyncPlan.PlannedMod m) {
        if (isRemoval(m)) return PeerCraftUi.TEXT_ACCENT;
        switch (m.catalogStatus()) {
            case PUBLISHED: return PeerCraftUi.TEXT_SUCCESS;
            case NOT_FOUND: return PeerCraftUi.TEXT_ACCENT;
            default: return PeerCraftUi.TEXT_MUTED;
        }
    }

    private String countLine(ModSyncPlan selected) {
        String downloads = PeerCraftLang.tr("peercraft.modsync.confirm.count_selected", selected.count(), plan.count());
        if (plan.removals().isEmpty()) return downloads;
        return downloads + " · " + PeerCraftLang.tr("peercraft.modsync.confirm.remove_selected", selected.removals().size(), plan.removals().size());
    }

    private static String rowName(ModSyncPlan.PlannedMod mod) {
        ModEntry e = mod.entry();
        if (mod.replacement() != null) return e.id() + "  " + mod.replacement().version() + " → " + e.version();
        return e.version().trim().isEmpty() ? e.id() : e.id() + "  " + e.version();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        int y = dialog.contentTop() + dialog.buttonPitch();
        for (String line : summaryLines()) { this.drawCenteredString(this.fontRenderer, line, width / 2, y, PeerCraftUi.TEXT_ACCENT); y += 12; }
        for (String line : warningLines()) { this.drawCenteredString(this.fontRenderer, line, width / 2, y, PeerCraftUi.TEXT_ERROR); y += 12; }
        List<ModSyncPlan.PlannedMod> vis = visibleMods();
        int rows = Math.min(visibleRows(), Math.max(0, vis.size() - scroll));
        List<String> hovered = null;
        for (int i = 0; i < rows; i++) {
            ModSyncPlan.PlannedMod m = vis.get(scroll + i);
            ModEntry e = m.entry(); boolean client = isClient(m);
            int rowY = listTop + i * ROW_HEIGHT;
            int textX = dialog.contentX() + 24;
            int textWidth = Math.max(1, dialog.contentWidth() - 34);
            if (!selectable(m)) this.fontRenderer.drawStringWithShadow("—", dialog.contentX() + 3, rowY + 7, PeerCraftUi.TEXT_MUTED);
            this.fontRenderer.drawStringWithShadow(this.fontRenderer.trimStringToWidth(rowName(m), textWidth), textX, rowY + 2, PeerCraftUi.TEXT_TITLE);
            String tag = PeerCraftLang.tr(client ? "peercraft.modsync.confirm.tag_client" : "peercraft.modsync.confirm.tag_required");
            String details = isRemoval(m)
                    ? PeerCraftLang.tr("peercraft.modsync.operation.remove") + " · " + tag + " · " + PeerCraftLang.tr("peercraft.modsync.confirm.not_on_host")
                    : PeerCraftLang.tr(m.replacement() == null ? "peercraft.modsync.operation.add" : "peercraft.modsync.operation.replace") + " · " + catalogLabel(m) + " · " + tag + " · " + humanSize(e.sizeBytes()) + " · " + sourceLabel(m);
            this.fontRenderer.drawStringWithShadow(this.fontRenderer.trimStringToWidth(details, textWidth), textX, rowY + 14,
                    catalogColor(m));
            if (mouseX >= textX && mouseX < textX + textWidth && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                hovered = PeerCraftUi.wrap(this.fontRenderer, rowName(m) + "\n" + details + "\n"
                        + PeerCraftLang.tr(isRemoval(m) ? "peercraft.modsync.confirm.remove_explanation" : "peercraft.modsync.catalog.explanation"), dialog.contentWidth());
            }
        }
        drawScrollbar(vis.size());
        ModSyncPlan selected = plan.excluding(deselected);
        this.drawCenteredString(this.fontRenderer, this.fontRenderer.trimStringToWidth(countLine(selected), dialog.contentWidth()),
                width / 2, selectionInfoY(), PeerCraftUi.TEXT_TITLE);
        this.drawCenteredString(this.fontRenderer, this.fontRenderer.trimStringToWidth(PeerCraftLang.tr("peercraft.modsync.confirm.total", humanSize(selected.totalBytes())), dialog.contentWidth()),
                width / 2, selectionInfoY() + 12, PeerCraftUi.TEXT_ACCENT);
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (hovered != null) drawHoveringText(hovered, mouseX, mouseY);
    }

    private void drawScrollbar(int total) {
        int visible = visibleRows();
        if (visible <= 0 || total <= visible) return;
        int track = visible * ROW_HEIGHT, thumb = Math.max(8, track * visible / total);
        int y = listTop + (track - thumb) * scroll / (total - visible);
        drawRect(dialog.left + dialog.width - 7, listTop, dialog.left + dialog.width - 5, listTop + track, net.peercraft.client.theme.SteampunkPalette.BORDER);
        drawRect(dialog.left + dialog.width - 7, y, dialog.left + dialog.width - 5, y + thumb, net.peercraft.client.theme.SteampunkPalette.ACCENT);
    }
}
