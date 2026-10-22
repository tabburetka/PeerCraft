package net.peercraft.network.telemetry;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small, versioned diagnostic packets. No free-text payloads or user identifiers. Java 8. */
public final class TelemetryProtocol {
    public static final int TYPE_EVENT = 0x70, TYPE_ACK = 0x71, MAX_PACKET_BYTES = 512;
    public static final long MAX_DURATION = 7L * 24 * 60 * 60 * 1000;
    private static final Set<String> EVENTS =
        values("client_started join_started stage_finished transport_ready join_result world_joined "
               + "session_active session_summary modsync_result handoff_result");
    private static final Set<String> STAGES =
        values("none auth rendezvous direct modsync minecraft_login playing handoff");
    private static final Set<String> OUTCOMES = values(
        "none success failure cancelled restart_required ready declined aborted unknown failed_after_commit "
        + "recovery_failed stop_failed off not_needed skipped installed continued proceed reconnected error");
    private static final Set<String> REASONS =
        values("none other user_cancel cancelled restart_required vanilla_disconnect normal_disconnect "
               + "transport_failure timeout no_response invalid_code already_claimed expired server_busy "
               + "not_friend account_required already_connecting hole_punching no_code proxy_port_busy "
               + "resolve_rendezvous udp_port_busy relay_unavailable relay_disabled relay_account_required "
               + "relay_not_configured inventory io too_big transfer handshake_timeout host_abort no_changes "
               + "no_downloads install_staged installed declined not_needed terminal stop_failed error "
               + "unknown ready aborted failed_after_commit recovery_failed reconnected");
    private static final Set<String> LOADERS = values("fabric neoforge forge unknown");
    private static final Set<String> SYSTEMS = values("windows linux macos other");
    private static final Set<String> ENTRIES = values("none code friend public lan");
    private static final Set<String> ROLES = values("client guest host successor observer");
    private TelemetryProtocol() {}
    private static Set<String> values(String s) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(s.split(" "))));
    }
    public static String version(String s) {
        return s != null && s.matches("[0-9][0-9A-Za-z._+~-]{0,31}") ? s : "unknown";
    }
    public static String reason(String s) {
        if (s == null)
            return "none";
        String v = s.toLowerCase(Locale.ROOT);
        for (String prefix :
            new String[] {"peercraft.p2p.fail.", "peercraft.modsync.fail.", "peercraft.handoff.abort."})
            if (v.startsWith(prefix))
                v = v.substring(prefix.length());
        return REASONS.contains(v) ? v : "other";
    }
    public static String outcome(String s) {
        if ("failed".equals(s))
            return "failure";
        return OUTCOMES.contains(s) ? s : "unknown";
    }
    public static String stage(String s) {
        if (STAGES.contains(s))
            return s;
        if ("peercraft.p2p.status.joining".equals(s))
            return "rendezvous";
        if ("peercraft.p2p.status.peer_found".equals(s) || "peercraft.p2p.status.checking_direct".equals(s))
            return "direct";
        return null;
    }
    public static final class Event {
        public final UUID eventId, operationId;
        public final String event, modVersion, mcVersion, loader, loaderVersion, os, entry, stage, outcome,
            reason, role;
        public final int javaMajor, count;
        public final long durationMillis, bytes;
        public Event(UUID id, UUID operation, String event, String mod, String mc, String loader,
            String loaderVersion, int javaMajor, String os, String entry, String stage, String outcome,
            String reason, String role, long duration, long bytes, int count) {
            this.eventId = id;
            this.operationId = operation;
            this.event = event;
            this.modVersion = mod;
            this.mcVersion = mc;
            this.loader = loader;
            this.loaderVersion = loaderVersion;
            this.javaMajor = javaMajor;
            this.os = os;
            this.entry = entry;
            this.stage = stage;
            this.outcome = outcome;
            this.reason = reason;
            this.role = role;
            this.durationMillis = duration;
            this.bytes = bytes;
            this.count = count;
        }
    }
    public static boolean validEvent(Event e) {
        return e != null && e.eventId != null && e.operationId != null && EVENTS.contains(e.event)
            && version(e.modVersion).equals(e.modVersion) && version(e.mcVersion).equals(e.mcVersion)
            && version(e.loaderVersion).equals(e.loaderVersion) && LOADERS.contains(e.loader)
            && SYSTEMS.contains(e.os) && ENTRIES.contains(e.entry) && STAGES.contains(e.stage)
            && OUTCOMES.contains(e.outcome) && REASONS.contains(e.reason) && ROLES.contains(e.role)
            && e.javaMajor >= 8 && e.javaMajor <= 99 && e.durationMillis >= 0
            && e.durationMillis <= MAX_DURATION && e.bytes >= 0 && e.bytes <= (1L << 40) && e.count >= 0
            && e.count <= 10000;
    }
    public static boolean isEvent(byte[] b, int n) {
        return b != null && n >= 2 && n <= b.length && (b[0] & 255) == 0xE1 && (b[1] & 255) == TYPE_EVENT;
    }
    public static byte[] encodeEvent(Event e) {
        if (!validEvent(e))
            throw new IllegalArgumentException("Invalid diagnostic event");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(out);
            d.writeByte(0xE1);
            d.writeByte(TYPE_EVENT);
            d.writeByte(1);
            uuid(d, e.eventId);
            uuid(d, e.operationId);
            for (String s : new String[] {e.event, e.modVersion, e.mcVersion, e.loader, e.loaderVersion, e.os,
                     e.entry, e.stage, e.outcome, e.reason, e.role})
                str(d, s);
            d.writeByte(e.javaMajor);
            d.writeLong(e.durationMillis);
            d.writeLong(e.bytes);
            d.writeShort(e.count);
            if (out.size() > MAX_PACKET_BYTES)
                throw new IllegalArgumentException("Diagnostic event too large");
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    public static Event decodeEvent(byte[] b, int n) {
        if (!isEvent(b, n) || n > MAX_PACKET_BYTES)
            throw new IllegalArgumentException("Invalid diagnostic packet");
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(b, 2, n - 2));
            if (d.readUnsignedByte() != 1)
                throw new IllegalArgumentException("Unknown diagnostic schema");
            UUID id = uuid(d), op = uuid(d);
            String[] s = new String[11];
            for (int i = 0; i < s.length; i++) s[i] = str(d);
            Event e = new Event(id, op, s[0], s[1], s[2], s[3], s[4], d.readUnsignedByte(), s[5], s[6], s[7],
                s[8], s[9], s[10], d.readLong(), d.readLong(), d.readUnsignedShort());
            if (d.available() != 0 || !validEvent(e))
                throw new IllegalArgumentException("Invalid diagnostic fields");
            return e;
        } catch (IOException malformed) {
            throw new IllegalArgumentException("Truncated diagnostic packet");
        }
    }
    public static byte[] encodeAck(UUID id) {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(o);
            d.writeByte(0xE1);
            d.writeByte(TYPE_ACK);
            d.writeByte(1);
            uuid(d, id);
            return o.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
    public static UUID decodeAck(byte[] b, int n) {
        if (n != 19 || b == null || b.length < n || (b[0] & 255) != 0xE1 || (b[1] & 255) != TYPE_ACK
            || b[2] != 1)
            throw new IllegalArgumentException("Invalid acknowledgement");
        try {
            return uuid(new DataInputStream(new ByteArrayInputStream(b, 3, 16)));
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
    private static void uuid(DataOutputStream d, UUID id) throws IOException {
        d.writeLong(id.getMostSignificantBits());
        d.writeLong(id.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream d) throws IOException {
        return new UUID(d.readLong(), d.readLong());
    }
    private static void str(DataOutputStream d, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        d.writeByte(b.length);
        d.write(b);
    }
    private static String str(DataInputStream d) throws IOException {
        int n = d.readUnsignedByte();
        if (n > 40)
            throw new IOException("Long field");
        byte[] b = new byte[n];
        d.readFully(b);
        for (byte v : b)
            if (v < 32 || v > 126)
                throw new IOException("Non-ASCII field");
        return new String(b, StandardCharsets.US_ASCII);
    }
}
