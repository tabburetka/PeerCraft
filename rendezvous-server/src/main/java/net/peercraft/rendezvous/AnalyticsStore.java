package net.peercraft.rendezvous;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Aggregated server telemetry. No addresses, names, room codes, or credentials are written to disk. */
final class AnalyticsStore {
    private static final int RETENTION_DAYS = 400;
    private static final int MAX_UNIQUE_PER_DAY = 100_000;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    static final class Day {
        Map<String, Long> counts = new HashMap<>();
        Set<String> visitors = new HashSet<>();
        Set<String> accounts = new HashSet<>();
        Map<String, Long> versions = new HashMap<>();
    }

    static final class State {
        int schema = 1;
        Map<String, Day> days = new LinkedHashMap<>();
    }

    private final Path file;
    private final byte[] key;
    private final LongSupplier clock;
    private final State state;
    private boolean dirty;

    AnalyticsStore(Path dir, LongSupplier clock) throws IOException {
        Files.createDirectories(dir);
        this.file = dir.resolve("analytics.json");
        this.clock = clock;
        Path keyFile = dir.resolve("analytics.key");
        if (Files.exists(file) && !Files.exists(keyFile)) throw new IOException("Analytics key is missing; preserving existing statistics");
        if (Files.exists(keyFile)) {
            key = Base64.getDecoder().decode(Files.readString(keyFile).trim());
        } else {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(key), StandardCharsets.US_ASCII,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            ownerOnly(keyFile);
        }
        if (key.length != 32) throw new IOException("Invalid analytics key length");
        if (Files.exists(file)) {
            State loaded = GSON.fromJson(Files.readString(file), State.class);
            if (loaded == null || loaded.schema != 1 || loaded.days == null) throw new IOException("Invalid analytics file");
            state = loaded;
        } else {
            state = new State();
        }
        prune();
    }

    private String today() {
        return Instant.ofEpochMilli(clock.getAsLong()).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private Day day() {
        return state.days.computeIfAbsent(today(), ignored -> new Day());
    }

    synchronized void request(String kind, InetAddress address, int bytes) {
        Day day = day();
        increment(day.counts, "requests", 1);
        increment(day.counts, "bytes_in", bytes);
        increment(day.counts, "request." + kind, 1);
        if (day.visitors.size() < MAX_UNIQUE_PER_DAY) day.visitors.add(hash("ip:", address.getAddress()));
        else increment(day.counts, "unique_limit_reached", 1);
        dirty = true;
    }

    synchronized void event(String name) {
        increment(day().counts, name, 1);
        dirty = true;
    }

    synchronized void account(UUID id) {
        if (id == null) return;
        Day day = day();
        if (day.accounts.size() < MAX_UNIQUE_PER_DAY) day.accounts.add(hash("account:", id.toString().getBytes(StandardCharsets.US_ASCII)));
        else increment(day.counts, "unique_limit_reached", 1);
        dirty = true;
    }

    synchronized void version(String value) {
        // Versions are self-reported; bound both string length and distinct values per day.
        String normalized = value != null && value.matches("[0-9A-Za-z._+-]{1,24}") ? value : "unknown";
        Day day = day();
        if (!day.versions.containsKey(normalized) && day.versions.size() >= 30) normalized = "other";
        increment(day.versions, normalized, 1);
        dirty = true;
    }

    private static void increment(Map<String, Long> map, String key, long by) {
        map.merge(key, by, Long::sum);
    }

    private String hash(String prefix, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(prefix.getBytes(StandardCharsets.US_ASCII));
            byte[] digest = mac.doFinal(value);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(digest, 16));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void prune() {
        String oldest = LocalDate.parse(today()).minusDays(RETENTION_DAYS - 1).toString();
        if (state.days.keySet().removeIf(date -> date.compareTo(oldest) < 0)) dirty = true;
    }

    synchronized void flush() {
        if (!dirty) return;
        prune();
        Path temporary = file.resolveSibling("analytics.json.tmp");
        try {
            Files.writeString(temporary, GSON.toJson(state), StandardCharsets.UTF_8);
            ownerOnly(temporary);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            System.err.println("[Analytics] Could not save statistics: " + e);
        }
    }

    private static void ownerOnly(Path path) {
        try { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
        catch (IOException | UnsupportedOperationException ignored) { }
    }

    /** Snapshot is detached from the mutable state and safe for the HTTP thread to serialize. */
    synchronized Map<String, Object> snapshot() {
        prune();
        List<Map<String, Object>> days = new ArrayList<>();
        Map<String, Set<String>> monthVisitors = new LinkedHashMap<>();
        Map<String, Set<String>> monthAccounts = new LinkedHashMap<>();
        Map<String, Map<String, Long>> monthCounts = new LinkedHashMap<>();
        state.days.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String date = entry.getKey();
            Day day = entry.getValue();
            if (day == null || day.counts == null || day.visitors == null || day.accounts == null || day.versions == null) return;
            String month = date.substring(0, 7);
            monthVisitors.computeIfAbsent(month, ignored -> new HashSet<>()).addAll(day.visitors);
            monthAccounts.computeIfAbsent(month, ignored -> new HashSet<>()).addAll(day.accounts);
            Map<String, Long> totals = monthCounts.computeIfAbsent(month, ignored -> new HashMap<>());
            day.counts.forEach((key, count) -> increment(totals, key, count));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date);
            row.put("visitors", day.visitors.size());
            row.put("accounts", day.accounts.size());
            row.put("counts", new HashMap<>(day.counts));
            row.put("versions", new HashMap<>(day.versions));
            days.add(row);
        });
        List<Map<String, Object>> months = new ArrayList<>();
        monthCounts.forEach((month, counts) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", month);
            row.put("visitors", monthVisitors.get(month).size());
            row.put("accounts", monthAccounts.get(month).size());
            row.put("counts", counts);
            months.add(row);
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", Instant.ofEpochMilli(clock.getAsLong()).toString());
        result.put("days", days);
        result.put("months", months);
        return result;
    }
}
