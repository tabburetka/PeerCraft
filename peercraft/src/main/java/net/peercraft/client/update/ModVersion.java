package net.peercraft.client.update;

import java.util.Locale;

/**
 * {@code MAJOR.MINOR.PATCH[-beta.N|-rc.N]} as described in VERSIONING.md. A release sorts after
 * its pre-releases, and {@code beta} sorts before {@code rc}. Java-8 clean: shared with the
 * Forge backports.
 */
public final class ModVersion implements Comparable<ModVersion> {
    private static final int RELEASE = 3;
    private static final int RC = 2;
    private static final int BETA = 1;

    private final int major;
    private final int minor;
    private final int patch;
    private final int stage;
    private final int stageNumber;
    private final String text;

    private ModVersion(int major, int minor, int patch, int stage, int stageNumber, String text) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.stage = stage;
        this.stageNumber = stageNumber;
        this.text = text;
    }

    /** Returns null for anything that is not a version string PeerCraft would publish. */
    public static ModVersion parse(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1);
        String core = text;
        String suffix = "";
        int dash = text.indexOf('-');
        if (dash >= 0) {
            core = text.substring(0, dash);
            suffix = text.substring(dash + 1).toLowerCase(Locale.ROOT);
        }
        String[] parts = core.split("\\.", -1);
        if (parts.length != 3) return null;
        int[] numbers = new int[3];
        for (int i = 0; i < 3; i++) {
            numbers[i] = number(parts[i]);
            if (numbers[i] < 0) return null;
        }
        int stage = RELEASE;
        int stageNumber = 0;
        if (!suffix.isEmpty()) {
            String name = suffix;
            int dot = suffix.indexOf('.');
            if (dot >= 0) {
                name = suffix.substring(0, dot);
                stageNumber = number(suffix.substring(dot + 1));
                if (stageNumber < 0) return null;
            }
            if ("beta".equals(name)) stage = BETA;
            else if ("rc".equals(name)) stage = RC;
            else return null;
        }
        return new ModVersion(numbers[0], numbers[1], numbers[2], stage, stageNumber, text);
    }

    private static int number(String part) {
        if (part.isEmpty() || part.length() > 6) return -1;
        for (int i = 0; i < part.length(); i++) {
            if (part.charAt(i) < '0' || part.charAt(i) > '9') return -1;
        }
        return Integer.parseInt(part);
    }

    public boolean isPrerelease() {
        return stage != RELEASE;
    }

    @Override
    public int compareTo(ModVersion other) {
        if (major != other.major) return Integer.compare(major, other.major);
        if (minor != other.minor) return Integer.compare(minor, other.minor);
        if (patch != other.patch) return Integer.compare(patch, other.patch);
        if (stage != other.stage) return Integer.compare(stage, other.stage);
        return Integer.compare(stageNumber, other.stageNumber);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ModVersion && compareTo((ModVersion) o) == 0;
    }

    @Override
    public int hashCode() {
        return ((major * 31 + minor) * 31 + patch) * 31 + stage * 1000 + stageNumber;
    }

    @Override
    public String toString() {
        return text;
    }
}
