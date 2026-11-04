package net.peercraft.network.modsync;

import java.util.Arrays;

/**
 * Java 8 twin of {@code src/main/.../network/modsync/ModEntry.java} — the record is
 * hand-lowered to a Java 8 final class with the same accessor names, so {@code ModSyncProtocol}
 * / {@code ModDiff} / {@code ModSyncCoordinator} call sites are unchanged. Keep in sync with
 * the original; only the record-vs-class shell differs.
 *
 * <p>One mod the host offers to a joiner that's missing it. Pure data — no
 * {@code net.minecraft.*}. {@code sha512} is the 64-byte SHA-512 of the mod's jar file on the
 * host — the single source of truth the joiner verifies every downloaded byte against.
 */
public final class ModEntry {

    public enum Env {
        BOTH, CLIENT, SERVER;

        public byte wire() {
            return (byte) ordinal();
        }

        public static Env fromWire(int b) {
            Env[] values = values();
            return (b >= 0 && b < values.length) ? values[b] : BOTH;
        }
    }

    private final String id;
    private final String version;
    private final long sizeBytes;
    private final byte[] sha512;
    private final String fileName;
    private final Env env;
    private final String homepageUrl;
    private final String sourcesUrl;

    public ModEntry(String id,
                    String version,
                    long sizeBytes,
                    byte[] sha512,
                    String fileName,
                    Env env,
                    String homepageUrl,
                    String sourcesUrl) {
        this.id = id;
        this.version = version;
        this.sizeBytes = sizeBytes;
        this.sha512 = sha512 == null ? new byte[0] : sha512;
        this.fileName = fileName;
        this.env = env;
        this.homepageUrl = homepageUrl;
        this.sourcesUrl = sourcesUrl;
    }

    public String id() {
        return id;
    }

    public String version() {
        return version;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public byte[] sha512() {
        return sha512;
    }

    public String fileName() {
        return fileName;
    }

    public Env env() {
        return env;
    }

    public String homepageUrl() {
        return homepageUrl;
    }

    public String sourcesUrl() {
        return sourcesUrl;
    }

    /** A jar file name with no path separators and a {@code .jar} suffix — the only shape the joiner will write to disk. */
    public boolean hasSafeFileName() {
        return fileName != null
                && !fileName.trim().isEmpty()
                && fileName.endsWith(".jar")
                && fileName.indexOf('/') < 0
                && fileName.indexOf('\\') < 0
                && !fileName.contains("..");
    }

    public String sha512Hex() {
        StringBuilder sb = new StringBuilder(sha512.length * 2);
        for (byte b : sha512) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModEntry)) return false;
        ModEntry other = (ModEntry) o;
        return sizeBytes == other.sizeBytes
                && id.equals(other.id)
                && version.equals(other.version)
                && Arrays.equals(sha512, other.sha512)
                && fileName.equals(other.fileName)
                && env == other.env
                && homepageUrl.equals(other.homepageUrl)
                && sourcesUrl.equals(other.sourcesUrl);
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + version.hashCode();
        result = 31 * result + Long.hashCode(sizeBytes);
        result = 31 * result + Arrays.hashCode(sha512);
        result = 31 * result + fileName.hashCode();
        result = 31 * result + env.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ModEntry[" + id + "@" + version + ", " + sizeBytes + "B, " + fileName + ", " + env + "]";
    }
}
