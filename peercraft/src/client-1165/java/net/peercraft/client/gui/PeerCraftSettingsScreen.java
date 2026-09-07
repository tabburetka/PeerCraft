package net.peercraft.client.gui;

// Minecraft 1.16.5 Fabric backport of src/main/.../client/gui/PeerCraftSettingsScreen.java.
// Deltas: Component.translatable -> TranslatableComponent; Component.empty -> TextComponent.EMPTY;
// CycleButton -> CycleBtn (a plain Button that advances an index); Checkbox.builder(...).onValueChange
// -> ModSyncCheckbox; addRenderableWidget -> addButton; render(GuiGraphics) -> render(PoseStack) via
// static GuiComponent calls; mouseScrolled 4-arg -> 3-arg; AbstractWidget.setX/setY -> public x/y.
// Keep in sync with the original.

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.config.PeerCraftSettings;
import net.peercraft.config.PeerCraftSettingsStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** In-game PeerCraft settings (1.16.5). See the src/main original for behaviour notes. */
public class PeerCraftSettingsScreen extends Screen {

    private static final int LIST_TOP = 40;
    private static final int ROWS_TOP = LIST_TOP + 38;
    private static final int ROW_PITCH = 26;
    private static final int CONTROL_W = 150;
    private static final int LABEL_X = 20;

    private static final int KIND_BOOL = 0;
    private static final int KIND_INT = 1;
    private static final int KIND_STRING = 2;
    private static final int KIND_MODE = 3;
    private static final int KIND_MODSYNC = 4;

    private static final List<String> MODE_VALUES = Arrays.asList("auto", "client", "host", "disabled");
    private static final List<String> MODSYNC_VALUES = Arrays.asList("off", "required", "all");

    private final Screen lastScreen;
    private PeerCraftSettings settings;
    private final List<Row> rows = new ArrayList<>();
    private ModSyncCheckbox developerToggle;
    private int scroll;
    private Component statusMessage = TextComponent.EMPTY;
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftSettingsScreen(Screen lastScreen) {
        super(new TranslatableComponent("peercraft.gui.settings.title"));
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
        String warnOverride;
        AbstractWidget widget;
        List<String> cycleValues;
        int cycleIndex;
        int screenY = -1;

        Row(String key, int kind, boolean developer, boolean restart, int min, int max, String labelKey) {
            this.key = key;
            this.kind = kind;
            this.developer = developer;
            this.restart = restart;
            this.min = min;
            this.max = max;
            this.labelKey = labelKey;
        }
    }

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

        addRow("modSync.client", KIND_MODSYNC, false, false, 0, 0, "modsync_client");
        addRow("modSync.host", KIND_MODSYNC, false, false, 0, 0, "modsync_host");
        addRow("modSync.reofferDeclined", KIND_BOOL, false, false, 0, 0, "reoffer_declined");
        addRow("modSync.maxTotalMb", KIND_INT, false, false, 1, 4096, "max_total_mb");
        addRow("modSync.maxModMb", KIND_INT, false, false, 1, 2048, "max_mod_mb");
        addRow("internetPlay", KIND_BOOL, false, false, 0, 0, "internet_play");
        addRow("maxPlayers", KIND_INT, false, false, 1, 8, "max_players");

