package net.peercraft.network.admission;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static net.peercraft.network.admission.AdmissionProtocol.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class AdmissionClientTest {
    private AdmissionClient client() throws Exception {
        var ctor=AdmissionClient.class.getDeclaredConstructor();ctor.setAccessible(true);AdmissionClient c=ctor.newInstance();
        c.guestId=UUID.randomUUID();c.guestRoom="ABCDEF";c.guestStatus=PENDING;return c;
    }
    private void field(AdmissionClient c,String name,Object value)throws Exception{var f=AdmissionClient.class.getDeclaredField(name);f.setAccessible(true);f.set(c,value);}
    private Object field(AdmissionClient c,String name)throws Exception{var f=AdmissionClient.class.getDeclaredField(name);f.setAccessible(true);return f.get(c);}
    private void receive(AdmissionClient c,int status)throws Exception{
        var m=AdmissionClient.class.getDeclaredMethod("receive",Message.class);m.setAccessible(true);
        m.invoke(c,new Message(STATUS,c.guestId,c.guestRoom,new byte[16],status,120,"",new byte[0]));
    }
    @Test void delayedApprovalCannotEraseConfirmationAndCallbackRunsOnce() throws Exception {
        AdmissionClient c=client();receive(c,APPROVED);c.confirm();receive(c,APPROVED);
        assertEquals(CONFIRM,field(c,"guestOp"));
        AtomicInteger called=new AtomicInteger();field(c,"connect",(Runnable)called::incrementAndGet);receive(c,READY);
        assertEquals(1,called.get());assertNull(c.guestId);
    }
    @Test void delayedPendingCannotUndoApprovalAndTimeoutCannotResurrectRequest() throws Exception {
        AdmissionClient c=client();receive(c,APPROVED);receive(c,PENDING);assertEquals(APPROVED,c.guestStatus);
        c.guestStatus=UPDATE_REQUIRED;receive(c,APPROVED);assertEquals(UPDATE_REQUIRED,c.guestStatus);
    }
    @Test void cancellationCannotStartAConnection() throws Exception {
        AdmissionClient c=client();AtomicInteger called=new AtomicInteger();field(c,"connect",(Runnable)called::incrementAndGet);
        c.cancel();receive(c,APPROVED);assertEquals(CANCELLED,c.guestStatus);assertEquals(0,called.get());
    }
    @Test void unavailableSocketStillTimesOutAndCancelledRequestSurvivesStartingAnother() throws Exception {
        AdmissionClient c=client();c.guestExpires=System.currentTimeMillis()+120_000;field(c,"lastReply",System.currentTimeMillis()-13_000);
        c.checkTimeout(System.currentTimeMillis());assertEquals(UPDATE_REQUIRED,c.guestStatus);
        c.guestStatus=PENDING;c.cancel();assertEquals(1,((java.util.Map<?,?>)field(c,"cancellations")).size());
    }
    @Test void latePendingReplyCannotAcknowledgeCancellation() throws Exception {
        AdmissionClient c=client();c.cancel();receive(c,PENDING);
        assertEquals(1,((java.util.Map<?,?>)field(c,"cancellations")).size());
        receive(c,CANCELLED);assertEquals(0,((java.util.Map<?,?>)field(c,"cancellations")).size());assertNull(c.guestId);
    }
}
