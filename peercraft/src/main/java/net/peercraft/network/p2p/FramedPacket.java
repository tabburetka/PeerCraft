package net.peercraft.network.p2p;

import java.nio.ByteBuffer;

/**
 * Wire framing for the UDP relay: prepends a session id + sequence number to
 * every chunk of the tunneled Minecraft TCP stream so the receiver can drop
 * stale-session data and reassemble packets in send order instead of
 * splicing UDP datagrams together in arrival order. Also carries small
 * control frames (NACK) used to request retransmission of a lost DATA packet.
 */
public final class FramedPacket {

    // Bump on any wire-format change (header layout/size) so mismatched builds
    // get rejected by decode() instead of misparsed.
    public static final byte VERSION = 2;
    public static final byte TYPE_DATA = 0;
    public static final byte TYPE_NACK = 1;
    public static final int FLAG_FIN = 0x1;

    // version, type, flags, sessionId, seq — seq is a full long so a session sending
    // continuously for weeks still can't wrap it (an int seq could in principle wrap
    // after enough sustained traffic, and Java's silent int overflow would quietly
    // corrupt the ReorderBuffer's ordering comparisons instead of failing loudly).
    public static final int HEADER_SIZE = 1 + 1 + 1 + 8 + 8;

    private final byte type;
    private final byte flags;
    private final long sessionId;
    private final long seq;
    private final byte[] payload;

    private FramedPacket(byte type, byte flags, long sessionId, long seq, byte[] payload) {
        this.type = type;
        this.flags = flags;
        this.sessionId = sessionId;
        this.seq = seq;
        this.payload = payload;
    }

    private static byte[] encodeFrame(byte type, long sessionId, long seq, byte flags, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buf.put(VERSION);
        buf.put(type);
        buf.put(flags);
        buf.putLong(sessionId);
        buf.putLong(seq);
        buf.put(payload);
        return buf.array();
    }

    public static byte[] encode(long sessionId, long seq, byte flags, byte[] payload) {
        return encodeFrame(TYPE_DATA, sessionId, seq, flags, payload);
    }

    public static byte[] encode(long sessionId, long seq, byte[] payload) {
        return encode(sessionId, seq, (byte) 0, payload);
    }

    /** A NACK asks the peer to resend the DATA packet with the given seq for this session. */
    public static byte[] encodeNack(long sessionId, long missingSeq) {
        ByteBuffer payload = ByteBuffer.allocate(8);
        payload.putLong(missingSeq);
        return encodeFrame(TYPE_NACK, sessionId, 0, (byte) 0, payload.array());
    }

    /**
     * @return null if the buffer is too short to contain a valid header, or the
     * version byte is not recognized.
     */
    public static FramedPacket decode(byte[] data, int length) {
        if (length < HEADER_SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        byte version = buf.get();
        if (version != VERSION) {
            return null;
        }
        byte type = buf.get();
        byte flags = buf.get();
        long sessionId = buf.getLong();
        long seq = buf.getLong();
        byte[] payload = new byte[length - HEADER_SIZE];
        buf.get(payload);
        return new FramedPacket(type, flags, sessionId, seq, payload);
    }

    public byte type() {
        return type;
    }

    public boolean isFin() {
        return (flags & FLAG_FIN) != 0;
    }

    public long sessionId() {
        return sessionId;
    }

    public long seq() {
        return seq;
    }

    public byte[] payload() {
        return payload;
    }

    /** Only meaningful when {@link #type()} == {@link #TYPE_NACK}. */
    public long nackSeq() {
        return ByteBuffer.wrap(payload).getLong();
    }
}
