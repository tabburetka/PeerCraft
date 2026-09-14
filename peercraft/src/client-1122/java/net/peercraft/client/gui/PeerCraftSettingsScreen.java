package net.peercraft.client.gui;

// Forge 1.12.2 backport of src/main/.../client/gui/PeerCraftSettingsScreen.java.
// 1.12.2 deltas: Screen -> GuiScreen (initGui/drawScreen/actionPerformed); Component -> PeerCraftLang.tr;
// CycleButton -> CycleTextButton; Checkbox -> ToggleButton; EditBox rows are NOT ported here — the
// free-text / port flags (rendezvous address, proxyPort, *UdpPort, peerHost/peerPort, roomCode) stay
// launch-flag only on 1.12.2; size limits and max-players use preset cycles instead. Everything the
// user actually toggles in-game (mod-sync host/client modes, autoAccept, mode) is covered.
// Keep the covered rows in sync with the original.

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.config.PeerCraftSettings;
import net.peercraft.config.PeerCraftSettingsStore;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

public class PeerCraftSettingsScreen extends GuiScreen {

    private static final int ROWS_TOP = 46;
    private static final int ROW_PITCH = 22;
    private static final int CONTROL_W = 150;

    private static final List<String> MODE_VALUES = Arrays.asList("auto", "client", "host", "disabled");
    private static final List<String> MODSYNC_VALUES = Arrays.asList("off", "required", "all");
    private static final List<String> TOTAL_MB = Arrays.asList("256", "512", "1024", "2048", "4096");
    private static final List<String> MOD_MB = Arrays.asList("64", "128", "256", "512", "1024", "2048");
    private static final List<String> PLAYERS = Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8");

    private final GuiScreen lastScreen;
    private PeerCraftSettings settings;
    private final List<String> labels = new ArrayList<String>();
    private final List<Integer> labelYs = new ArrayList<Integer>();
    private final List<Boolean> labelWarn = new ArrayList<Boolean>();
    private String statusMessage = "";
    private int statusColor = PeerCraftUi.TEXT_ERROR;

    public PeerCraftSettingsScreen(GuiScreen lastScreen) {
        this.lastScreen = lastScreen;
    }

