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
public class ModSyncConfirmScreen extends GuiScreen {

    private static final int LIST_TOP = 68;
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_ROWS_SHOWN = 8;

    private enum ViewFilter {ALL, CLIENT_ONLY, REQUIRED}

    private final String titleText = PeerCraftLang.tr("peercraft.modsync.confirm.title");
    private final ModSyncPlan plan;
    private final List<ModSyncPlan.PlannedMod> allMods;
    /** Ids of client-side mods the player has unchecked. Only CLIENT-env ids ever land here. */
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
    public void initGui() {
        this.buttonList.clear();
        this.rowWidgets.clear();
        int centerX = this.width / 2;

        this.buttonList.add(CycleTextButton.create(centerX + 62, 42, 150, 20,
                Arrays.asList(ViewFilter.values()), filter, this::filterLabel,
                v -> {
                    filter = v;
                    scroll = 0;
                    rebuildRows();
                }));
        rebuildRows();

        int y = this.height - 52;
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.modsync.confirm.accept"), this::accept)
                .bounds(centerX - 154, y, 150, 20).build());
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.modsync.confirm.cancel"), onCancel)
                .bounds(centerX + 4, y, 150, 20).build());
    }

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
        int avail = (this.height - 96) - LIST_TOP;
        return Math.max(0, Math.min(MAX_ROWS_SHOWN, avail / ROW_HEIGHT));
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
            if (!isClient(m)) {
                continue;
            }
            final String id = m.entry().id();
            int rowY = LIST_TOP + i * ROW_HEIGHT;
            ToggleButton cb = new ToggleButton(centerX - 210, rowY + 1, "", !deselected.contains(id),
                    val -> {
                        if (val) {
                            deselected.remove(id);
                        } else {
                            deselected.add(id);
                        }
                    });
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
        if (keyCode == Keyboard.KEY_ESCAPE) {
            onCancel.run();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    // 1.12.2 has no mouseScrolled callback — the wheel arrives here as a raw LWJGL event delta.
    @Override
    public void handleMouseInput() throws IOException {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int total = visibleMods().size();
            int visible = visibleRows();
            if (total > visible) {
                int next = Math.max(0, Math.min(scroll + (wheel > 0 ? -1 : 1), total - visible));
                if (next != scroll) {
                    scroll = next;
                    rebuildRows();
                }
            }
        }
        super.handleMouseInput();
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

    private static String rowName(ModEntry e) {
        return e.version().trim().isEmpty() ? e.id() : e.id() + "  " + e.version();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        int centerX = this.width / 2;
        this.drawCenteredString(this.fontRenderer, this.titleText, centerX, 14, 0xFFFFFFFF);
        this.drawCenteredString(this.fontRenderer,
                PeerCraftLang.tr("peercraft.modsync.confirm.intro2", allMods.size()), centerX, 28, 0xFFAAAAAA);

        List<ModSyncPlan.PlannedMod> vis = visibleMods();
        int rows = Math.min(visibleRows(), Math.max(0, vis.size() - scroll));
        for (int i = 0; i < rows; i++) {
            ModSyncPlan.PlannedMod m = vis.get(scroll + i);
            ModEntry e = m.entry();
            int textY = LIST_TOP + i * ROW_HEIGHT + 6;
            boolean client = isClient(m);
            if (!client) {
                this.fontRenderer.drawString("—", centerX - 204, textY, 0xFF777777);
            }
            this.fontRenderer.drawString(rowName(e), centerX - 186, textY, 0xFFFFFFFF);
            this.fontRenderer.drawString(PeerCraftLang.tr(client
                            ? "peercraft.modsync.confirm.tag_client"
                            : "peercraft.modsync.confirm.tag_required"),
                    centerX + 20, textY, client ? 0xFFAAAAAA : 0xFFFFD966);
            this.fontRenderer.drawString(humanSize(e.sizeBytes()) + ", " + sourceLabel(m),
                    centerX + 96, textY, 0xFFAAAAAA);
        }
        drawScrollbar(centerX, vis.size());

        ModSyncPlan sel = plan.excluding(deselected);
        this.drawCenteredString(this.fontRenderer,
                PeerCraftLang.tr("peercraft.modsync.confirm.count_selected", sel.count(), plan.count()),
                centerX, this.height - 86, 0xFFFFFFFF);
        this.drawCenteredString(this.fontRenderer,
                PeerCraftLang.tr("peercraft.modsync.confirm.total", humanSize(sel.totalBytes())),
                centerX, this.height - 72, 0xFFFFD966);
    }

    private void drawScrollbar(int centerX, int total) {
        int visible = visibleRows();
        if (visible <= 0 || total <= visible) {
            return;
        }
        int trackHeight = visible * ROW_HEIGHT;
        int left = centerX + 214;
        int right = left + 6;
        drawRect(left, LIST_TOP, right, LIST_TOP + trackHeight, 0xFF000000);
        int maxScroll = total - visible;
        int s = Math.max(0, Math.min(scroll, maxScroll));
        int thumbHeight = Math.max(16, trackHeight * visible / total);
        int thumbY = LIST_TOP + (trackHeight - thumbHeight) * s / maxScroll;
        drawRect(left, thumbY, right, thumbY + thumbHeight, 0xFFA0A0A0);
    }
}
