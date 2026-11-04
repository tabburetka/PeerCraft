package net.peercraft.network;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A real rendezvous-server subprocess for end-to-end tests (servers/rendezvous jar, built first).
 * It binds port 0 and the test reads the port back from its log: reserving a port in the test and
 * handing it over raced with the other version nodes, which run these tests in parallel.
 */
public final class RendezvousServerProcess implements AutoCloseable {
    private static final Pattern LISTENING = Pattern.compile("Listening on UDP port (\\d+)");

    public final Process process;
    public final Path dataDir;
    public final Path log;
    public final int port;

    private RendezvousServerProcess(Process process, Path dataDir, Path log, int port) {
        this.process = process;
        this.dataDir = dataDir;
        this.log = log;
        this.port = port;
    }

    /** Skips the calling test when the server jar has not been built. */
    public static RendezvousServerProcess start(String... systemProperties) throws Exception {
        Path jar = findServerJar();
        Assumptions.assumeTrue(jar != null,
                "rendezvous-server jar not built — run `./gradlew jar` in servers/rendezvous first to enable this test");
        Path dataDir = Files.createTempDirectory("peercraft-rendezvous-e2e");
        Path log = dataDir.resolve("server.log");
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-Dpeercraft.analytics.httpPort=0");
        command.add("-Dpeercraft.rendezvous.dataDir=" + dataDir);
        for (String property : systemProperties) command.add("-D" + property);
        command.add("-jar");
        command.add(jar.toString());
        command.add("0");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Matcher m = LISTENING.matcher(new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
            if (m.find()) return new RendezvousServerProcess(process, dataDir, log, Integer.parseInt(m.group(1)));
            if (!process.isAlive()) break;
            Thread.sleep(25);
        }
        process.destroyForcibly();
        throw new AssertionError("rendezvous-server did not start ("
                + (process.isAlive() ? "timed out" : "exit=" + process.exitValue()) + "); log: " + log);
    }

    private static Path findServerJar() throws IOException {
        // Gradle runs each Stonecutter test from its version directory, not the root.
        for (Path root = Path.of("").toAbsolutePath(); root != null; root = root.getParent()) {
            Path libsDir = root.resolve("servers/rendezvous/build/libs");
            if (!Files.isDirectory(libsDir)) continue;
            try (Stream<Path> stream = Files.list(libsDir)) {
                return stream.filter(p -> p.getFileName().toString().matches("rendezvous-server-[0-9.]+\\.jar"))
                        .findFirst().orElse(null);
            }
        }
        return null;
    }

    @Override
    public void close() throws InterruptedException {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }
}