    private static String builtinDefault(String key) {
        if ("mode".equals(key)) return "auto";
        if ("internetPlay".equals(key)) return "false";
        if ("maxPlayers".equals(key)) return "4";
        if ("modSync.host".equals(key)) return "all";
        if ("modSync.client".equals(key)) return "all";
        if ("modSync.autoAccept".equals(key)) return "false";
        if ("modSync.reofferDeclined".equals(key)) return "false";
        if ("modSync.maxTotalMb".equals(key)) return "512";
        if ("modSync.maxModMb".equals(key)) return "256";
        if ("handoff".equals(key)) return "true";
        if ("handoff.declineSuccessor".equals(key)) return "false";
        if ("handoff.confirmBeforeOffer".equals(key)) return "true";
        if ("handoff.chatNotify".equals(key)) return "true";
        return "";
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

    private String seed(String key) {
        String saved = this.settings.get(key);
        if (saved != null && !saved.trim().isEmpty()) {
            return saved.trim();
        }
        String baseline = PeerCraftConfig.baselineValue(key);
        return !baseline.isEmpty() ? baseline : builtinDefault(key);
    }

    // Right-hand column for the cycle controls, pushed toward the edge so the Russian label to
    // its left has room; drawScreen() clips the label so the two never overlap.
    private int controlX() {
        return Math.max(this.width / 2 + 4, this.width - 20 - CONTROL_W);
    }

    @Override
    public void initGui() {
        if (this.settings == null) {
            this.settings = PeerCraftSettingsStore.load();
        }
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();
        this.labels.clear();
        this.labelYs.clear();
        this.labelWarn.clear();

        int y = ROWS_TOP;
        y = cycleRow(y, "modSync.client", "modsync_client", MODSYNC_VALUES, "modsync_mode.", false);
        y = cycleRow(y, "modSync.host", "modsync_host", MODSYNC_VALUES, "modsync_mode.", false);
        y = toggleRow(y, "modSync.reofferDeclined", "reoffer_declined", false);
        y = cycleRow(y, "modSync.maxTotalMb", "max_total_mb", TOTAL_MB, null, false);
        y = cycleRow(y, "modSync.maxModMb", "max_mod_mb", MOD_MB, null, false);
        y = toggleRow(y, "internetPlay", "internet_play", false);
        y = cycleRow(y, "maxPlayers", "max_players", PLAYERS, null, false);
        y = toggleRow(y, "handoff", "handoff", false);
        y = toggleRow(y, "handoff.declineSuccessor", "handoff_decline_successor", false);
        y = toggleRow(y, "handoff.confirmBeforeOffer", "handoff_confirm_before_offer", false);
        y = toggleRow(y, "handoff.chatNotify", "handoff_chat_notify", false);

        // developer section toggle
        this.buttonList.add(new ToggleButton(this.width / 2 - CONTROL_W, y,
                PeerCraftLang.tr("peercraft.gui.settings.developer_toggle"),
                this.settings.showDeveloperSection,
                value -> {
                    this.settings.showDeveloperSection = value;
                    this.initGui();
                }));
        y += ROW_PITCH + 4;

        if (this.settings.showDeveloperSection) {
            y = cycleRow(y, "mode", "mode", MODE_VALUES, "mode.", false);
            y = toggleRow(y, "modSync.autoAccept", "autoaccept", true);
        }

        int by = this.height - 40;
        int cx = this.width / 2;
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.settings.save"), this::onSave)
                .bounds(cx - 154, by, 100, 20).build());
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.settings.reset"), this::onReset)
                .bounds(cx - 50, by, 100, 20).build());
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.settings.cancel"),
                        () -> PeerCraftUi.setScreen(this.mc, this.lastScreen))
                .bounds(cx + 54, by, 100, 20).build());
    }

    private int cycleRow(int y, String key, String labelKey, List<String> values, String langPrefix, boolean warn) {
        String current = seed(key);
        final String prefix = langPrefix;
        IdButton button = CycleTextButton.create(controlX(), y, CONTROL_W, 20,
                values, values.contains(current) ? current : values.get(values.size() - 1),
                v -> prefix == null ? v : PeerCraftLang.tr("peercraft.gui.settings." + prefix + v),
                v -> this.settings.set(key, v));
        this.settings.set(key, values.contains(current) ? current : values.get(values.size() - 1));
        if (forcedByFlag(key)) {
            button.enabled = false;
        }
        this.buttonList.add(button);
        addLabel(labelKey, y, warn);
        return y + ROW_PITCH;
    }

    private int toggleRow(int y, String key, String labelKey, boolean warn) {
        boolean current = "true".equalsIgnoreCase(seed(key));
        ToggleButton toggle = new ToggleButton(this.width / 2 - CONTROL_W, y,
                labelText(labelKey, false),
                current,
                value -> this.settings.set(key, Boolean.toString(value)));
        this.settings.set(key, Boolean.toString(current));
        if (forcedByFlag(key)) {
            toggle.enabled = false;
        }
        this.buttonList.add(toggle);
        return y + ROW_PITCH;
    }

    private void addLabel(String labelKey, int y, boolean warn) {
        this.labels.add(labelText(labelKey, true));
        this.labelYs.add(y + 6);
        this.labelWarn.add(warn);
    }

    private String labelText(String labelKey, boolean withRestartHint) {
        String base = PeerCraftLang.tr("peercraft.gui.settings." + labelKey);
        boolean restart = "mode".equals(labelKey);
        return (withRestartHint && restart)
                ? base + " " + PeerCraftLang.tr("peercraft.gui.settings.restart_hint")
                : base;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
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
            PeerCraftUi.setScreen(this.mc, this.lastScreen);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void onReset() {
        PeerCraftSettingsStore.clear();
        PeerCraftConfig.applyOverrides(new LinkedHashMap<String, String>());
        PeerCraftUi.setScreen(this.mc, new PeerCraftSettingsScreen(this.lastScreen));
    }

    private void onSave() {
        for (String key : PeerCraftSettings.FLAG_KEYS) {
            String value = this.settings.get(key);
            if (value != null && (value.equals(builtinDefault(key))
                    || value.equals(PeerCraftConfig.baselineValue(key))
                    || forcedByFlag(key))) {
                this.settings.set(key, null);
            }
        }
        PeerCraftSettingsStore.save(this.settings);
        PeerCraftConfig.applyOverrides(this.settings.toOverrideMap());
        HandoffClientController.INSTANCE.resendPreference();
        PeerCraftUi.setScreen(this.mc, this.lastScreen);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        int cx = this.width / 2;
        this.drawCenteredString(this.fontRenderer, PeerCraftLang.tr("peercraft.gui.settings.title"),
                cx, 16, PeerCraftUi.TEXT_TITLE);
        int labelMax = Math.max(60, controlX() - 12 - (cx - CONTROL_W));
        for (int i = 0; i < this.labels.size(); i++) {
            this.drawString(this.fontRenderer, this.fontRenderer.trimStringToWidth(this.labels.get(i), labelMax),
                    cx - CONTROL_W, this.labelYs.get(i),
                    this.labelWarn.get(i) ? PeerCraftUi.TEXT_ERROR : PeerCraftUi.TEXT_TITLE);
        }
        if (this.settings != null && this.settings.showDeveloperSection) {
            this.drawString(this.fontRenderer, PeerCraftLang.tr("peercraft.gui.settings.developer_warning"),
                    cx - CONTROL_W, 32, PeerCraftUi.TEXT_ERROR);
        }
        if (!this.statusMessage.isEmpty()) {
            this.drawCenteredString(this.fontRenderer, this.statusMessage, cx, this.height - 54, this.statusColor);
        }
    }
}
