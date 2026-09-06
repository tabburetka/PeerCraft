package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.config.PeerCraftSettings;
import net.peercraft.config.PeerCraftSettingsStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/**
 * In-game PeerCraft settings — a persisted, GUI-editable mirror of every {@code PeerCraftConfig}
 * launch flag. Saved to {@code config/peercraft/settings.json} and folded back into
 * {@code PeerCraftConfig} as an override layer (an explicit {@code -Dpeercraft.*} still wins).
 *
 * <p>The list has a normal section plus a collapsed <b>Для разработчиков</b> section
 * ({@code mode}, {@code modSync.autoAccept}, rendezvous address, ports) that can disable the
 * mod, break matchmaking or weaken the mod-sync trust prompt.
 *
 * <p>Reached from the gear glyph in {@link PeerCraftMultiplayerScreen}.
 */
public class PeerCraftSettingsScreen extends Screen {

    private static final int LIST_TOP = 40;
    private static final int ROWS_TOP = LIST_TOP + 38;
    private static final int ROW_PITCH = 26;
    private static final int CONTROL_W = 150;

    private static final int KIND_BOOL = 0;
    private static final int KIND_INT = 1;
    private static final int KIND_STRING = 2;
    private static final int KIND_MODE = 3;         // auto/client/host/disabled
    private static final int KIND_MODSYNC = 4;      // off/required/all

    private static final List<String> MODE_VALUES = Arrays.asList("auto", "client", "host", "disabled");
    private static final List<String> MODSYNC_VALUES = Arrays.asList("off", "required", "all");

    private final Screen lastScreen;
    private PeerCraftSettings settings;

    private final List<Row> rows = new ArrayList<>();
    private Checkbox developerToggle;
    private int scroll;

    private Component statusMessage = Component.empty();
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftSettingsScreen(Screen lastScreen) {
        super(Component.translatable("peercraft.gui.settings.title"));
        this.lastScreen = lastScreen;
    }

    private static final class Row {
        final String key;
        final int kind;
        final boolean developer;
        final boolean restart;
        final int min;
        final int max;
        final String labelKey;
        final String tooltipKey;
        String warnOverride;     // lang suffix for a red-warning row/tooltip (e.g. autoAccept); null otherwise
        AbstractWidget widget;
        int screenY = -1;

        Row(String key, int kind, boolean developer, boolean restart, int min, int max,
            String labelKey, String tooltipKey) {
            this.key = key;
            this.kind = kind;
            this.developer = developer;
            this.restart = restart;
            this.min = min;
            this.max = max;
            this.labelKey = labelKey;
            this.tooltipKey = tooltipKey;
        }
    }

    // Flag defaults, mirroring PeerCraftConfig's hardcoded fallbacks. Seeding widgets from
    // these (never from PeerCraftConfig itself) keeps a -D value out of the widgets, so Save
    // can never bake a launch-flag value into settings.json.
    private static String builtinDefault(String key) {
        switch (key) {
            case "mode": return "auto";
            case "proxyPort": return "25566";
            case "clientUdpPort": return "50002";
            case "hostUdpPort": return "50001";
            case "peerHost": return "127.0.0.1";
            case "peerPort": return "";
            case "internetPlay": return "false";
            case "rendezvousHost": return "91.146.31.165";
            case "rendezvousPort": return "51000";
            case "roomCode": return "";
            case "maxPlayers": return "4";
            case "modSync.host": return "all";
            case "modSync.client": return "all";
            case "modSync.autoAccept": return "false";
            case "modSync.reofferDeclined": return "false";
            case "modSync.maxTotalMb": return "512";
            case "modSync.maxModMb": return "256";
            default: return "";
        }
    }

