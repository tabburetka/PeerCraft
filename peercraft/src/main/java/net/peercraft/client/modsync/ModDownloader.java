package net.peercraft.client.modsync;

import net.peercraft.network.modsync.FileReassembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * Streams a jar from an HTTPS URL to a temp file, hashing as it goes and bailing the moment
 * the byte count passes the per-mod cap. Verifies the finished file's SHA-512 against the
 * host manifest before the caller is allowed to install it.
 */
public final class ModDownloader {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    public interface ProgressSink {
        void onProgress(long received, long total);
    }

    private final HttpClient http;
    private final String userAgent;

    public ModDownloader(String modVersion) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.userAgent = "PeerCraft/" + modVersion + " (github.com/tabburetka/PeerCraft)";
    }

    /**
     * @return the SHA-512 of the downloaded file, or {@code null} on any failure (network,
     * HTTP status, size overrun). The caller compares it to the manifest hash.
     */
    public byte[] download(String url, Path dest, long declaredSize, long maxBytes, ProgressSink progress) {
        Path parent = dest.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", userAgent)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                LOGGER.debug("[ModSync] HTTP {} для {}", resp.statusCode(), url);
                return null;
            }
            long total = declaredSize > 0 ? declaredSize
                    : resp.headers().firstValueAsLong("content-length").orElse(-1);
            MessageDigest md = FileReassembler.sha512();
            long count = 0;
            try (DigestInputStream in = new DigestInputStream(resp.body(), md);
                 OutputStream out = Files.newOutputStream(dest)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) != -1) {
                    count += n;
                    if (count > maxBytes) {
                        LOGGER.warn("[ModSync] Загрузка {} превысила лимит {} байт — прерываем", url, maxBytes);
                        ModSyncFilesystem.deleteQuietly(dest);
                        return null;
                    }
                    out.write(buf, 0, n);
                    progress.onProgress(count, total);
                }
            }
            return md.digest();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.debug("[ModSync] Ошибка загрузки {}: {}", url, e.toString());
            ModSyncFilesystem.deleteQuietly(dest);
            return null;
        }
    }
}
