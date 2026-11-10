package net.peercraft.rendezvous;

import net.peercraft.network.admission.AdmissionProtocol;
import static net.peercraft.network.admission.AdmissionProtocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionRegistryTest {
    @TempDir Path dir;
    AtomicLong clock=new AtomicLong(1000);
    RoomRegistry rooms=new RoomRegistry(clock::get);
    UUID host=UUID.randomUUID(),guest=UUID.randomUUID(),other=UUID.randomUUID();
    String code;
    AdmissionRegistry registry;
    @BeforeEach void setup() throws Exception {
        code=((RoomRegistry.Registered)rooms.register(new RendezvousProtocol.Address(InetAddress.getLoopbackAddress(),5000),8,1,Optional.of(host),false,true,"World")).code();
        registry=new AdmissionRegistry(rooms,clock::get,(a,b)->false,id->"Player",dir.resolve("blocks"));
        registry.register(code,host,true,true);
    }
    Message msg(int op,UUID id,int state){return message(op,id,code,new byte[16],state);}
    Message send(int op,UUID id,int state,UUID actor){return registry.handle(msg(op,id,state),actor,"test-ip").getFirst();}
    @Test void permissionAndGuestConfirmationAreBothRequired() {
        UUID id=UUID.randomUUID(),attempt=UUID.randomUUID();
        assertFalse(registry.authorizeJoin(code,guest,attempt));
        assertEquals(PENDING,send(REQUEST,id,0,guest).status);
        assertEquals(AUTH_REQUIRED,send(DECIDE,id,APPROVED,other).status);
        assertFalse(registry.authorizeJoin(code,guest,attempt));
        assertEquals(APPROVED,send(DECIDE,id,APPROVED,host).status);
        assertFalse(registry.authorizeJoin(code,guest,attempt));
        assertEquals(READY,send(CONFIRM,id,0,guest).status);
        assertTrue(registry.authorizeJoin(code,guest,attempt));
        assertFalse(registry.authorizeJoin(code,guest,UUID.randomUUID()));
        assertFalse(registry.authorizeJoin(code,other,attempt));
    }
    @Test void retryDoesNotExtendRequestOrApprovalAndCancelIsTerminal() {
        UUID id=UUID.randomUUID();assertEquals(PENDING,send(REQUEST,id,0,guest).status);
        clock.addAndGet(30_000);assertEquals(90,send(REQUEST,id,0,guest).seconds);
        assertEquals(CANCELLED,send(CANCEL,id,0,guest).status);
        assertEquals(CANCELLED,send(DECIDE,id,APPROVED,host).status);
        assertFalse(registry.authorizeJoin(code,guest,UUID.randomUUID()));
    }
    @Test void blockPersistsAndOptOutCannotBypassIt() {
        UUID id=UUID.randomUUID();send(REQUEST,id,0,guest);send(DECIDE,id,BLOCKED,host);
        registry=new AdmissionRegistry(rooms,clock::get,(a,b)->false,x->"Player",dir.resolve("blocks"));registry.register(code,host,false,true);
        assertFalse(registry.authorizeJoin(code,guest,UUID.randomUUID()));
        assertEquals(BLOCKED,send(REQUEST,UUID.randomUUID(),0,guest).status);
    }
    @Test void legacyRegisterCannotDisableProtectionAndFriendsCanJoin() {
        registry.register(code,host,false,false);
        assertFalse(registry.authorizeJoin(code,guest,UUID.randomUUID()));
        registry=new AdmissionRegistry(rooms,clock::get,(a,b)->b.equals(guest),x->"Player",dir.resolve("blocks"));registry.register(code,host,true,true);
        assertTrue(registry.authorizeJoin(code,guest,UUID.randomUUID()));
        assertEquals(AUTO,send(REQUEST,UUID.randomUUID(),0,guest).status);
    }
    @Test void wireIsBoundedAndDoesNotContainPeerEndpoints() {
        Message m=msg(REQUEST,UUID.randomUUID(),0);byte[] bytes=encode(m);
        assertEquals(m.id,decode(bytes,bytes.length).id);
        assertThrows(IllegalArgumentException.class,()->decode(Arrays.copyOf(bytes,bytes.length-1),bytes.length-1));
        assertThrows(IllegalArgumentException.class,()->encode(new Message(REGISTER,NONE,"",new byte[16],0,0,"",new byte[1001])));
    }
    @Test void roomClosureAndApprovalExpiryRevokeJoin() throws Exception {
        UUID id=UUID.randomUUID();send(REQUEST,id,0,guest);send(DECIDE,id,APPROVED,host);send(CONFIRM,id,0,guest);
        clock.addAndGet(30_001);
        assertFalse(registry.authorizeJoin(code,guest,UUID.randomUUID()));
        assertEquals(EXPIRED,send(POLL_GUEST,id,0,guest).status);
        clock.addAndGet(10_000);
        assertEquals(CLOSED,send(POLL_GUEST,id,0,guest).status);
    }
}
