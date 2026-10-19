package net.peercraft.network.modsync;

import javax.net.ssl.HttpsURLConnection;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

/** Java 8 HTTPS transport. Default JVM certificate and hostname checks remain enabled. */
public final class ModrinthDownloader {
    private ModrinthDownloader() { }

    public interface ProgressSink { void onProgress(long received, long total); }

    static boolean isCdnUrl(String value) {
        try {
            URL url = new URL(value);
            return "https".equalsIgnoreCase(url.getProtocol())
                    && "cdn.modrinth.com".equalsIgnoreCase(url.getHost())
                    && (url.getPort() == -1 || url.getPort() == 443) && url.getUserInfo() == null;
        } catch (Exception ignored) { return false; }
    }

    /** Returns true only for bytes matching BOTH the host's declared size and SHA-512. */
    public static boolean download(String url, Path dest, ModEntry entry, long maxBytes,
                                   BooleanSupplier cancelled, ProgressSink progress) {
        HttpsURLConnection connection = null;
        boolean verified = false;
        try {
            if (!isCdnUrl(url) || entry.sizeBytes() < 0 || entry.sizeBytes() > maxBytes
                    || cancelled.getAsBoolean()) return false;
            connection = (HttpsURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "PeerCraft (github.com/tabburetka/PeerCraft)");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (connection.getResponseCode() != 200) return false;
            long length = connection.getContentLengthLong();
            if (length >= 0 && length != entry.sizeBytes()) return false;
            Path parent = dest.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            long count = 0;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
            try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(dest)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()
                            || System.nanoTime() > deadline || n > entry.sizeBytes() - count
                            || n > maxBytes - count) return false;
                    output.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    count += n;
                    progress.onProgress(count, entry.sizeBytes());
                }
            }
            verified = !cancelled.getAsBoolean() && count == entry.sizeBytes()
                    && hex(digest.digest()).equalsIgnoreCase(entry.sha512Hex());
            return verified;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
            if (!verified) {
                try { Files.deleteIfExists(dest); } catch (Exception ignored) { }
            }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
