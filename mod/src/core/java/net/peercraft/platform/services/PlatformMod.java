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
public final class PlatformMod {
    private final String id;
    private final String version;
    private final Path jarPath;
    private final String environment;
    private final String homepageUrl;
    private final String sourcesUrl;
    private final boolean nested;
    private final String parentId;

    public PlatformMod(String id, String version, Path jarPath, String environment, String homepageUrl, String sourcesUrl, boolean nested, String parentId) {
        this.id = id;
        this.version = version;
        this.jarPath = jarPath;
        this.environment = environment;
        this.homepageUrl = homepageUrl;
        this.sourcesUrl = sourcesUrl;
        this.nested = nested;
        this.parentId = parentId;
    }

    public String id() {
        return id;
    }

    public String version() {
        return version;
    }

    public Path jarPath() {
        return jarPath;
    }

    public String environment() {
        return environment;
    }

    public String homepageUrl() {
        return homepageUrl;
    }

    public String sourcesUrl() {
        return sourcesUrl;
    }

    public boolean nested() {
        return nested;
    }

    public String parentId() {
        return parentId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlatformMod)) return false;
        PlatformMod other = (PlatformMod) o;
        return java.util.Objects.equals(id, other.id)
                && java.util.Objects.equals(version, other.version)
                && java.util.Objects.equals(jarPath, other.jarPath)
                && java.util.Objects.equals(environment, other.environment)
                && java.util.Objects.equals(homepageUrl, other.homepageUrl)
                && java.util.Objects.equals(sourcesUrl, other.sourcesUrl)
                && nested == other.nested
                && java.util.Objects.equals(parentId, other.parentId);
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + java.util.Objects.hashCode(id);
        h = 31 * h + java.util.Objects.hashCode(version);
        h = 31 * h + java.util.Objects.hashCode(jarPath);
        h = 31 * h + java.util.Objects.hashCode(environment);
        h = 31 * h + java.util.Objects.hashCode(homepageUrl);
        h = 31 * h + java.util.Objects.hashCode(sourcesUrl);
        h = 31 * h + Boolean.hashCode(nested);
        h = 31 * h + java.util.Objects.hashCode(parentId);
        return h;
    }

    @Override
    public String toString() {
        return "PlatformMod[id=" + id + ", version=" + version + ", jarPath=" + jarPath + ", environment=" + environment + ", homepageUrl=" + homepageUrl + ", sourcesUrl=" + sourcesUrl + ", nested=" + nested + ", parentId=" + parentId + "]";
    }

    public PlatformMod(String id, String version, Path jarPath, String environment,
                       String homepageUrl, String sourcesUrl, boolean nested) {
        this(id, version, jarPath, environment, homepageUrl, sourcesUrl, nested, "");
    }
}