    private static String envName(String key) {
        StringBuilder b = new StringBuilder("PEERCRAFT_");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '.') {
                b.append('_');
            } else if (Character.isUpperCase(c)) {
                b.append('_').append(c);
            } else {
                b.append(Character.toUpperCase(c));
            }
        }
        return b.toString();
    }

    private static boolean forcedByFlag(String key) {
        String prop = System.getProperty("peercraft." + key);
        if (prop != null && !prop.trim().isEmpty()) {
            return true;
        }
        String env = System.getenv(envName(key));
        return env != null && !env.trim().isEmpty();
    }

    @Override
    protected void init() {
        if (this.settings == null) {
            this.settings = PeerCraftSettingsStore.load();
        }
        this.rows.clear();

        // normal section
        addRow("modSync.client", KIND_MODSYNC, false, false, 0, 0, "modsync_client");
        addRow("modSync.host", KIND_MODSYNC, false, false, 0, 0, "modsync_host");
        addRow("modSync.reofferDeclined", KIND_BOOL, false, false, 0, 0, "reoffer_declined");
        addRow("modSync.maxTotalMb", KIND_INT, false, false, 1, 4096, "max_total_mb");
        addRow("modSync.maxModMb", KIND_INT, false, false, 1, 2048, "max_mod_mb");
        addRow("internetPlay", KIND_BOOL, false, false, 0, 0, "internet_play");
        addRow("maxPlayers", KIND_INT, false, false, 1, 32, "max_players");
        addRow("roomCode", KIND_STRING, false, false, 0, 0, "room_code");

        // developer section
        addRow("mode", KIND_MODE, true, true, 0, 0, "mode");
        Row autoAccept = addRow("modSync.autoAccept", KIND_BOOL, true, false, 0, 0, "autoaccept");
        autoAccept.warnOverride = "autoaccept_warning";
        addRow("rendezvousHost", KIND_STRING, true, true, 0, 0, "rendezvous_host");
        addRow("rendezvousPort", KIND_INT, true, true, 0, 65535, "rendezvous_port");
        addRow("proxyPort", KIND_INT, true, true, 0, 65535, "proxy_port");
        addRow("clientUdpPort", KIND_INT, true, true, 0, 65535, "client_udp_port");
        addRow("hostUdpPort", KIND_INT, true, true, 0, 65535, "host_udp_port");
        addRow("peerHost", KIND_STRING, true, true, 0, 0, "peer_host");
        addRow("peerPort", KIND_INT, true, true, 0, 65535, "peer_port");

        for (Row row : this.rows) {
            row.widget = buildWidget(row);
            if (forcedByFlag(row.key)) {
                row.widget.active = false;
                row.widget.setTooltip(Tooltip.create(Component.translatable("peercraft.gui.settings.forced_by_flag")));
            } else {
                row.widget.setTooltip(Tooltip.create(Component.translatable(
                        "peercraft.gui.settings." + (row.warnOverride != null ? row.warnOverride : row.tooltipKey + "_desc"))));
            }
            this.addRenderableWidget(row.widget);
        }

        this.developerToggle = Checkbox.builder(Component.translatable("peercraft.gui.settings.developer_toggle"), this.font)
                .pos(this.width / 2 - CONTROL_W, LIST_TOP)
                .selected(this.settings.showDeveloperSection)
                .onValueChange((box, value) -> {
                    this.settings.showDeveloperSection = value;
                    this.scroll = 0;
                    relayout();
                })
                .build();
        this.addRenderableWidget(this.developerToggle);

        int by = this.height - 52;
        int cx = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.settings.save"), b -> onSave())
                .bounds(cx - 154, by, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.settings.reset"), b -> onReset())
                .bounds(cx - 50, by, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.settings.cancel"),
                        b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                .bounds(cx + 54, by, 100, 20).build());

        relayout();
    }

    private Row addRow(String key, int kind, boolean developer, boolean restart, int min, int max, String labelKey) {
        Row row = new Row(key, kind, developer, restart, min, max, labelKey, labelKey);
        this.rows.add(row);
        return row;
    }

    private String seed(String key) {
        String saved = this.settings.get(key);
        return (saved != null && !saved.trim().isEmpty()) ? saved.trim() : builtinDefault(key);
    }

    private AbstractWidget buildWidget(Row row) {
        int x = this.width / 2 + 10;
        int y = ROWS_TOP;
        String current = seed(row.key);
        switch (row.kind) {
            case KIND_BOOL:
                return Checkbox.builder(Component.empty(), this.font)
                        .pos(x, y)
                        .selected("true".equalsIgnoreCase(current))
                        .build();
            case KIND_MODE:
                return makeCycle(MODE_VALUES, current, x, y, v -> Component.translatable("peercraft.gui.settings.mode." + v));
            case KIND_MODSYNC:
                return makeCycle(MODSYNC_VALUES, current, x, y, v -> Component.translatable("peercraft.gui.settings.modsync_mode." + v));
            case KIND_INT:
            case KIND_STRING:
            default:
                EditBox box = new EditBox(this.font, x, y, CONTROL_W, 18, Component.empty());
                box.setMaxLength(row.kind == KIND_INT ? 6 : 128);
                box.setValue(current);
                return box;
        }
    }

    private CycleButton<String> makeCycle(List<String> values, String initial, int x, int y, Function<String, Component> labels) {
        String start = values.contains(initial) ? initial : values.get(values.size() - 1);
        // CycleButton.builder lost the no-initial-value overload in 1.21.11.
        //? if <1.21.11 {
        CycleButton.Builder<String> builder = CycleButton.<String>builder(labels).withInitialValue(start);
        //?} else {
        /*CycleButton.Builder<String> builder = CycleButton.<String>builder(labels, start);*/
        //?}
        return builder.withValues(values).displayOnlyValue()
                .create(x, y, CONTROL_W, 18, Component.empty(), (button, value) -> { });
    }

    private List<Row> visibleRows() {
        List<Row> out = new ArrayList<>();
        for (Row row : this.rows) {
            if (!row.developer || this.settings.showDeveloperSection) {
                out.add(row);
            }
        }
        return out;
    }

    private int rowsShown() {
        int band = (this.height - 66) - ROWS_TOP;
        return Math.max(1, band / ROW_PITCH);
    }

    private void relayout() {
        List<Row> visible = visibleRows();
        int maxScroll = Math.max(0, visible.size() - rowsShown());
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll));

        int shown = rowsShown();
        for (Row row : this.rows) {
            row.screenY = -1;
            if (row.widget != null) {
                row.widget.visible = false;
            }
        }
        for (int i = 0; i < shown && this.scroll + i < visible.size(); i++) {
            Row row = visible.get(this.scroll + i);
            int y = ROWS_TOP + i * ROW_PITCH;
            row.screenY = y;
            row.widget.visible = true;
            row.widget.setX(this.width / 2 + 10);
            row.widget.setY(y);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int total = visibleRows().size();
        int shown = rowsShown();
        if (scrollY != 0 && total > shown && mouseY >= ROWS_TOP && mouseY < ROWS_TOP + shown * ROW_PITCH) {
            int next = Math.max(0, Math.min(this.scroll - (int) Math.signum(scrollY), total - shown));
            if (next != this.scroll) {
                this.scroll = next;
                relayout();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        PeerCraftUi.setScreen(this.minecraft, this.lastScreen);
    }

    private void onReset() {
        PeerCraftSettingsStore.clear();
        PeerCraftConfig.applyOverrides(new LinkedHashMap<>());
        this.settings = new PeerCraftSettings();
        this.scroll = 0;
        rebuildWidgets();
        this.statusMessage = Component.translatable("peercraft.gui.settings.reset_done");
        this.statusColor = PeerCraftUi.TEXT_SUCCESS;
    }

    private void onSave() {
        for (Row row : this.rows) {
            if (row.kind != KIND_INT || !(row.widget instanceof EditBox)) {
                continue;
            }
            String v = ((EditBox) row.widget).getValue().trim();
            if (v.isEmpty()) {
                continue;
            }
            int parsed;
            try {
                parsed = Integer.parseInt(v);
            } catch (NumberFormatException e) {
                failValidation(row);
                return;
            }
            if (parsed < row.min || parsed > row.max) {
                failValidation(row);
                return;
            }
        }

        for (Row row : this.rows) {
            String value = readWidget(row);
            String def = builtinDefault(row.key);
            if (value == null || value.trim().isEmpty() || value.equals(def)) {
                this.settings.set(row.key, null);
            } else {
                this.settings.set(row.key, value.trim());
            }
        }
        this.settings.showDeveloperSection = this.developerToggle != null && this.developerToggle.selected();

        PeerCraftSettingsStore.save(this.settings);
        PeerCraftConfig.applyOverrides(this.settings.toOverrideMap());
        PeerCraftUi.setScreen(this.minecraft, this.lastScreen);
    }

    private void failValidation(Row row) {
        this.statusMessage = Component.translatable("peercraft.gui.settings.invalid_number",
                Component.translatable("peercraft.gui.settings." + row.labelKey));
        this.statusColor = PeerCraftUi.TEXT_ERROR;
    }

    @SuppressWarnings("unchecked")
    private String readWidget(Row row) {
        AbstractWidget w = row.widget;
        if (w instanceof Checkbox) {
            return Boolean.toString(((Checkbox) w).selected());
        }
        if (w instanceof CycleButton) {
            Object v = ((CycleButton<String>) w).getValue();
            return v == null ? null : v.toString();
        }
        if (w instanceof EditBox) {
            return ((EditBox) w).getValue();
        }
        return null;
    }

    // 26.1 renamed GuiGraphics -> GuiGraphicsExtractor and drawString/drawCenteredString ->
    // text/centeredText; the background is painted by Screen itself since 1.21.6.
    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, cx, 16, PeerCraftUi.TEXT_TITLE);
        for (Row row : this.rows) {
            if (row.screenY < 0) {
                continue;
            }
            int color = !row.widget.active ? PeerCraftUi.TEXT_MUTED
                    : (row.warnOverride != null ? PeerCraftUi.TEXT_ERROR : PeerCraftUi.TEXT_TITLE);
            graphics.drawString(this.font, rowLabel(row), cx - CONTROL_W, row.screenY + 5, color, false);
        }
        if (this.settings != null && this.settings.showDeveloperSection) {
            graphics.drawString(this.font, Component.translatable("peercraft.gui.settings.developer_warning"),
                    cx - CONTROL_W, LIST_TOP + 16, PeerCraftUi.TEXT_ERROR, false);
        }
        graphics.drawCenteredString(this.font, this.statusMessage, cx, this.height - 68, this.statusColor);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        graphics.centeredText(this.font, this.title, cx, 16, PeerCraftUi.TEXT_TITLE);
        for (Row row : this.rows) {
            if (row.screenY < 0) {
                continue;
            }
            int color = !row.widget.active ? PeerCraftUi.TEXT_MUTED
                    : (row.warnOverride != null ? PeerCraftUi.TEXT_ERROR : PeerCraftUi.TEXT_TITLE);
            graphics.text(this.font, rowLabel(row), cx - CONTROL_W, row.screenY + 5, color, false);
        }
        if (this.settings != null && this.settings.showDeveloperSection) {
            graphics.text(this.font, Component.translatable("peercraft.gui.settings.developer_warning"),
                    cx - CONTROL_W, LIST_TOP + 16, PeerCraftUi.TEXT_ERROR, false);
        }
        graphics.centeredText(this.font, this.statusMessage, cx, this.height - 68, this.statusColor);
    }*/
    //?}

    private Component rowLabel(Row row) {
        Component label = Component.translatable("peercraft.gui.settings." + row.labelKey);
        if (row.restart) {
            return Component.empty().append(label).append(" ")
                    .append(Component.translatable("peercraft.gui.settings.restart_hint"));
        }
        return label;
    }
}
