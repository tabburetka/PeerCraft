package net.peercraft.config;

import java.util.List;
import net.peercraft.client.modsync.InstalledModScanner;
import net.peercraft.client.modsync.LoaderTag;
import net.peercraft.network.telemetry.Telemetry;
import net.peercraft.platform.services.PlatformMod;

/** First-run choice and live telemetry gate. Shared verbatim with the Java 8 clients. */
public final class TelemetryConsent {
    private static volatile boolean noticeNeeded = true;

    private TelemetryConsent() {}

    public static void initialize() {
        PeerCraftSettings settings = PeerCraftSettingsStore.load();
        noticeNeeded = !settings.telemetryNoticeAcknowledged;
        List<PlatformMod> installed = InstalledModScanner.allInstalled();
        String loader = LoaderTag.current().name().toLowerCase(java.util.Locale.ROOT);
        String loaderId = "fabric".equals(loader) ? "fabricloader" : loader;
        Telemetry.initialize(version(installed, "peercraft"), version(installed, "minecraft"), loader,
            version(installed, loaderId));
        apply(settings);
    }

    private static String version(List<PlatformMod> mods, String id) {
        for (PlatformMod mod : mods)
            if (id.equals(mod.id()))
                return mod.version();
        return "";
    }

    public static boolean needsNotice() {
        return noticeNeeded;
    }

    /** Closing the notice calls this with false, just like choosing not to send. */
    public static void choose(boolean enabled) {
        PeerCraftSettings settings = PeerCraftSettingsStore.load();
        settings.telemetryNoticeAcknowledged = true;
        settings.telemetryEnabled = Boolean.toString(enabled);
        PeerCraftSettingsStore.save(settings);
        PeerCraftConfig.applyOverrides(settings.toOverrideMap());
        apply(settings);
    }

    public static void apply(PeerCraftSettings settings) {
        noticeNeeded = !settings.telemetryNoticeAcknowledged;
        Telemetry.setEnabled(permitted(settings, PeerCraftConfig.telemetryEnabled(), PeerCraftConfig.mode()));
    }

    /** Launch flags may disable sending, but cannot enable it over a player's refusal. */
    static boolean permitted(PeerCraftSettings settings, boolean configured, String mode) {
        return settings.telemetryNoticeAcknowledged && "true".equalsIgnoreCase(settings.telemetryEnabled)
            && configured && !PeerCraftConfig.MODE_DISABLED.equals(mode);
    }

    /** Resetting other settings preserves the player's choice and does not show the notice again. */
    static PeerCraftSettings defaultsPreservingChoice(PeerCraftSettings saved) {
        PeerCraftSettings defaults = new PeerCraftSettings();
        defaults.telemetryNoticeAcknowledged = saved.telemetryNoticeAcknowledged;
        defaults.telemetryEnabled = saved.telemetryEnabled;
        return defaults;
    }
    public static PeerCraftSettings resetSettings() {
        PeerCraftSettings defaults = defaultsPreservingChoice(PeerCraftSettingsStore.load());
        PeerCraftSettingsStore.save(defaults);
        PeerCraftConfig.applyOverrides(defaults.toOverrideMap());
        apply(defaults);
        return defaults;
    }
}
