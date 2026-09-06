package net.peercraft.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Three-state mod-sync setting, used independently for the host side ({@code peercraft.modSync.host})
 * and the client/joiner side ({@code peercraft.modSync.client}):
 *
 * <ul>
 *   <li>{@link #OFF} — mod sync does not run at all for that side.</li>
 *   <li>{@link #REQUIRED} — only mods required to join are shared/downloaded: entries whose
 *       {@code ModEntry.Env} is {@code BOTH} or {@code SERVER}. Purely client-side mods
 *       ({@code Env.CLIENT}) are withheld by the host / auto-skipped by the joiner.</li>
 *   <li>{@link #ALL} — every non-excluded mod is shared/offered (the original behaviour).</li>
 * </ul>
 *
 * <p>Kept Java-8 clean and free of {@code net.minecraft} references so it is synced verbatim
 * into the Forge 1.12.2 / 1.7.10 backports.
 */
public enum ModSyncMode {
    OFF("off"),
    REQUIRED("required"),
    ALL("all");

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    private final String key;

    ModSyncMode(String key) {
        this.key = key;
    }

    /** The wire/config token for this mode ({@code "off"} / {@code "required"} / {@code "all"}). */
    public String key() {
        return key;
    }

    /** Parses a config token; unknown/blank input logs a warning and returns {@code fallback}. */
    public static ModSyncMode fromKey(String raw, ModSyncMode fallback) {
        if (raw != null) {
            String v = raw.trim().toLowerCase();
            for (ModSyncMode mode : values()) {
                if (mode.key.equals(v)) {
                    return mode;
                }
            }
            if (!v.isEmpty()) {
                LOGGER.warn("[PeerCraftConfig] Неизвестный режим mod-sync '{}' (допустимо: off/required/all), использую '{}'", raw, fallback.key);
            }
        }
        return fallback;
    }
}
