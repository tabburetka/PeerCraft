package net.peercraft.network.handoff;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

/** New control messages never reuse the v1 packet family or meanings. */
public final class HandoffControlProtocol {
    public static final byte MAGIC = (byte) 0xE8;
    public static final int VERSION = 2, MAX_PAYLOAD = 1024, HEADER = 68, MAX_PACKET = HEADER + MAX_PAYLOAD;
    public static final int OFFER = 1, ACCEPT = 2, DECLINE = 3, MANIFEST = 4, MANIFEST_ACK = 5,
            PREFLIGHT = 6, PREPARE = 7, PREPARED = 8, VERIFIED = 9, START = 10, READY = 11, ABORT = 12, HEARTBEAT = 13, INSTALLED = 14;
    public static final class Message {
        public final int type;
        public final UUID session;
        public final long offer, epoch;
        public final byte[] authority, payload;
        public Message(int type, UUID session, long offer, long epoch, byte[] authority, byte[] payload) {
            if (type < OFFER || type > INSTALLED || session == null || epoch < 0 || authority == null
                    || authority.length != 32 || payload == null || payload.length > MAX_PAYLOAD)
                throw new IllegalArgumentException("Invalid handoff control message");
            this.type = type; this.session = session; this.offer = offer; this.epoch = epoch;
            this.authority = authority.clone(); this.payload = payload.clone();
        }
    }
    private HandoffControlProtocol() { }
    public static byte[] encode(Message m) {
        return ByteBuffer.allocate(HEADER + m.payload.length).put(MAGIC).put((byte) VERSION).put((byte) m.type).put((byte) 0)
                .putLong(m.session.getMostSignificantBits()).putLong(m.session.getLeastSignificantBits())
                .putLong(m.offer).putLong(m.epoch).put(m.authority).put(m.payload).array();
    }
    public static Message decode(byte[] bytes, int length) throws IOException {
        if (length < HEADER || length > MAX_PACKET || length > bytes.length) throw new IOException("Invalid control packet size");
        ByteBuffer in = ByteBuffer.wrap(bytes, 0, length);
        if (in.get() != MAGIC || (in.get() & 255) != VERSION) throw new IOException("Unsupported control protocol");
        int type = in.get() & 255;
        if (in.get() != 0) throw new IOException("Unsupported control flags");
        UUID session = new UUID(in.getLong(), in.getLong()); long offer = in.getLong(), epoch = in.getLong();
        byte[] authority = new byte[32], payload = new byte[length - HEADER]; in.get(authority); in.get(payload);
        try { return new Message(type, session, offer, epoch, authority, payload); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid control message", invalid); }
    }
}