        addRow("mode", KIND_MODE, true, true, 0, 0, "mode");
        addRow("modSync.autoAccept", KIND_BOOL, true, false, 0, 0, "autoaccept").warnOverride = "autoaccept_warning";
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
            }
            this.addButton(row.widget);
        }

        this.developerToggle = new ModSyncCheckbox(LABEL_X, LIST_TOP, 20, 20,
                new TranslatableComponent("peercraft.gui.settings.developer_toggle"),
                this.settings.showDeveloperSection,
                value -> {
                    this.settings.showDeveloperSection = value;
                    this.scroll = 0;
                    relayout();
                });
        this.addButton(this.developerToggle);

        int by = this.height - 52;
        int cx = this.width / 2;
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.settings.save"), b -> onSave())
                .bounds(cx - 154, by, 100, 20).build());
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.settings.reset"), b -> onReset())
                .bounds(cx - 50, by, 100, 20).build());
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.settings.cancel"),
                        b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                .bounds(cx + 54, by, 100, 20).build());

        relayout();
    }

    private Row addRow(String key, int kind, boolean developer, boolean restart, int min, int max, String labelKey) {
        Row row = new Row(key, kind, developer, restart, min, max, labelKey);
        this.rows.add(row);
        return row;
    }

    // saved value, else what a launch flag / env / baked default is forcing, else hardcoded default.
    private String seed(String key) {
        String saved = this.settings.get(key);
        if (saved != null && !saved.trim().isEmpty()) {
            return saved.trim();
        }
        String baseline = PeerCraftConfig.baselineValue(key);
        return !baseline.isEmpty() ? baseline : builtinDefault(key);
    }

    private int controlX() {
        return Math.max(this.width / 2 + 4, this.width - 20 - CONTROL_W);
    }

    private int labelMaxWidth() {
        return Math.max(60, controlX() - 12 - LABEL_X);
    }

    private String clip(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - this.font.width("..."))) + "...";
    }

    private AbstractWidget buildWidget(Row row) {
        int x = controlX();
        int y = ROWS_TOP;
        String current = seed(row.key);
        switch (row.kind) {
            case KIND_BOOL:
                return new ModSyncCheckbox(x, y, 20, 20, TextComponent.EMPTY, "true".equalsIgnoreCase(current), v -> { });
            case KIND_MODE:
                return makeCycle(row, MODE_VALUES, current, x, y, "mode.");
            case KIND_MODSYNC:
                return makeCycle(row, MODSYNC_VALUES, current, x, y, "modsync_mode.");
            case KIND_INT:
            case KIND_STRING:
            default:
                EditBox box = new EditBox(this.font, x, y, CONTROL_W, 18, TextComponent.EMPTY);
                box.setMaxLength(row.kind == KIND_INT ? 6 : 128);
                box.setValue(current);
                return box;
        }
    }

    private Button makeCycle(Row row, List<String> values, String initial, int x, int y, String langPrefix) {
        row.cycleValues = values;
        row.cycleIndex = Math.max(0, values.indexOf(values.contains(initial) ? initial : values.get(values.size() - 1)));
        Button button = Btn.builder(cycleLabel(langPrefix, values.get(row.cycleIndex)), b -> {
            row.cycleIndex = (row.cycleIndex + 1) % values.size();
            b.setMessage(cycleLabel(langPrefix, values.get(row.cycleIndex)));
        }).bounds(x, y, CONTROL_W, 18).build();
        return button;
    }

    private static Component cycleLabel(String langPrefix, String value) {
        return new TranslatableComponent("peercraft.gui.settings." + langPrefix + value);
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
            row.widget.x = controlX();
            row.widget.y = y;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int total = visibleRows().size();
        int shown = rowsShown();
        if (delta != 0 && total > shown && mouseY >= ROWS_TOP && mouseY < ROWS_TOP + shown * ROW_PITCH) {
            int next = Math.max(0, Math.min(this.scroll - (int) Math.signum(delta), total - shown));
            if (next != this.scroll) {
                this.scroll = next;
                relayout();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        PeerCraftUi.setScreen(this.minecraft, this.lastScreen);
    }

    private void onReset() {
        PeerCraftSettingsStore.clear();
        PeerCraftConfig.applyOverrides(new LinkedHashMap<>());
        PeerCraftUi.setScreen(this.minecraft, new PeerCraftSettingsScreen(this.lastScreen));
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
        this.settings.showDeveloperSection = this.developerToggle != null && this.developerToggle.selected();

        PeerCraftSettingsStore.save(this.settings);
        PeerCraftConfig.applyOverrides(this.settings.toOverrideMap());
        PeerCraftUi.setScreen(this.minecraft, this.lastScreen);
    }

    private void failValidation(Row row) {
        this.statusMessage = new TranslatableComponent("peercraft.gui.settings.invalid_number",
                new TranslatableComponent("peercraft.gui.settings." + row.labelKey));
        this.statusColor = PeerCraftUi.TEXT_ERROR;
    }

    private String readWidget(Row row) {
        AbstractWidget w = row.widget;
        if (w instanceof ModSyncCheckbox) {
            return Boolean.toString(((ModSyncCheckbox) w).selected());
        }
        if (row.cycleValues != null) {
            return row.cycleValues.get(row.cycleIndex);
        }
        if (w instanceof EditBox) {
            return ((EditBox) w).getValue();
        }
        return null;
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, cx, 16, PeerCraftUi.TEXT_TITLE);
        for (Row row : this.rows) {
            if (row.screenY < 0) {
                continue;
            }
            int color = !row.widget.active ? PeerCraftUi.TEXT_MUTED
                    : (row.warnOverride != null ? PeerCraftUi.TEXT_ERROR : PeerCraftUi.TEXT_TITLE);
            GuiComponent.drawString(poseStack, this.font, rowLabel(row), LABEL_X, row.screenY + 5, color);
        }
        if (this.settings != null && this.settings.showDeveloperSection) {
            String warn = clip(new TranslatableComponent("peercraft.gui.settings.developer_warning").getString(),
                    this.width - LABEL_X - 20);
            GuiComponent.drawString(poseStack, this.font, new TextComponent(warn),
                    LABEL_X, LIST_TOP + 16, PeerCraftUi.TEXT_ERROR);
        }
        GuiComponent.drawCenteredString(poseStack, this.font, this.statusMessage, cx, this.height - 68, this.statusColor);
    }

    private Component rowLabel(Row row) {
        String base = new TranslatableComponent("peercraft.gui.settings." + row.labelKey).getString();
        if (row.restart) {
            base = base + " " + new TranslatableComponent("peercraft.gui.settings.restart_hint").getString();
        }
        return new TextComponent(clip(base, labelMaxWidth()));
    }
}
