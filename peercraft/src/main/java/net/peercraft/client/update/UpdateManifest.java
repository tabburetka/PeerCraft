package net.peercraft.client.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

/** The download part of a verified {@code update.json} (format 2). Java-8 clean. */
public final class UpdateManifest {
    private static final String[] CDN_PREFIXES = {
            "https://mediafilez.forgecdn.net/files/",
            "https://edge.forgecdn.net/files/",
    };

    private UpdateManifest() {
    }

    public static final class File {
        public final String version;
        public final String fileName;
        public final String url;
        public final String sha512;
        public final long size;

        File(String version, String fileName, String url, String sha512, long size) {
            this.version = version;
            this.fileName = fileName;
            this.url = url;
            this.sha512 = sha512;
            this.size = size;
        }
    }

    /** {@code fabric-1.21.1}, {@code neoforge-26.2}, {@code forge-1.12.2}; null when unknown. */
    public static String buildKey(String loader, String minecraft) {
        if (loader == null || minecraft == null || minecraft.isEmpty()) return null;
        String l = loader.toLowerCase(Locale.ROOT);
        if (!"fabric".equals(l) && !"neoforge".equals(l) && !"forge".equals(l)) return null;
        return l + "-" + minecraft;
    }

    /** The jar for {@code buildKey} at {@code version}, or null when absent or not a safe CurseForge file. */
    public static File fileFor(JsonObject root, String version, String buildKey) {
        if (root == null || version == null || buildKey == null) return null;
        JsonObject files = object(root, "files");
        JsonObject builds = files == null ? null : object(files, version);
        JsonObject entry = builds == null ? null : object(builds, buildKey);
        if (entry == null) return null;
        String name = string(entry, "file");
        String url = string(entry, "url");
        String sha512 = string(entry, "sha512");
        long size = number(entry, "size");
        if (name == null || url == null || sha512 == null || size <= 0 || size > 64L * 1024 * 1024) return null;
        if (!isSafeFileName(name) || !sha512.matches("[0-9a-fA-F]{128}")) return null;
        if (!isCdnUrl(url) || !url.endsWith("/" + name)) return null;
        return new File(version, name, url, sha512.toLowerCase(Locale.ROOT), size);
    }

    static boolean isCdnUrl(String url) {
        for (String prefix : CDN_PREFIXES) {
            if (url.startsWith(prefix) && url.indexOf('?') < 0 && url.indexOf('#') < 0
                    && url.indexOf("..") < 0 && url.indexOf('\\') < 0) return true;
        }
        return false;
    }

    static boolean isSafeFileName(String name) {
        return name.matches("peercraft-[A-Za-z0-9.+_-]+\\.jar");
    }

    private static JsonObject object(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static long number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsLong() : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
