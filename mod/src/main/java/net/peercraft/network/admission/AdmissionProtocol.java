package net.peercraft.network.admission;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Address-free admission messages; Java 8 wire contract shared by client and server. */
public final class AdmissionProtocol {
    public static final int TYPE = 0x60;
    public static final int REGISTER=1, REQUEST=2, POLL_HOST=3, ITEM=4, DECIDE=5, POLL_GUEST=6, STATUS=7, CONFIRM=8, CANCEL=9;
    public static final int PENDING=1, APPROVED=2, DENIED=3, BLOCKED=4, EXPIRED=5, CLOSED=6, CANCELLED=7, READY=8, RATE_LIMIT=9, UPDATE_REQUIRED=10, AUTH_REQUIRED=11, AUTO=12;
    public static final UUID NONE=new UUID(0,0);
    public static final class Message {
        public final int op, status, seconds;
        public final UUID id;
        public final String room, name;
        public final byte[] token, payload;
        public Message(int op, UUID id, String room, byte[] token, int status, int seconds, String name, byte[] payload) {
            this.op=op; this.id=id; this.room=room; this.token=token.clone(); this.status=status; this.seconds=seconds; this.name=name; this.payload=payload.clone();
        }
    }
    public static Message message(int op,UUID id,String room,byte[] token,int status) {
        return new Message(op,id,room,token,status,0,"",new byte[0]);
    }
    public static boolean isMessage(byte[] bytes,int length) { return length>=3 && bytes[0]==(byte)0xE1 && (bytes[1]&255)==TYPE; }
    public static byte[] encode(Message m) {
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); DataOutputStream b=new DataOutputStream(out);
            b.writeByte(0xE1); b.writeByte(TYPE); b.writeByte(1); b.writeByte(m.op); b.writeLong(m.id.getMostSignificantBits()); b.writeLong(m.id.getLeastSignificantBits());
            string(b,m.room,32); if(m.token.length!=16) throw new IllegalArgumentException("token size"); b.write(m.token);
            b.writeByte(m.status); b.writeShort(Math.max(0,Math.min(120,m.seconds))); string(b,m.name,128);
            if(m.payload.length>1000) throw new IllegalArgumentException("payload size"); b.writeShort(m.payload.length); b.write(m.payload);
            return out.toByteArray();
        } catch(IOException impossible) {throw new IllegalStateException(impossible);}
    }
    public static Message decode(byte[] bytes,int length) {
        if(length>1200 || length<0 || length>bytes.length || !isMessage(bytes,length)) throw new IllegalArgumentException("admission header");
        try {
            DataInputStream b=new DataInputStream(new ByteArrayInputStream(bytes,0,length)); b.skipBytes(2);
            if(b.readUnsignedByte()!=1) throw new IllegalArgumentException("admission version");
            int op=b.readUnsignedByte(); UUID id=new UUID(b.readLong(),b.readLong()); String room=string(b,32);
            byte[] token=new byte[16]; b.readFully(token); int status=b.readUnsignedByte(),seconds=b.readUnsignedShort();
            String name=string(b,128); int size=b.readUnsignedShort(); if(size>1000 || size!=b.available()) throw new IllegalArgumentException("admission length");
            byte[] payload=new byte[size]; b.readFully(payload); return new Message(op,id,room,token,status,seconds,name,payload);
        } catch(IOException malformed) {throw new IllegalArgumentException("admission truncated",malformed);}
    }
    private static void string(DataOutputStream b,String s,int max) throws IOException {byte[] v=s.getBytes(StandardCharsets.UTF_8); if(v.length>max)throw new IllegalArgumentException("string size"); b.writeShort(v.length);b.write(v);}
    private static String string(DataInputStream b,int max) throws IOException {int n=b.readUnsignedShort();if(n>max)throw new IllegalArgumentException("string size");byte[] v=new byte[n];b.readFully(v);return new String(v,StandardCharsets.UTF_8);}
    public static byte[] registration(byte[] inner, boolean protect) {return encode(new Message(REGISTER,NONE,"",new byte[16],protect?1:0,0,"",inner));}
    private AdmissionProtocol() { }
}
