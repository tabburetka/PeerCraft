package net.peercraft.platform.services;

import java.nio.file.Path;

/**
 * Java 8 twin of the {@code src/main/java} record of the same name (Forge 1.12.2 / 1.7.10
 * backports run on Java 8, where records don't exist). Kept API-compatible with the record
 * — same component accessor names. Mod-sync itself is Fabric/NeoForge only; the backports
 * pull this in only because {@code PeercraftPlatform} now names the type.
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

    public PlatformMod(String id, String version, Path jarPath, String environment,
                       String homepageUrl, String sourcesUrl, boolean nested) {
        this(id, version, jarPath, environment, homepageUrl, sourcesUrl, nested, "");
    }

    public PlatformMod(String id, String version, Path jarPath, String environment,
                       String homepageUrl, String sourcesUrl, boolean nested, String parentId) {
        this.parentId = parentId;
        this.id = id;
        this.version = version;
        this.jarPath = jarPath;
        this.environment = environment;
        this.homepageUrl = homepageUrl;
        this.sourcesUrl = sourcesUrl;
        this.nested = nested;
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

    public String parentId() { return parentId; }

    public boolean nested() {
        return nested;
    }
}
