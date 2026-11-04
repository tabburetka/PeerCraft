package net.peercraft.rendezvous;

import net.peercraft.network.admission.AdmissionProtocol;
import static net.peercraft.network.admission.AdmissionProtocol.*;
import java.util.*;
import java.util.function.*;
import java.nio.file.*;
import java.io.IOException;

/** Admission decisions never carry addresses. Access is checked again at the real JOIN. */
final class AdmissionRegistry {
    private record Policy(UUID owner, boolean protect, boolean capable) { }
    private static final class Request {
        UUID id, guest, host, attempt; Object incarnation; String room; int state=PENDING; long expires;
        Request(UUID id,UUID guest,UUID host,String room,long now) {this.id=id;this.guest=guest;this.host=host;this.room=room;expires=now+120_000;}
    }
    private final RoomRegistry rooms;
    private final LongSupplier clock;
    private final BiPredicate<UUID,UUID> friends;
    private final Function<UUID,String> names;
    private final Map<String,Policy> policies=new HashMap<>();
    private final Map<UUID,Request> requests=new HashMap<>();
    private final Map<String,Long> cooldown=new HashMap<>();
    private final Set<String> blocks=new HashSet<>();
    private final Path blockFile;
    private final RateLimiter<UUID> newRequests;
    private final RateLimiter<String> ipRequests;
    AdmissionRegistry(RoomRegistry rooms, LongSupplier clock, BiPredicate<UUID,UUID> friends, Function<UUID,String> names,Path file) {
        this.rooms=rooms;this.clock=clock;this.friends=friends;this.names=names;blockFile=file;
        newRequests=new RateLimiter<>(8,60_000,clock); ipRequests=new RateLimiter<>(24,60_000,clock);
        try {if(Files.exists(file))blocks.addAll(Files.readAllLines(file));}catch(IOException e){throw new java.io.UncheckedIOException(e);}
    }
    synchronized void register(String room,UUID owner,boolean protect,boolean capable) {
        Policy old=policies.get(room);
        // A legacy packet cannot silently turn off a capable host's protection.
        if(old!=null && old.capable && !capable) return;
        policies.put(room,new Policy(owner,protect,capable)); sweep();
    }
    private String block(UUID host,UUID guest){return host+":"+guest;}
    private void refresh(Request r) {
        RoomRegistry.AdmissionRoom current=rooms.admissionRoom(r.room);
        if(current==null || current.incarnation()!=r.incarnation)r.state=CLOSED;
        else if(clock.getAsLong()>=r.expires && (r.state==PENDING || r.state==APPROVED || r.state==READY))r.state=EXPIRED;
    }
    private void sweep(){
        long now=clock.getAsLong();requests.values().removeIf(r->now>r.expires+120_000 || rooms.admissionRoom(r.room)==null);
        policies.keySet().removeIf(r->rooms.admissionRoom(r)==null);
        cooldown.values().removeIf(until->until<now);
    }
    synchronized List<Message> handle(Message m,UUID actor,String ip) {
        sweep(); RoomRegistry.AdmissionRoom room=rooms.admissionRoom(m.room);
        if(room==null)return List.of(reply(m,CLOSED,0,""));
        if(m.op==POLL_HOST) {
            if(!actor.equals(room.owner()))return List.of(reply(m,AUTH_REQUIRED,0,""));
            List<Message> result=new ArrayList<>();
            List<Request> ordered=new ArrayList<>(requests.values());
            ordered.sort(Comparator.<Request>comparingInt(r->r.state==PENDING?0:1).thenComparingLong(r->-r.expires));
            for(Request r:ordered)if(Objects.equals(r.host,actor)&&r.room.equals(m.room)) {
                refresh(r); if(result.size()<32) result.add(new Message(ITEM,r.id,r.room,new byte[16],r.state,remaining(r),names.apply(r.guest),new byte[0]));
            }
            return result; // Individual bounded datagrams; at most 16 pending in a room.
        }
        if(m.op==REQUEST) {
            if(blocks.contains(block(room.owner(),actor)))return List.of(reply(m,BLOCKED,0,""));
            if(room.friendsOnly() && (room.owner()==null || !friends.test(room.owner(),actor)))return List.of(reply(m,DENIED,0,""));
            Request existing=requests.get(m.id);
            if(existing!=null) {
                if(!existing.guest.equals(actor)||!existing.room.equals(m.room))return List.of(reply(m,DENIED,0,""));
                refresh(existing);return List.of(reply(m,existing.state,remaining(existing),""));
            }
            for(Request r:requests.values())if(r.guest.equals(actor)&&r.room.equals(m.room)) {
                refresh(r); if(r.state==PENDING || r.state==APPROVED || r.state==READY)return List.of(reply(m,RATE_LIMIT,0,""));
            }
            String key=actor+":"+m.room; long now=clock.getAsLong();
            if(cooldown.getOrDefault(key,0L)>now || requests.size()>=4096 || requests.values().stream().filter(r->r.room.equals(m.room)&&r.state==PENDING).count()>=16
                    || !newRequests.allow(actor)||!ipRequests.allow(ip))return List.of(reply(m,RATE_LIMIT,0,""));
            Policy policy=policies.get(m.room);
            boolean required=required(room,policy,actor);
            if(required && (policy==null||!policy.capable||room.owner()==null))return List.of(reply(m,UPDATE_REQUIRED,0,""));
            Request r=new Request(m.id,actor,room.owner(),m.room,now);r.incarnation=room.incarnation();
            if(!required)r.state=AUTO;
            requests.put(r.id,r);cooldown.put(key,now+30_000);
            return List.of(reply(m,r.state,120,""));
        }
        Request r=requests.get(m.id);
        if(r==null || !r.room.equals(m.room))return List.of(reply(m,EXPIRED,0,""));
        refresh(r);
        if(m.op==DECIDE) {
            if(!actor.equals(room.owner())||!actor.equals(r.host))return List.of(reply(m,AUTH_REQUIRED,0,""));
            if(r.state==PENDING) {
                if(m.status==BLOCKED) {
                    blocks.add(block(actor,r.guest)); saveBlocks();r.state=BLOCKED;
                    for(Request other:requests.values())if(actor.equals(other.host)&&r.guest.equals(other.guest))other.state=BLOCKED;
                } else if(m.status==DENIED)r.state=DENIED;
                else if(m.status==APPROVED){r.state=APPROVED;r.expires=clock.getAsLong()+120_000;}
            }
        } else {
            if(!actor.equals(r.guest))return List.of(reply(m,AUTH_REQUIRED,0,""));
            if(m.op==CANCEL && r.state!=READY)r.state=CANCELLED;
            if(m.op==CONFIRM && r.state==APPROVED){r.state=READY;r.expires=clock.getAsLong()+30_000;}
        }
        return List.of(reply(m,r.state,remaining(r),""));
    }
    private boolean required(RoomRegistry.AdmissionRoom room,Policy p,UUID guest) {
        return room.publicRoom() && (p==null || !Objects.equals(p.owner,room.owner()) || p.protect) && (room.owner()==null || !friends.test(room.owner(),guest));
    }
    synchronized boolean authorizeJoin(String code,UUID guest,UUID attempt) {
        RoomRegistry.AdmissionRoom room=rooms.admissionRoom(code);
        if(room==null)return false;
        if(blocks.contains(block(room.owner(),guest)))return false;
        if(!required(room,policies.get(code),guest))return true;
        for(Request r:requests.values())if(r.room.equals(code)&&r.guest.equals(guest)) {
            refresh(r);
            if(r.state==READY) {
                if(attempt==null)return false;
                if(r.attempt==null)r.attempt=attempt;
                return r.attempt.equals(attempt);
            }
        }
        return false;
    }
    private int remaining(Request r){return (int)Math.max(0,Math.min(120,(r.expires-clock.getAsLong()+999)/1000));}
    private Message reply(Message m,int state,int seconds,String name){return new Message(STATUS,m.id,m.room,new byte[16],state,seconds,name,new byte[0]);}
    private void saveBlocks(){
        try {Files.createDirectories(blockFile.getParent());Path temp=blockFile.resolveSibling(blockFile.getFileName()+".tmp");Files.write(temp,blocks);Files.move(temp,blockFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(IOException e){throw new java.io.UncheckedIOException(e);}
    }
}
