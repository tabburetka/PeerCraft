package net.peercraft.client.handoff;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Per-world PeerCraft sidecar ({@code <world>/peercraft-world.json}) that survives a handoff
 * because it sits inside the save directory and rides the archive along with everything else.
 *
 * <ul>
 *   <li>{@code worldId} — minted once, on this world's first internet-hosting. Lets a handoff
 *       recognise "this is the same world coming back" regardless of the folder name, so it
 *       can be updated in place instead of piling up {@code -peercraft-2/-3} copies.</li>
 *   <li>{@code handedOffAt} / {@code handedOffTo} — set when this machine hands the world to
 *       someone else. Together with {@code lastBecameHostAt} they tell whether the local copy
 *       is a stale post-handoff leftover ({@link #isStaleAfterHandoff()} &rarr; the warning
 *       screen).</li>
 *   <li>{@code lastBecameHostAt} — set whenever this machine (re)starts hosting this world,
 *       including an in-place reclaim; clearing the "stale" state.</li>
 * </ul>
 *
 * Tolerant of a missing / corrupt file — a fresh {@link PeercraftWorldMeta} is returned and
 * written on the next mutate.
 */
public final class PeercraftWorldMeta {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String FILE_NAME = "peercraft-world.json";

    private String worldId = "";
    private long handedOffAt;
    private String handedOffTo = "";
    private long lastBecameHostAt;

    public String worldId() {
        return worldId;
    }

    public long handedOffAt() {
        return handedOffAt;
    }

    public String handedOffTo() {
        return handedOffTo;
    }

    /** True when this local copy was handed to someone else and hasn't been re-hosted / reclaimed since. */
    public boolean isStaleAfterHandoff() {
        return handedOffAt > 0 && handedOffAt > lastBecameHostAt;
    }

    // ---- load / mutate ----

    /** Reads the sidecar, or {@code null} if there isn't one (or it's unreadable). */
    public static PeercraftWorldMeta loadOrNull(Path worldDir) {
        Path file = worldDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            PeercraftWorldMeta m = GSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), PeercraftWorldMeta.class);
            return m != null ? m : new PeercraftWorldMeta();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Handoff] Не удалось прочитать {}: {}", FILE_NAME, e.toString());
            return null;
        }
    }

    /**
     * Ensures the world has a sidecar with a {@code worldId} and a fresh {@code lastBecameHostAt}
     * (this machine is (re)starting to host it now). Returns the (written) metadata.
     */
    public static PeercraftWorldMeta ensureHosting(Path worldDir) {
        PeercraftWorldMeta m = loadOrNull(worldDir);
        if (m == null) {
            m = new PeercraftWorldMeta();
        }
        if (m.worldId == null || m.worldId.isEmpty()) {
            m.worldId = UUID.randomUUID().toString();
        }
        m.lastBecameHostAt = Math.max(nowSeconds(), m.handedOffAt);
        m.write(worldDir);
        return m;
    }

    /** Records that this machine has handed the world to {@code toName}. */
    public static void markHandedOff(Path worldDir, String toName) {
        PeercraftWorldMeta m = loadOrNull(worldDir);
        if (m == null) {
            m = new PeercraftWorldMeta();
        }
        if (m.worldId == null || m.worldId.isEmpty()) {
            m.worldId = UUID.randomUUID().toString();
        }
        // Recovery may replay this after COMMIT and on every later launch. Keep the original
        // transfer time and a known recipient rather than replacing either with a guess.
        boolean changed = !m.isStaleAfterHandoff();
        if (changed) m.handedOffAt = Math.max(nowSeconds(), m.lastBecameHostAt + 1);
        if (toName != null && !toName.isEmpty() && !toName.equals(m.handedOffTo)) {
            m.handedOffTo = toName;
            changed = true;
        }
        if (changed) m.write(worldDir);
    }

    /** Clears the "stale post-handoff" state — the local copy is authoritative again. */
    public static void markBecameHost(Path worldDir) {
        PeercraftWorldMeta m = loadOrNull(worldDir);
        if (m == null) {
            m = new PeercraftWorldMeta();
        }
        if (m.worldId == null || m.worldId.isEmpty()) {
            m.worldId = UUID.randomUUID().toString();
        }
        m.lastBecameHostAt = Math.max(nowSeconds(), m.handedOffAt);
        m.write(worldDir);
    }

    public void write(Path worldDir) {
        try {
            Files.createDirectories(worldDir);
            Files.write(worldDir.resolve(FILE_NAME), GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.warn("[Handoff] Не удалось записать {}: {}", FILE_NAME, e.toString());
        }
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
