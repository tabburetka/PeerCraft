package net.peercraft.rendezvous;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Bounded, Java-8 wire format shared verbatim by clients and rendezvous. No world bytes. */
public final class HandoffAuthorityProtocol {
    public static final byte MAGIC = (byte) 0xE6;
    public static final int VERSION = 3, MAX_PACKET = 512;
    public static final int CAPABILITIES = 1, BEGIN = 2, VERIFIED = 3, COMMIT = 4,
            ABORT = 5, QUERY = 6, READY = 7, QUIESCE = 8, REPLY = 9, RECOVERED = 10, INSTALLED = 11;
    public static final int UNKNOWN = 0, PENDING = 1, STAGED = 2, COMMITTED = 3,
            ABORTED = 4, ROOM_READY = 5, DENIED = 6, SUPPORTED = 7;
    public static final UUID ZERO = new UUID(0, 0);
    private HandoffAuthorityProtocol() { }

    public static final class Message {
        public int type, state;
        public long requestId, offerId, epoch, currentEpoch;
        public boolean ownsCurrentEpoch, sourceRestored, installed, isCurrentAttempt;
        public UUID sessionId = ZERO;
        public byte[] key = new byte[32], successorKey = new byte[32], observerKey = new byte[32], digest = new byte[64];
        public String room = "";
        public Message(int type) { this.type = type; }
    }

    public static byte[] encode(Message m) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(MAGIC); out.writeByte(VERSION); out.writeByte(m.type); out.writeByte(m.state);
            out.writeLong(m.requestId); out.writeLong(m.sessionId.getMostSignificantBits());
            out.writeLong(m.sessionId.getLeastSignificantBits()); out.writeLong(m.offerId); out.writeLong(m.epoch); out.writeLong(m.currentEpoch); out.writeBoolean(m.ownsCurrentEpoch); out.writeBoolean(m.sourceRestored); out.writeBoolean(m.installed); out.writeBoolean(m.isCurrentAttempt);
            fixed(out, m.key, 32); fixed(out, m.successorKey, 32); fixed(out, m.observerKey, 32); fixed(out, m.digest, 64);
            byte[] room = m.room.getBytes(StandardCharsets.UTF_8);
            if (room.length > 32) throw new IllegalArgumentException("Room too long");
            out.writeByte(room.length); out.write(room);
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_PACKET) throw new IllegalArgumentException("Oversized authority message");
            return result;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    public static Message decode(byte[] bytes, int length) throws IOException {
        if (length > MAX_PACKET || length < 217 || length > bytes.length) throw new IOException("Invalid authority packet size");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, length));
        if (in.readByte() != MAGIC || in.readUnsignedByte() != VERSION) throw new IOException("Unsupported authority protocol");
        Message m = new Message(in.readUnsignedByte()); m.state = in.readUnsignedByte();
        m.requestId = in.readLong(); m.sessionId = new UUID(in.readLong(), in.readLong());
        m.offerId = in.readLong(); m.epoch = in.readLong(); m.currentEpoch = in.readLong();
        int owns = in.readUnsignedByte(); if (owns > 1) throw new IOException("Invalid authority ownership flag");
        m.ownsCurrentEpoch = owns == 1;
        int restored = in.readUnsignedByte(); if (restored > 1) throw new IOException("Invalid source recovery flag");
        m.sourceRestored = restored == 1;
        int installed = in.readUnsignedByte(); if (installed > 1) throw new IOException("Invalid installation flag");
        m.installed = installed == 1;
        int current = in.readUnsignedByte(); if (current > 1) throw new IOException("Invalid current attempt flag");
        m.isCurrentAttempt = current == 1;
        in.readFully(m.key); in.readFully(m.successorKey); in.readFully(m.observerKey); in.readFully(m.digest);
        int n = in.readUnsignedByte(); if (n > 32 || n != in.available()) throw new IOException("Invalid room length");
        byte[] room = new byte[n]; in.readFully(room); m.room = new String(room, StandardCharsets.UTF_8);
        if (m.type < CAPABILITIES || m.type > INSTALLED) throw new IOException("Unknown authority operation");
        return m;
    }
    private static void fixed(DataOutputStream out, byte[] bytes, int n) throws IOException {
        if (bytes == null || bytes.length != n) throw new IllegalArgumentException("Invalid fixed field");
        out.write(bytes);
    }
}
