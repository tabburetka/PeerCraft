package net.peercraft.rendezvous;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import net.peercraft.network.admission.AdmissionProtocol;
import static net.peercraft.network.admission.AdmissionProtocol.*;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionServerIntegrationTest {
    private byte[] exchange(DatagramSocket socket,int port,byte[] bytes)throws Exception{
        socket.send(new DatagramPacket(bytes,bytes.length,InetAddress.getLoopbackAddress(),port));
        DatagramPacket packet=new DatagramPacket(new byte[2048],2048);socket.receive(packet);return Arrays.copyOf(packet.getData(),packet.getLength());
    }
    private Message admission(DatagramSocket socket,int port,Message message)throws Exception{byte[] reply=exchange(socket,port,encode(message));return decode(reply,reply.length);}
    private AccountProtocol.AuthOk account(DatagramSocket socket,int port,String name)throws Exception{
        byte[] bytes=exchange(socket,port,AccountProtocol.encodeAccountRegister(name,new byte[16],new byte[32]));return AccountProtocol.decodeAuthOk(bytes,bytes.length);
    }
    @Test @Timeout(15) void rawJoinCannotBypassApprovalAndNoPeerPacketArrivesBeforeGuestConfirmation() throws Exception {
        try(RendezvousServer server=new RendezvousServer(0); DatagramSocket host=new DatagramSocket(); DatagramSocket guest=new DatagramSocket(); DatagramSocket control=new DatagramSocket()) {
            Thread thread=new Thread(()->{try{server.run();}catch(Exception ignored){}});thread.setDaemon(true);thread.start();
            long deadline=System.currentTimeMillis()+3000;while(server.getBoundPort()==0&&System.currentTimeMillis()<deadline)Thread.sleep(10);
            int port=server.getBoundPort();assertTrue(port>0);
            host.setSoTimeout(1000);guest.setSoTimeout(1000);control.setSoTimeout(1000);
            var h=account(host,port,"AdmissionHost");var g=account(guest,port,"AdmissionGuest");
            byte[] register=registration(RendezvousProtocol.encodeRegisterWithAccount(8,1,h.accountId(),h.sessionToken(),false,true,"World","26.2"),true);
            byte[] registered=exchange(host,port,register);String room=RendezvousProtocol.decodeRoomCreated(registered,registered.length).code();
            UUID id=UUID.randomUUID();
            assertEquals(PENDING,admission(guest,port,message(REQUEST,id,room,g.sessionToken(),0)).status);
            byte[] bypass=exchange(guest,port,RendezvousProtocol.encodeJoinWithAccount(room,g.sessionToken()));
            assertEquals(RendezvousProtocol.TYPE_JOIN_FAIL,RendezvousProtocol.messageType(bypass,bypass.length));
            host.setSoTimeout(150);assertThrows(SocketTimeoutException.class,()->host.receive(new DatagramPacket(new byte[2048],2048)));
            Message item=admission(control,port,message(POLL_HOST,NONE,room,h.sessionToken(),0));assertEquals(ITEM,item.op);assertEquals(id,item.id);assertEquals("AdmissionGuest",item.name);
            assertEquals(APPROVED,admission(control,port,message(DECIDE,id,room,h.sessionToken(),APPROVED)).status);
            assertThrows(SocketTimeoutException.class,()->host.receive(new DatagramPacket(new byte[2048],2048)));
            assertEquals(READY,admission(guest,port,message(CONFIRM,id,room,g.sessionToken(),0)).status);
            var connectivity=new RendezvousProtocol.ConnectivityAdvertisement(false,false,List.of());
            byte[] found=exchange(guest,port,RendezvousProtocol.encodeJoinWithAccount(room,g.sessionToken(),connectivity));
            assertEquals(RendezvousProtocol.TYPE_PEER_FOUND,RendezvousProtocol.messageType(found,found.length));
            host.setSoTimeout(1000);DatagramPacket peer=new DatagramPacket(new byte[2048],2048);host.receive(peer);
            assertEquals(RendezvousProtocol.TYPE_PEER_FOUND,RendezvousProtocol.messageType(peer.getData(),peer.getLength()));
        }
    }
}
