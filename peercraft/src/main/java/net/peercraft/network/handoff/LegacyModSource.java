package net.peercraft.network.handoff;

import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Resolve synthetic FML sources without weakening mandatory dependency checks. */
public final class LegacyModSource {
    private LegacyModSource() { }

    public static Path resolve(Path reported, Class<?> container) {
        if (reported != null && Files.isRegularFile(reported)) return reported;
        try {
            URL resource = container.getResource("/" + container.getName().replace('.', '/') + ".class");
            if (resource != null && "jar".equals(resource.getProtocol())) {
                URL location = ((JarURLConnection) resource.openConnection()).getJarFileURL();
                if ("file".equals(location.getProtocol())) {
                    Path actual = Paths.get(location.toURI());
                    if (Files.isRegularFile(actual)) return actual;
                }
            }
        } catch (java.io.IOException | java.net.URISyntaxException | SecurityException invalid) {
            // Keep the unresolved path: manifest capture must fail closed.
        }
        return reported;
    }
}
