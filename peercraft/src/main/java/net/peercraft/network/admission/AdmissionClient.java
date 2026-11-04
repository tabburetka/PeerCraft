package net.peercraft.network.admission;

import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.account.AccountClient;
import static net.peercraft.network.admission.AdmissionProtocol.*;

/** Independent control socket: waiting never starts or reserves a Minecraft connection. */
public final class AdmissionClient {
    public static final AdmissionClient INSTANCE=new AdmissionClient();
    public static final class Entry {
        public final UUID id; public final String name; public final long expires;
        public Entry(UUID id,String name,long expires){this.id=id;this.name=name;this.expires=expires;}
    }
    private final Map<UUID,Entry> entries=new ConcurrentHashMap<UUID,Entry>();
    private final Map<UUID,Long> notified=new ConcurrentHashMap<UUID,Long>();
    private final Map<UUID,Entry> cancellations=new ConcurrentHashMap<UUID,Entry>();
    private final Map<UUID,Integer> decisions=new ConcurrentHashMap<UUID,Integer>();
    public volatile String hostRoom="", guestRoom="";
    public volatile UUID guestId;
    public volatile int guestStatus;
    public volatile long guestExpires, toastUntil;
    public volatile String noticeKey="";
    private volatile int guestOp=REQUEST;
    private volatile long lastReply, guestStarted;
    private volatile Runnable connect;
    private volatile boolean running;
    private AdmissionClient() { }
    public synchronized void start() {
        if(running)return;running=true;
        Thread worker=new Thread(this::run,"PeerCraft-Admission");worker.setDaemon(true);worker.start();
    }
    public List<Entry> entries(){
        long now=System.currentTimeMillis();
        entries.values().removeIf(e->e.expires<now);notified.values().removeIf(time->time<now-240_000);
        List<Entry> result=new ArrayList<Entry>();
        for(Entry e:entries.values())if(e.expires>now && !decisions.containsKey(e.id))result.add(e);
        result.sort((a,b)->a.id.compareTo(b.id)); return result;
    }
    public synchronized void host(String code){ if(!hostRoom.equals(code)){entries.clear();notified.clear();decisions.clear();}hostRoom=code;start(); }
    public synchronized void stopHost(String code){if(hostRoom.equals(code)){hostRoom="";entries.clear();notified.clear();decisions.clear();}}
    public synchronized void request(String room,Runnable onReady){
        if(guestId!=null && (guestStatus==PENDING||guestStatus==APPROVED))return;
        guestRoom=room;guestId=UUID.randomUUID();guestStatus=PENDING;guestOp=REQUEST;guestStarted=lastReply=System.currentTimeMillis();guestExpires=guestStarted+120_000;connect=onReady;start();
    }
    public synchronized void confirm(){if(guestStatus==APPROVED)guestOp=CONFIRM;}
    public synchronized void cancel(){if(guestId!=null){
        if(cancellations.size()<16)cancellations.put(guestId,new Entry(guestId,guestRoom,System.currentTimeMillis()+120_000));
        guestOp=CANCEL;guestStatus=CANCELLED;connect=null;
    }}
    public void decide(UUID id,int state){if(entries.containsKey(id))decisions.put(id,state);}
    public boolean waiting(){return guestId!=null && (guestStatus==PENDING||guestStatus==APPROVED);}
    private void run(){
        while(running) {
            checkTimeout(System.currentTimeMillis());
            try(DatagramSocket socket=net.peercraft.network.control.SecureControlSocket.forServer()) {
                String serverHost=PeerCraftConfig.rendezvousHost(); InetAddress server=InetAddress.getByName(serverHost);int port=PeerCraftConfig.rendezvousPort();
                socket.connect(server,port);socket.setSoTimeout(250);
                long next=0;
                while(server.getHostAddress()!=null) {
                    AccountClient.AccountSession session=AccountClient.INSTANCE.getCurrentSession();
                    long now=System.currentTimeMillis();
                    checkTimeout(now);
                    if(now>=next){next=now+1500;
                        cancellations.values().removeIf(e->e.expires<now);
                        if(session!=null){
                            for(Entry cancelled:cancellations.values())send(socket,message(CANCEL,cancelled.id,cancelled.name,session.sessionToken(),0));
                            if(!hostRoom.isEmpty()) {
                                send(socket,message(POLL_HOST,NONE,hostRoom,session.sessionToken(),0));
                                for(Map.Entry<UUID,Integer> d:decisions.entrySet())send(socket,message(DECIDE,d.getKey(),hostRoom,session.sessionToken(),d.getValue()));
                            }
                            UUID id=guestId;
                            if(id!=null && (waiting()||guestOp==CANCEL))send(socket,message(guestOp,id,guestRoom,session.sessionToken(),0));
                        }

                    }
                    try {
                        DatagramPacket packet=new DatagramPacket(new byte[1200],1200);socket.receive(packet);
                        receive(decode(packet.getData(),packet.getLength()));
                    } catch(SocketTimeoutException ignored) { }
                    catch(IllegalArgumentException malformed) { }
                    if(port!=PeerCraftConfig.rendezvousPort() || !serverHost.equals(PeerCraftConfig.rendezvousHost()))break;
                }
            }catch(Exception unavailable){try{Thread.sleep(1500);}catch(InterruptedException done){Thread.currentThread().interrupt();return;}}
        }
    }
    synchronized void checkTimeout(long now){
        if(waiting() && (now>guestExpires || now-lastReply>12_000)){
            guestStatus=now>guestExpires?EXPIRED:UPDATE_REQUIRED;
            noticeKey=now>guestExpires?"peercraft.admission.expired":"peercraft.admission.unavailable";
            toastUntil=now+5000;connect=null;
        }
    }
    private void send(DatagramSocket socket,Message message)throws java.io.IOException{byte[] bytes=encode(message);socket.send(new DatagramPacket(bytes,bytes.length));}
    private synchronized void receive(Message m){
        long now=System.currentTimeMillis();
        if(m.op==STATUS && cancellations.containsKey(m.id)){
            if(m.status!=PENDING && m.status!=APPROVED){cancellations.remove(m.id);if(m.id.equals(guestId)&&guestStatus==CANCELLED)guestId=null;}return;
        }
        if(m.op==ITEM && m.room.equals(hostRoom)) {
            if(m.status!=PENDING){entries.remove(m.id);decisions.remove(m.id);return;}
            if(decisions.containsKey(m.id))return;
            entries.put(m.id,new Entry(m.id,m.name,now+m.seconds*1000L));
            if(notified.putIfAbsent(m.id,now)==null){noticeKey="peercraft.admission.new_request";toastUntil=now+5000;}
        } else if(m.op==STATUS && m.room.equals(hostRoom) && decisions.containsKey(m.id)) {
            decisions.remove(m.id);entries.remove(m.id);
        } else if(m.op==STATUS && m.id.equals(guestId) && m.room.equals(guestRoom)) {
            if(guestStatus==CANCELLED && m.status!=CANCELLED)return;
            if(guestStatus==APPROVED && m.status==PENDING)return;
            if(guestStatus!=PENDING && guestStatus!=APPROVED && guestStatus!=CANCELLED)return;
            int previous=guestStatus;guestStatus=m.status;guestExpires=now+m.seconds*1000L;lastReply=now;
            if(guestOp==REQUEST && m.status==PENDING)guestOp=POLL_GUEST;
            if(m.status==APPROVED){if(guestOp!=CONFIRM)guestOp=POLL_GUEST;if(previous!=APPROVED){noticeKey="peercraft.admission.approved";toastUntil=now+5000;}}
            if(m.status==AUTO || (m.status==READY && guestOp==CONFIRM)){Runnable action=connect;connect=null;guestId=null;if(action!=null)action.run();}
            if(m.status==CANCELLED){guestId=null;}
        }
    }
}
