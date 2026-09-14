package net.peercraft.network.handoff;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wire codec for <b>host handoff</b> — the "graceful baton pass" that lets a host give the
 * running world to another connected player and leave without the session ending. It runs
 * over the already-relayed peer-to-peer link (host &lt;-&gt; joiner), NOT over the rendezvous
 * server, which never sees a byte of it.
 *
 * <p>Every datagram starts with {@code [magic:1 = 0xE3][type:1]}, a third family alongside
 * {@code RendezvousProtocol.MAGIC = 0xE1} and {@code ModSyncProtocol.MAGIC = 0xE2}, so
 * {@code P2PBridge.handleIncomingPacket} can route it ahead of {@code FramedPacket.decode}
 * (which rejects a first byte that isn't its own {@code VERSION}).
 *
 * <p>The actual world-save bytes do NOT travel on this protocol — they reuse the mod-sync
 * file transport ({@code ModSyncProtocol} {@code T_FILE_*} + {@code FileReassembler}). This
 * protocol only carries the handshake: offer, accept/decline, "everyone reconnect", abort.
 *
 * <p>Backward compatibility follows the same append-only rule as the other two codecs: new
 * fields go at the END of a message and are read only when {@code remaining()} finds them;
 * {@code HandoffProtocolTest} pins the exact bytes.
 *
 * <p>Pure: no {@code net.minecraft.*}, no I/O. Operates on {@code byte[]} only. Kept
 * Java-8-source-clean (plain classes, no records / arrow-switch) so it compiles for the
 * 1.16.5 backport target without a hand-lowered twin.
 */
public final class HandoffProtocol {

    public static final byte MAGIC = (byte) 0xE3;

    /** Bumped only on an incompatible layout change; carried in OFFER so a mismatch declines cleanly. */
    public static final int PROTO_VERSION = 1;

    /** host -&gt; chosen successor: "will you take the world?" + everything needed to decide and to re-host it. */
    public static final byte T_OFFER = 0x01;
    /** successor -&gt; host: yes. */
    public static final byte T_ACCEPT = 0x02;
    /** successor -&gt; host: no (carries a {@code peercraft.handoff.decline.*} reason key). */
    public static final byte T_DECLINE = 0x03;
    /** host -&gt; ALL joiners: the world has moved; reconnect to the player with this account id. */
    public static final byte T_MIGRATE = 0x04;
    /** successor -&gt; host: my integrated server is up and registered — safe for you to stop now. */
    public static final byte T_MIGRATE_OK = 0x05;
    /** either side -&gt; the other: the handoff was called off (carries a {@code peercraft.handoff.abort.*} reason key). */
    public static final byte T_ABORT = 0x06;
    /** keepalive, sent both ways while an offer/transfer is in flight so the punched NAT mapping doesn't age out. No payload; ignored on receipt. */
    public static final byte T_PING = 0x07;

    /** OFFER flag bits. */
    public static final int OFFER_FLAG_ALLOW_UNLICENSED = 0x1;
    public static final int OFFER_FLAG_FRIENDS_ONLY = 0x2;
    public static final int OFFER_FLAG_PUBLIC_ROOM = 0x4;

    /** One required mod, as the host has it installed — the successor checks it has each id before accepting. */
    public static final class ModRef {
        private final String id;
        private final String version;

        public ModRef(String id, String version) {
            this.id = id;
            this.version = version;
        }

        public String id() {
            return id;
        }

        public String version() {
            return version;
        }
    }

    /**
     * {@code offerId} correlates every later message of one handoff attempt. {@code worldLabel}
     * is the public/browser world name (or the save name). {@code estArchiveBytes} is a rough
     * size so the successor can show a sensible progress bar / refuse a huge one. {@code flags}
     * mirrors the host's {@code PeerCraftHostOptions} so the successor re-hosts with the same
     * gating. {@code requiredMods} is the host's mod set (mod-sync {@code REQUIRED} view).
     */
    public static final class Offer {
        private final int protoVersion;
        private final long offerId;
        private final String worldLabel;
        private final long estArchiveBytes;
        private final int maxPlayers;
        private final int flags;
        private final List<ModRef> requiredMods;
        /** Stable PeerCraft world id (see {@code PeercraftWorldMeta}) so the successor can recognise a world coming back and update it in place. "" if the host has none. */
        private final String worldId;

        public Offer(int protoVersion, long offerId, String worldLabel, long estArchiveBytes,
                     int maxPlayers, int flags, List<ModRef> requiredMods, String worldId) {
            this.protoVersion = protoVersion;
            this.offerId = offerId;
            this.worldLabel = worldLabel;
            this.estArchiveBytes = estArchiveBytes;
            this.maxPlayers = maxPlayers;
            this.flags = flags;
            this.requiredMods = requiredMods;
            this.worldId = worldId == null ? "" : worldId;
        }

        public int protoVersion() {
            return protoVersion;
        }

        public long offerId() {
            return offerId;
        }

        public String worldLabel() {
            return worldLabel;
        }

        public long estArchiveBytes() {
            return estArchiveBytes;
        }

        public int maxPlayers() {
            return maxPlayers;
        }

        public int flags() {
            return flags;
        }

        public List<ModRef> requiredMods() {
            return requiredMods;
        }

        public String worldId() {
            return worldId;
        }

        public boolean allowUnlicensed() {
            return (flags & OFFER_FLAG_ALLOW_UNLICENSED) != 0;
        }

        public boolean friendsOnly() {
            return (flags & OFFER_FLAG_FRIENDS_ONLY) != 0;
        }

        public boolean publicRoom() {
            return (flags & OFFER_FLAG_PUBLIC_ROOM) != 0;
        }
    }

    public static final class Decline {
        private final long offerId;
        private final String reasonKey;

        public Decline(long offerId, String reasonKey) {
            this.offerId = offerId;
            this.reasonKey = reasonKey;
        }

        public long offerId() {
            return offerId;
        }

        public String reasonKey() {
            return reasonKey;
        }
    }

    public static final class Migrate {
        private final long offerId;
        private final UUID successorAccountId;

        public Migrate(long offerId, UUID successorAccountId) {
            this.offerId = offerId;
            this.successorAccountId = successorAccountId;
        }

        public long offerId() {
            return offerId;
        }

        public UUID successorAccountId() {
            return successorAccountId;
        }
    }

    public static final class Abort {
        private final long offerId;
        private final String reasonKey;

        public Abort(long offerId, String reasonKey) {
            this.offerId = offerId;
            this.reasonKey = reasonKey;
        }

        public long offerId() {
            return offerId;
        }

        public String reasonKey() {
            return reasonKey;
        }
    }

    private HandoffProtocol() {
    }

    /** @return the type byte, or -1 if this isn't a handoff datagram at all (wrong magic / too short). */
    public static int messageType(byte[] data, int length) {
        if (length < 2 || data[0] != MAGIC) {
            return -1;
        }
        return data[1] & 0xFF;
    }

    // ---- OFFER: host -> successor ----

    public static byte[] encodeOffer(long offerId, String worldLabel, long estArchiveBytes,
                                     int maxPlayers, int flags, List<ModRef> requiredMods, String worldId) {
        int size = 2 + 1 + 8 + 2 + utf8(worldLabel).length + 8 + 1 + 1 + 2 + 2 + utf8(worldId).length;
        for (ModRef m : requiredMods) {
            size += 2 + utf8(m.id()).length + 2 + utf8(m.version()).length;
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(MAGIC).put(T_OFFER);
        buf.put((byte) PROTO_VERSION);
        buf.putLong(offerId);
        putStr(buf, worldLabel);
        buf.putLong(estArchiveBytes);
        buf.put((byte) maxPlayers);
        buf.put((byte) flags);
        buf.putShort((short) requiredMods.size());
        for (ModRef m : requiredMods) {
            putStr(buf, m.id());
            putStr(buf, m.version());
        }
        // Appended after the mod list (append-only): a pre-worldId sender just omits it and
        // decodeOffer's getStr() returns "".
        putStr(buf, worldId == null ? "" : worldId);
        return trim(buf);
    }

    public static Offer decodeOffer(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        int proto = u8(buf);
        long offerId = i64(buf);
        String worldLabel = getStr(buf);
        long estArchiveBytes = i64(buf);
        int maxPlayers = u8(buf);
        int flags = u8(buf);
        int count = u16(buf);
        List<ModRef> mods = new ArrayList<ModRef>(Math.max(0, count));
        for (int i = 0; i < count && buf.remaining() > 0; i++) {
            String id = getStr(buf);
            String version = getStr(buf);
            mods.add(new ModRef(id, version));
        }
        String worldId = getStr(buf);
        return new Offer(proto, offerId, worldLabel, estArchiveBytes, maxPlayers, flags, mods, worldId);
    }

    // ---- ACCEPT / MIGRATE_OK: successor -> host ----

    public static byte[] encodeAccept(long offerId) {
        return offerIdMessage(T_ACCEPT, offerId);
    }

    public static byte[] encodeMigrateOk(long offerId) {
        return offerIdMessage(T_MIGRATE_OK, offerId);
    }

    /** @return the {@code offerId} of an ACCEPT or MIGRATE_OK datagram. */
    public static long decodeOfferId(byte[] data, int length) {
        return ByteBuffer.wrap(data, 2, length - 2).getLong();
    }

    // ---- DECLINE: successor -> host ----

    public static byte[] encodeDecline(long offerId, String reasonKey) {
        return offerIdAndReason(T_DECLINE, offerId, reasonKey);
    }

    public static Decline decodeDecline(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        long offerId = i64(buf);
        return new Decline(offerId, getStr(buf));
    }

    // ---- MIGRATE: host -> all joiners ----

    public static byte[] encodeMigrate(long offerId, UUID successorAccountId) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + 16);
        buf.put(MAGIC).put(T_MIGRATE);
        buf.putLong(offerId);
        buf.putLong(successorAccountId.getMostSignificantBits());
        buf.putLong(successorAccountId.getLeastSignificantBits());
        return buf.array();
    }

    public static Migrate decodeMigrate(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        long offerId = i64(buf);
        UUID accountId = new UUID(i64(buf), i64(buf));
        return new Migrate(offerId, accountId);
    }

    // ---- ABORT: either side ----

    public static byte[] encodeAbort(long offerId, String reasonKey) {
        return offerIdAndReason(T_ABORT, offerId, reasonKey);
    }

    public static Abort decodeAbort(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        long offerId = i64(buf);
        return new Abort(offerId, getStr(buf));
    }

    // ---- PING ----

    public static byte[] encodePing() {
        return new byte[]{MAGIC, T_PING};
    }

    // ---- helpers ----

    private static byte[] offerIdMessage(byte type, long offerId) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 8);
        buf.put(MAGIC).put(type);
        buf.putLong(offerId);
        return buf.array();
    }

    private static byte[] offerIdAndReason(byte type, long offerId, String reasonKey) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + 2 + utf8(reasonKey).length);
        buf.put(MAGIC).put(type);
        buf.putLong(offerId);
        putStr(buf, reasonKey);
        return trim(buf);
    }

    private static ByteBuffer body(byte[] data, int length) {
        return ByteBuffer.wrap(data, 2, length - 2);
    }

    private static byte[] trim(ByteBuffer buf) {
        byte[] out = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, out, 0, out.length);
        return out;
    }

    private static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    /** 2-byte unsigned length prefix + UTF-8 bytes. */
    private static void putStr(ByteBuffer buf, String s) {
        byte[] b = utf8(s);
        buf.putShort((short) b.length);
        buf.put(b);
    }

    /** Tolerant of a short/absent buffer (returns "") so appended trailer fields decode on older senders. */
    private static String getStr(ByteBuffer buf) {
        if (buf.remaining() < 2) {
            return "";
        }
        int len = buf.getShort() & 0xFFFF;
        if (buf.remaining() < len) {
            return "";
        }
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static int u8(ByteBuffer buf) {
        try {
            return buf.get() & 0xFF;
        } catch (BufferUnderflowException e) {
            return 0;
        }
    }

    private static int u16(ByteBuffer buf) {
        if (buf.remaining() < 2) {
            return 0;
        }
        return buf.getShort() & 0xFFFF;
    }

    private static long i64(ByteBuffer buf) {
        if (buf.remaining() < 8) {
            return 0L;
        }
        return buf.getLong();
    }
}
