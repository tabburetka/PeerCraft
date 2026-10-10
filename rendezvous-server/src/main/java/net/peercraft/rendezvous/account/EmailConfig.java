package net.peercraft.rendezvous.account;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Secrets are environment-only. An absent file disables recovery without changing accounts. */
public record EmailConfig(boolean enabled, String bindHost, int port, boolean tlsReverseProxy,
                          Path keyStore, String keyStorePassword, String smtpHost, int smtpPort,
                          boolean implicitTls, String smtpUser, String smtpPassword, String from) {
    public static EmailConfig load(Path dataDir) throws IOException {
        return load(dataDir, System.getenv());
    }
    static EmailConfig load(Path dataDir, Map<String, String> environment) throws IOException {
        Properties p = new Properties(); Path file = dataDir.resolve("email.properties");
        if (Files.exists(file)) try (Reader input = Files.newBufferedReader(file)) { p.load(input); }
        boolean enabled = Boolean.parseBoolean(p.getProperty("enabled", "false"));
        if (!enabled) return new EmailConfig(false, "127.0.0.1", 51082, false, null, null, null, 587, false, null, null, null);
        String host = required(p, "smtp.host");
        String user = environment.get("PEERCRAFT_SMTP_USER"), password = environment.get("PEERCRAFT_SMTP_PASSWORD");
        if (user == null || user.isBlank() || password == null || password.isEmpty()) throw new IOException("Missing SMTP environment settings");
        String key = p.getProperty("https.keyStore", "").trim();
        return new EmailConfig(true, p.getProperty("https.bind", "127.0.0.1"),
                port(p, "https.port", 51082), Boolean.parseBoolean(p.getProperty("https.tlsReverseProxy", "false")),
                key.isEmpty() ? null : dataDir.resolve(key), environment.get("PEERCRAFT_EMAIL_KEYSTORE_PASSWORD"),
                host, port(p, "smtp.port", 587), Boolean.parseBoolean(p.getProperty("smtp.implicitTls", "false")),
                user, password, EmailRecoveryService.normalize(required(p, "smtp.from")));
    }
    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key, "").trim();
        if (value.isEmpty() || value.contains("\r") || value.contains("\n")) throw new IOException("Missing or invalid mail configuration: " + key);
        return value;
    }
    private static int port(Properties p, String key, int defaultValue) throws IOException {
        try {
            int value = Integer.parseInt(p.getProperty(key, Integer.toString(defaultValue)));
            if (value < 1 || value > 65535) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) { throw new IOException("Invalid mail service port"); }
    }
    // Record's generated toString must never disclose passwords in diagnostics.
    @Override public String toString() { return "EmailConfig[enabled=" + enabled + "]"; }
}
