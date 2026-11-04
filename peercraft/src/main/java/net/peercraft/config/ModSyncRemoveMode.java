package net.peercraft.config;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/**
 * Joiner-side handling of mods this player has but the host doesn't
 * ({@code peercraft.modSync.removeExtra}):
 *
 * <ul>
 *   <li>{@link #OFF} — never remove anything (default).</li>
 *   <li>{@link #KEEP_CLIENT} — offer to remove extra mods, but keep purely client-side ones
 *       ({@code ModEntry.Env.CLIENT}: minimaps, shaders, HUDs…), which never block a join.</li>
 *   <li>{@link #ALL} — offer to remove every extra mod, client-side ones included.</li>
 * </ul>
 *
 * <p>Removal is always confirmed per mod on the confirm screen and applied after the game
 * closes, with the original jar kept in the transaction's {@code backups/}.
 *
 * <p>Kept Java-8 clean and free of {@code net.minecraft} references so it is synced verbatim
 * into the Forge 1.12.2 / 1.7.10 backports.
 */
public enum ModSyncRemoveMode {
    OFF("off"),
    KEEP_CLIENT("keep_client"),
    ALL("all");

    private static final Logger LOGGER = LogManager.getLogger("peercraft");

    private final String key;

    ModSyncRemoveMode(String key) {
        this.key = key;
    }

    /** The config token for this mode ({@code "off"} / {@code "keep_client"} / {@code "all"}). */
    public String key() {
        return key;
    }

    /** Parses a config token; unknown/blank input logs a warning and returns {@code fallback}. */
    public static ModSyncRemoveMode fromKey(String raw, ModSyncRemoveMode fallback) {
        if (raw != null) {
            String v = raw.trim().toLowerCase();
            for (ModSyncRemoveMode mode : values()) {
                if (mode.key.equals(v)) {
                    return mode;
                }
            }
            if (!v.isEmpty()) {
                LOGGER.warn("[PeerCraftConfig] Неизвестный режим удаления лишних модов '{}' (допустимо: off/keep_client/all), использую '{}'", raw, fallback.key);
            }
        }
        return fallback;
    }
}
