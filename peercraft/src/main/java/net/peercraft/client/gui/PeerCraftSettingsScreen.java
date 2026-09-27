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
//? if =1.21.1 {
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;
//?}
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
    private static final int LABEL_X = 20;

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
    private AbstractWidget developerToggle;
    private int scroll;

    //? if =1.21.1 {
    private static final int THEMED_ROW_PITCH = 30;
    private final long animationStart = System.nanoTime();
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int listBottom;
    private int controlWidth;
    private boolean draggingScrollbar;
    private int scrollbarGrabOffset;
    private AbstractWidget pendingValidationFocus;
    private final LinkedHashMap<String, String> draftValues = new LinkedHashMap<>();
    //?}

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
        //? if =1.21.1 {
        Button decrement;
        Button increment;
        //?}

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
            case "maxPlayers": return "4";
            case "modSync.host": return "all";
            case "modSync.client": return "all";
            case "modSync.autoAccept": return "false";
            case "handoff": return "true";
            case "handoff.declineSuccessor": return "false";
            case "handoff.confirmBeforeOffer": return "true";
            case "handoff.chatNotify": return "true";
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
        //? if =1.21.1 {
        captureDraft();
        this.panelWidth = Math.min(Math.max(0, this.width - 16), Math.max(300, Math.min(420, this.width * 45 / 100)));
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.panelTop = Math.min(10, Math.max(0, this.height / 24));
        this.panelHeight = this.height - this.panelTop * 2;
        this.controlWidth = Math.min(140, Math.max(92, this.panelWidth * 36 / 100));
        this.listTop = this.panelTop + 62;
        this.listBottom = this.panelTop + this.panelHeight - 56;
        this.draggingScrollbar = false;
        //?}
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
        //? if =1.21.1
        addRow("maxPlayers", KIND_INT, false, false, 1, 32, "max_players");
        //? if !=1.21.1
        /*addRow("maxPlayers", KIND_INT, false, false, 1, 8, "max_players");*/
        addRow("handoff", KIND_BOOL, false, false, 0, 0, "handoff");
        addRow("handoff.declineSuccessor", KIND_BOOL, false, false, 0, 0, "handoff_decline_successor");
        addRow("handoff.confirmBeforeOffer", KIND_BOOL, false, false, 0, 0, "handoff_confirm_before_offer");
        addRow("handoff.chatNotify", KIND_BOOL, false, false, 0, 0, "handoff_chat_notify");

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
            //? if =1.21.1 {
            Component label = Component.translatable("peercraft.gui.settings." + row.labelKey);
            if (row.widget instanceof SteampunkSettingsTheme.Toggle toggle) {
                toggle.setNarrationLabel(label);
            } else if (row.widget instanceof SteampunkSettingsTheme.Choice choice) {
                choice.setNarrationLabel(label);
            }
            //?}
            if (forcedByFlag(row.key)) {
                row.widget.active = false;
                row.widget.setTooltip(Tooltip.create(Component.translatable("peercraft.gui.settings.forced_by_flag")));
            } else {
                //? if =1.21.1 {
                if ("maxPlayers".equals(row.key)) {
                    row.widget.setTooltip(Tooltip.create(Component.translatable("peercraft.gui.settings.max_players_desc_range", row.min, row.max)));
                } else {
                //?}
                row.widget.setTooltip(Tooltip.create(Component.translatable(
                        "peercraft.gui.settings." + (row.warnOverride != null ? row.warnOverride : row.tooltipKey + "_desc"))));
                //? if =1.21.1
                }
            }
            this.addRenderableWidget(row.widget);
            //? if =1.21.1 {
            if ("maxPlayers".equals(row.key) && row.widget instanceof EditBox box) {
                row.decrement = SteampunkSettingsTheme.action(controlX(), 0, 20, 24, Component.literal("−"),
                        button -> adjustPlayerCount(row, -1), false,
                        Component.translatable("peercraft.gui.settings.decrease", label));
                row.increment = SteampunkSettingsTheme.action(controlX() + this.controlWidth - 20, 0, 20, 24,
                        Component.literal("+"), button -> adjustPlayerCount(row, 1), false,
                        Component.translatable("peercraft.gui.settings.increase", label));
                row.decrement.setTooltip(row.widget.getTooltip());
                row.increment.setTooltip(row.widget.getTooltip());
                this.addRenderableWidget(row.decrement);
                this.addRenderableWidget(row.increment);
                box.setResponder(value -> refreshPlayerCountButtons(row));
                refreshPlayerCountButtons(row);
            }
            //?}
        }

        //? if =1.21.1 {
        this.developerToggle = new SteampunkSettingsTheme.Toggle(this.panelLeft + 12, this.panelTop + 40,
                this.panelWidth - 24, 18, Component.translatable("peercraft.gui.settings.developer_toggle"),
                this.settings.showDeveloperSection, value -> {
                    this.settings.showDeveloperSection = value;
                    this.scroll = 0;
                    relayout();
                });
        this.developerToggle.setTooltip(Tooltip.create(Component.translatable("peercraft.gui.settings.developer_warning")));
        //?} else {
        /*this.developerToggle = Checkbox.builder(Component.translatable("peercraft.gui.settings.developer_toggle"), this.font)
                .pos(LABEL_X, LIST_TOP)
                .selected(this.settings.showDeveloperSection)
                .onValueChange((box, value) -> {
                    this.settings.showDeveloperSection = value;
                    this.scroll = 0;
                    relayout();
                })
                .build();*/
        //?}
        this.addRenderableWidget(this.developerToggle);

        //? if =1.21.1 {
        int by = this.panelTop + this.panelHeight - 32;
        int available = this.panelWidth - 24;
        int secondaryWidth = (available - 12) * 29 / 100;
        int primaryWidth = available - 12 - secondaryWidth * 2;
        int buttonX = this.panelLeft + 12;
        this.addRenderableWidget(SteampunkSettingsTheme.action(buttonX, by, primaryWidth, 22,
                Component.translatable("peercraft.gui.settings.save"), b -> onSave(), true));
        buttonX += primaryWidth + 6;
        this.addRenderableWidget(SteampunkSettingsTheme.action(buttonX, by, secondaryWidth, 22,
                Component.translatable("peercraft.gui.settings.reset"), b -> onReset(), false));
        buttonX += secondaryWidth + 6;
        this.addRenderableWidget(SteampunkSettingsTheme.action(buttonX, by, secondaryWidth, 22,
                Component.translatable("peercraft.gui.settings.cancel"), b -> onClose(), false));
        //?} else {
        /*int by = this.height - 52;
        int cx = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.settings.save"), b -> onSave())
                .bounds(cx - 154, by, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.settings.reset"), b -> onReset())
                .bounds(cx - 50, by, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.settings.cancel"),
                        b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                .bounds(cx + 54, by, 100, 20).build());*/
        //?}

        relayout();
    }

    private Row addRow(String key, int kind, boolean developer, boolean restart, int min, int max, String labelKey) {
        Row row = new Row(key, kind, developer, restart, min, max, labelKey, labelKey);
        this.rows.add(row);
        return row;
    }

    // What to show in a row's widget: the player's saved value, else whatever a launch flag /
    // env var / baked default is currently forcing (so e.g. a -Dpeercraft.rendezvousHost shows
    // its real address instead of a blank box), else the hardcoded default.
    private String seed(String key) {
        //? if =1.21.1 {
        if (this.draftValues.containsKey(key)) {
            return this.draftValues.get(key);
        }
        //?}
        String saved = this.settings.get(key);
        if (saved != null && !saved.trim().isEmpty()) {
            return saved.trim();
        }
        String baseline = PeerCraftConfig.baselineValue(key);
        return !baseline.isEmpty() ? baseline : builtinDefault(key);
    }

    // Controls stay inside the panel; labels wrap separately within their available space.
    private int controlX() {
        //? if =1.21.1
        return this.panelLeft + this.panelWidth - 26 - this.controlWidth;
        //? if !=1.21.1
        /*return Math.max(this.width / 2 + 4, this.width - 20 - CONTROL_W);*/
    }

    private int labelMaxWidth() {
        //? if =1.21.1
        return Math.max(40, controlX() - 8 - (this.panelLeft + 20));
        //? if !=1.21.1
        /*return Math.max(60, controlX() - 12 - LABEL_X);*/
    }

    private String clip(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - this.font.width("…"))) + "…";
    }

    private AbstractWidget buildWidget(Row row) {
        int x = controlX();
        int y = ROWS_TOP;
        String current = seed(row.key);
        //? if =1.21.1 {
        Component label = Component.translatable("peercraft.gui.settings." + row.labelKey);
        switch (row.kind) {
            case KIND_BOOL:
                return new SteampunkSettingsTheme.Toggle(x, y, this.controlWidth, 24, Component.empty(),
                        "true".equalsIgnoreCase(current), value -> { });
            case KIND_MODE:
                return new SteampunkSettingsTheme.Choice(x, y, this.controlWidth, 24, MODE_VALUES, current,
                        value -> Component.translatable("peercraft.gui.settings.mode." + value));
            case KIND_MODSYNC:
                return new SteampunkSettingsTheme.Choice(x, y, this.controlWidth, 24, MODSYNC_VALUES, current,
                        value -> Component.translatable("peercraft.gui.settings.modsync_mode." + value));
            default:
                boolean stepper = "maxPlayers".equals(row.key);
                EditBox box = new SteampunkSettingsTheme.Field(this.font, x + (stepper ? 24 : 0), y,
                        this.controlWidth - (stepper ? 48 : 0), 24, label);
                box.setMaxLength(row.kind == KIND_INT ? 6 : 128);
                box.setValue(current);
                return box;
        }
        //?} else {
        /*switch (row.kind) {
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
        }*/
        //?}
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
        //? if =1.21.1
        return Math.max(1, (this.listBottom - this.listTop) / THEMED_ROW_PITCH);
        //? if !=1.21.1 {
        /*int band = (this.height - 66) - ROWS_TOP;
        return Math.max(1, band / ROW_PITCH);*/
        //?}
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
            //? if =1.21.1 {
            if (row.decrement != null) {
                row.decrement.visible = false;
                row.increment.visible = false;
            }
            //?}
        }
        for (int i = 0; i < shown && this.scroll + i < visible.size(); i++) {
            Row row = visible.get(this.scroll + i);
            //? if =1.21.1
            int y = this.listTop + i * THEMED_ROW_PITCH;
            //? if !=1.21.1
            /*int y = ROWS_TOP + i * ROW_PITCH;*/
            row.screenY = y;
            row.widget.visible = true;
            //? if =1.21.1
            row.widget.setX(controlX() + (row.decrement != null ? 24 : 0));
            //? if !=1.21.1
            /*row.widget.setX(controlX());*/
            //? if =1.21.1
            row.widget.setY(y + (THEMED_ROW_PITCH - row.widget.getHeight()) / 2);
            //? if !=1.21.1
            /*row.widget.setY(y);*/
            //? if =1.21.1 {
            if (row.decrement != null) {
                row.decrement.visible = true;
                row.increment.visible = true;
                row.decrement.setX(controlX());
                row.increment.setX(controlX() + this.controlWidth - 20);
                row.decrement.setY(row.widget.getY());
                row.increment.setY(row.widget.getY());
            }
            //?}
        }
        //? if =1.21.1 {
        for (Row row : this.rows) {
            if (!row.widget.visible && (row.widget == getFocused()
                    || row.decrement != null && (row.decrement == getFocused() || row.increment == getFocused()))) {
                setFocused(null);
            }
        }
        //?}
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        //? if =1.21.1 {
        if (mouseX >= this.panelLeft + 10 && mouseX < this.panelLeft + this.panelWidth - 10
                && mouseY >= this.listTop && mouseY < this.listBottom && scrollY != 0) {
            scrollTo(this.scroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        //?} else {
        /*int total = visibleRows().size();
        int shown = rowsShown();
        if (scrollY != 0 && total > shown && mouseY >= ROWS_TOP && mouseY < ROWS_TOP + shown * ROW_PITCH) {
            int next = Math.max(0, Math.min(this.scroll - (int) Math.signum(scrollY), total - shown));
            if (next != this.scroll) {
                this.scroll = next;
                relayout();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);*/
        //?}
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
        //? if =1.21.1 {
        this.rows.clear();
        this.draftValues.clear();
        //?}
        rebuildWidgets();
        this.statusMessage = Component.translatable("peercraft.gui.settings.reset_done");
        this.statusColor = PeerCraftUi.TEXT_SUCCESS;
    }

    private void onSave() {
        for (Row row : this.rows) {
            //? if =1.21.1 {
            if (!row.widget.active) {
                continue;
            }
            //?}
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
            // Don't persist a value a launch flag is forcing (the flag wins anyway and the box
            // just mirrors it), nor one that equals the hardcoded default — keep settings.json
            // to the keys the player deliberately changed.
            String value2 = value == null ? "" : value.trim();
            if (value2.isEmpty()
                    || value2.equals(builtinDefault(row.key))
                    || value2.equals(PeerCraftConfig.baselineValue(row.key))
                    || forcedByFlag(row.key)) {
                this.settings.set(row.key, null);
            } else {
                this.settings.set(row.key, value2);
            }
        }
        //? if =1.21.1
        this.settings.showDeveloperSection = this.developerToggle instanceof SteampunkSettingsTheme.Toggle toggle && toggle.selected();
        //? if !=1.21.1
        /*this.settings.showDeveloperSection = this.developerToggle instanceof Checkbox box && box.selected();*/

        PeerCraftSettingsStore.save(this.settings);
        PeerCraftConfig.applyOverrides(this.settings.toOverrideMap());
        // Picked up immediately if already connected as a joiner — no-op otherwise.
        HandoffClientController.INSTANCE.resendPreference();
        PeerCraftUi.setScreen(this.minecraft, this.lastScreen);
    }

    private void failValidation(Row row) {
        this.statusMessage = Component.translatable("peercraft.gui.settings.invalid_number",
                Component.translatable("peercraft.gui.settings." + row.labelKey));
        this.statusColor = PeerCraftUi.TEXT_ERROR;
        //? if =1.21.1 {
        if (row.developer && !this.settings.showDeveloperSection) {
            this.settings.showDeveloperSection = true;
            ((SteampunkSettingsTheme.Toggle) this.developerToggle).setSelected(true);
        }
        List<Row> visible = visibleRows();
        int index = visible.indexOf(row);
        if (index < this.scroll || index >= this.scroll + rowsShown()) {
            scrollTo(index);
        } else {
            relayout();
        }
        this.pendingValidationFocus = row.widget;
        //?}
    }

    @SuppressWarnings("unchecked")
    private String readWidget(Row row) {
        AbstractWidget w = row.widget;
        //? if =1.21.1 {
        if (w instanceof SteampunkSettingsTheme.Toggle toggle) {
            return Boolean.toString(toggle.selected());
        }
        if (w instanceof SteampunkSettingsTheme.Choice choice) {
            return choice.getValue();
        }
        //?}
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

    //? if =1.21.1 {
    private void adjustPlayerCount(Row row, int direction) {
        EditBox box = (EditBox) row.widget;
        int value;
        try {
            value = Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException ignored) {
            value = Integer.parseInt(builtinDefault(row.key));
        }
        box.setValue(Integer.toString(Math.max(row.min, Math.min(row.max, value + direction))));
    }

    private void refreshPlayerCountButtons(Row row) {
        int value;
        try {
            value = Integer.parseInt(((EditBox) row.widget).getValue().trim());
        } catch (NumberFormatException ignored) {
            value = Integer.parseInt(builtinDefault(row.key));
        }
        row.decrement.active = row.widget.active && value > row.min;
        row.increment.active = row.widget.active && value < row.max;
    }

    private void captureDraft() {
        for (Row row : this.rows) {
            if (row.widget != null && row.widget.active) {
                this.draftValues.put(row.key, readWidget(row));
            }
        }
    }

    private void scrollTo(int target) {
        int next = Math.max(0, Math.min(target, Math.max(0, visibleRows().size() - rowsShown())));
        if (next != this.scroll) {
            this.scroll = next;
            relayout();
        }
    }

    private int scrollbarX() {
        return this.panelLeft + this.panelWidth - 20;
    }

    private int scrollbarTrackTop() {
        return this.listTop + 10;
    }

    private int scrollbarTrackHeight() {
        return Math.max(1, this.listBottom - this.listTop - 20);
    }

    private int scrollbarThumbHeight() {
        int total = visibleRows().size();
        return Math.min(scrollbarTrackHeight(), Math.max(14, scrollbarTrackHeight() * rowsShown() / Math.max(1, total)));
    }

    private int scrollbarThumbY() {
        int maxScroll = Math.max(0, visibleRows().size() - rowsShown());
        int travel = scrollbarTrackHeight() - scrollbarThumbHeight();
        return scrollbarTrackTop() + (maxScroll == 0 ? 0 : travel * this.scroll / maxScroll);
    }

    private void scrollFromMouse(double mouseY) {
        int maxScroll = Math.max(0, visibleRows().size() - rowsShown());
        int travel = scrollbarTrackHeight() - scrollbarThumbHeight();
        if (travel > 0) {
            scrollTo((int) Math.round((mouseY - scrollbarTrackTop() - this.scrollbarGrabOffset) * maxScroll / travel));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && visibleRows().size() > rowsShown()
                && mouseX >= scrollbarX() - 1 && mouseX < scrollbarX() + 9
                && mouseY >= this.listTop && mouseY < this.listBottom) {
            if (mouseY < scrollbarTrackTop()) {
                scrollTo(this.scroll - 1);
            } else if (mouseY >= scrollbarTrackTop() + scrollbarTrackHeight()) {
                scrollTo(this.scroll + 1);
            } else {
                this.scrollbarGrabOffset = mouseY >= scrollbarThumbY()
                        && mouseY < scrollbarThumbY() + scrollbarThumbHeight()
                        ? (int) mouseY - scrollbarThumbY() : scrollbarThumbHeight() / 2;
                this.draggingScrollbar = true;
                scrollFromMouse(mouseY);
            }
            return true;
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        applyValidationFocus();
        return handled;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && this.draggingScrollbar) {
            scrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.draggingScrollbar) {
            this.draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN || keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            scrollTo(this.scroll + (keyCode == GLFW.GLFW_KEY_PAGE_DOWN ? rowsShown() : -rowsShown()));
            return true;
        }
        if (!(getFocused() instanceof EditBox)) {
            if (keyCode == GLFW.GLFW_KEY_HOME || keyCode == GLFW.GLFW_KEY_END) {
                scrollTo(keyCode == GLFW.GLFW_KEY_HOME ? 0 : visibleRows().size());
                return true;
            }
        }
        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        applyValidationFocus();
        return handled;
    }

    private void applyValidationFocus() {
        if (this.pendingValidationFocus != null) {
            setFocused(this.pendingValidationFocus);
            this.pendingValidationFocus = null;
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, SteampunkSettingsTheme.BACKGROUND);
        SteampunkSettingsTheme.particles(graphics, this.width, this.height, this.panelLeft,
                this.panelLeft + this.panelWidth, (System.nanoTime() - this.animationStart) / 1_000_000L);
        SteampunkSettingsTheme.frame(graphics, this.panelLeft, this.panelTop, this.panelWidth,
                this.panelHeight, SteampunkSettingsTheme.PANEL, SteampunkSettingsTheme.BORDER);
        graphics.pose().pushPose();
        graphics.pose().scale(1.5F, 1.5F, 1.0F);
        graphics.drawCenteredString(this.font, "PeerCraft", (int) (this.width / 3.0F),
                (int) ((this.panelTop + 8) / 1.5F), SteampunkSettingsTheme.ACCENT);
        graphics.pose().popPose();
        graphics.drawCenteredString(this.font, Component.translatable("options.title"), this.width / 2,
                this.panelTop + 26, SteampunkSettingsTheme.TEXT);

        SteampunkSettingsTheme.frame(graphics, this.panelLeft + 10, this.listTop - 1,
                this.panelWidth - 32, this.listBottom - this.listTop + 2, 0xFF18120D, 0xFF49331F);
        for (Row row : this.rows) {
            if (row.screenY < 0) {
                continue;
            }
            if (mouseX >= this.panelLeft + 11 && mouseX < scrollbarX() - 4
                    && mouseY >= row.screenY && mouseY < row.screenY + THEMED_ROW_PITCH) {
                graphics.fill(this.panelLeft + 11, row.screenY, scrollbarX() - 3,
                        row.screenY + THEMED_ROW_PITCH, 0xFF241B12);
            }
            graphics.fill(this.panelLeft + 11, row.screenY + THEMED_ROW_PITCH - 1,
                    scrollbarX() - 3, row.screenY + THEMED_ROW_PITCH, 0xFF38291B);
            int color = !row.widget.active ? SteampunkSettingsTheme.MUTED
                    : row.warnOverride != null ? PeerCraftUi.TEXT_ERROR : SteampunkSettingsTheme.TEXT;
            List<FormattedCharSequence> lines = this.font.split(fullRowLabel(row), labelMaxWidth());
            int count = Math.min(3, lines.size());
            int textY = row.screenY + (THEMED_ROW_PITCH - count * this.font.lineHeight) / 2;
            for (int i = 0; i < count; i++) {
                graphics.drawString(this.font, lines.get(i), this.panelLeft + 20,
                        textY + i * this.font.lineHeight, color, false);
            }
        }
        drawThemedScrollbar(graphics, mouseX, mouseY);
        Component footerMessage = this.statusMessage;
        int color = this.statusColor;
        if (footerMessage.getString().isEmpty() && this.settings.showDeveloperSection) {
            footerMessage = Component.translatable("peercraft.gui.settings.developer_warning");
            color = PeerCraftUi.TEXT_ERROR;
        }
        List<FormattedCharSequence> lines = this.font.split(footerMessage, this.panelWidth - 24);
        int y = this.panelTop + this.panelHeight - 53;
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            FormattedCharSequence line = lines.get(i);
            graphics.drawString(this.font, line, (this.width - this.font.width(line)) / 2,
                    y + i * this.font.lineHeight, color, false);
        }
    }

    private Component fullRowLabel(Row row) {
        Component label = Component.translatable("peercraft.gui.settings." + row.labelKey);
        return row.restart ? label.copy().append(" ").append(Component.translatable("peercraft.gui.settings.restart_hint")) : label;
    }

    private void drawThemedScrollbar(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = scrollbarX();
        int top = scrollbarTrackTop();
        SteampunkSettingsTheme.frame(graphics, x, top, 8, scrollbarTrackHeight(), 0xFF120E0A, 0xFF49331F);
        if (visibleRows().size() > rowsShown()) {
            boolean hovered = mouseX >= x - 1 && mouseX < x + 9 && mouseY >= this.listTop && mouseY < this.listBottom;
            int border = hovered || this.draggingScrollbar ? SteampunkSettingsTheme.BORDER_HOVER : SteampunkSettingsTheme.BORDER;
            SteampunkSettingsTheme.frame(graphics, x + 1, scrollbarThumbY(), 6, scrollbarThumbHeight(), 0xFF604328, border);
            int color = hovered ? SteampunkSettingsTheme.ACCENT : SteampunkSettingsTheme.MUTED;
            graphics.drawCenteredString(this.font, "^", x + 4, this.listTop, color);
            graphics.drawCenteredString(this.font, "v", x + 4, this.listBottom - 8, color);
        }
    }
    //?}

    // 26.1 renamed GuiGraphics -> GuiGraphicsExtractor and drawString/drawCenteredString ->
    // text/centeredText. 1.21.1 calls our custom background through Screen.render().
    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if !=1.21.1 && <1.21.6
        /*this.renderBackground(graphics, mouseX, mouseY, partialTick);*/
        super.render(graphics, mouseX, mouseY, partialTick);
        //? if !=1.21.1 {
        /*int cx = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, cx, 16, PeerCraftUi.TEXT_TITLE);
        for (Row row : this.rows) {
            if (row.screenY < 0) {
                continue;
            }
            int color = !row.widget.active ? PeerCraftUi.TEXT_MUTED
                    : (row.warnOverride != null ? PeerCraftUi.TEXT_ERROR : PeerCraftUi.TEXT_TITLE);
            graphics.drawString(this.font, rowLabel(row), LABEL_X, row.screenY + 5, color, false);
        }
        if (this.settings != null && this.settings.showDeveloperSection) {
            graphics.drawString(this.font, devWarning(), LABEL_X, LIST_TOP + 16, PeerCraftUi.TEXT_ERROR, false);
        }
        graphics.drawCenteredString(this.font, this.statusMessage, cx, this.height - 68, this.statusColor);*/
        //?}
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
            graphics.text(this.font, rowLabel(row), LABEL_X, row.screenY + 5, color, false);
        }
        if (this.settings != null && this.settings.showDeveloperSection) {
            graphics.text(this.font, devWarning(), LABEL_X, LIST_TOP + 16, PeerCraftUi.TEXT_ERROR, false);
        }
        graphics.centeredText(this.font, this.statusMessage, cx, this.height - 68, this.statusColor);
    }*/
    //?}

    private Component rowLabel(Row row) {
        String base = Component.translatable("peercraft.gui.settings." + row.labelKey).getString();
        if (row.restart) {
            base = base + " " + Component.translatable("peercraft.gui.settings.restart_hint").getString();
        }
        return Component.literal(clip(base, labelMaxWidth()));
    }

    private Component devWarning() {
        String s = Component.translatable("peercraft.gui.settings.developer_warning").getString();
        return Component.literal(clip(s, this.width - LABEL_X - 20));
    }
}
