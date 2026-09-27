package net.peercraft.platform.services;

import java.nio.file.Path;

/**
 * One installed mod as the current loader sees it — the loader-agnostic shape
 * {@link PeercraftPlatform#getInstalledMods()} returns, so mod-sync's diff/scan code never
 * touches {@code net.fabricmc.*} / {@code net.neoforged.*} directly.
 *
 * @param id          the mod id
 * @param version     the mod's version string, as the loader reports it
 * @param jarPath     the standalone jar on disk, or {@code null} for a nested/bundled mod or a dev classpath entry
 * @param environment loader classification; client does not prove absence of integrated-server logic
 * @param homepageUrl a landing page, or {@code ""}; display-only
 * @param sourcesUrl  a sources page, or {@code ""}; display-only
 * @param nested      {@code true} when this mod is jar-in-jar'd inside another (not a file mod-sync can ship on its own)
 */
public record PlatformMod(String id,
                          String version,
                          Path jarPath,
                          String environment,
                          String homepageUrl,
                          String sourcesUrl,
                          boolean nested,
                          String parentId) {
    public PlatformMod(String id, String version, Path jarPath, String environment,
                       String homepageUrl, String sourcesUrl, boolean nested) {
        this(id, version, jarPath, environment, homepageUrl, sourcesUrl, nested, "");
    }
}
