import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.peercraft.network.p2p.*;
import net.peercraft.network.modsync.*;
import net.peercraft.client.gui.*;
import net.minecraft.client.gui.screens.*;
import sun.misc.Unsafe;

public class ClientAuditProbe {
  static Field f(Class<?> c, String s) throws Exception { Field f=c.getDeclaredField(s); f.setAccessible(true); return f; }
  static void set(Object o,String s,Object v) throws Exception { f(o.getClass(),s).set(o,v); }
  static P2PBridge bridge() throws Exception {var c=P2PBridge.class.getDeclaredConstructor();c.setAccessible(true);return c.newInstance();}
  static class Sender extends P2PSender {
    int nacks;
    Sender(){super(null);}
    public void sendData(byte[] b,String ip,int port){var p=FramedPacket.decode(b,b.length);if(p!=null&&p.type()==FramedPacket.TYPE_NACK)nacks++;}
  }
  static class Agent implements ModSyncAgent {
    boolean cancelled;
    public void run(ModSyncLink link,Outcome outcome){}
    public void cancel(){cancelled=true;}
  }
  public static void main(String[] args) throws Exception {
    ReorderBuffer r=new ReorderBuffer();r.accept(0,new byte[]{1});
    f(ReorderBuffer.class,"expectedSince").setLong(r,System.currentTimeMillis()-ReorderBuffer.GAP_TIMEOUT_MILLIS-100);
    try {r.accept(2,new byte[]{3});throw new AssertionError("Expected immediate erroneous timeout");}
    catch(ReorderBuffer.SessionBrokenException e){System.out.println("FIRST_GAP_AFTER_IDLE_REJECTED_IMMEDIATELY=true");}
    var peer=new PeerAddress(InetAddress.getLoopbackAddress(),20001);
    P2PBridge host=bridge();Sender send=new Sender();set(host,"sender",send);set(host,"isHost",true);
    ((Set<PeerAddress>)f(P2PBridge.class,"authorizedPeers").get(host)).add(peer);
    for(int i=1;i<4;i++){byte[] b=FramedPacket.encode(71,i,new byte[]{4});host.handleIncomingPacket(b,b.length,peer.host(),peer.port());}
    if(send.nacks!=0||!((Map<?,?>)f(P2PBridge.class,"hostConnectionsBySessionId").get(host)).isEmpty())throw new AssertionError();
    System.out.println("LOST_FIRST_GAME_FRAME_NO_NACK_AND_NO_SESSION=true");host.stop();
    P2PBridge client=bridge();Sender clientSend=new Sender();set(client,"sender",clientSend);set(client,"clientTargetPeer",peer);
    try(Socket socket=new Socket()){
      long id=client.beginClientSession(socket);
      for(int i:new int[]{0,2}){byte[] b=FramedPacket.encode(id,i,new byte[]{5});client.handleIncomingPacket(b,b.length,peer.host(),peer.port());}
      int first=clientSend.nacks;Thread.sleep(600);
      if(first!=1||clientSend.nacks!=first)throw new AssertionError();
      System.out.println("LOST_NACK_NOT_RETRIED_WITHOUT_NEW_DATA=true");
    }finally{client.stop();}
    Unsafe unsafe=(Unsafe)f(Unsafe.class,"theUnsafe").get(null);
    P2PBridge uiBridge=bridge();Agent agent=new Agent();
    var ctor=P2PBridge.ClientJoinAttempt.class.getDeclaredConstructor(P2PBridge.class,ModSyncAgent.class,String.class);ctor.setAccessible(true);
    var attempt=ctor.newInstance(uiBridge,agent,"code");set(uiBridge,"currentJoinAttempt",attempt);
    ((AtomicBoolean)f(P2PBridge.class,"rendezvousClientBusy").get(uiBridge)).set(true);
    f(TransportNoticeController.class,"joinAttempt").set(null,attempt);
    f(TransportNoticeController.class,"joinScreen").set(null,unsafe.allocateInstance(TitleScreen.class));
    Method tick=TransportNoticeController.class.getDeclaredMethod("tickJoin",Screen.class,boolean.class);tick.setAccessible(true);
    tick.invoke(null,unsafe.allocateInstance(ModSyncPreparingScreen.class),false);
    if(!attempt.isBusy()||agent.cancelled)throw new AssertionError("Preparing must remain active");
    tick.invoke(null,unsafe.allocateInstance(ModSyncIssuesScreen.class),false);
    if(attempt.isBusy()||!agent.cancelled)throw new AssertionError("Expected Issues screen to cancel");
    System.out.println("MODSYNC_ISSUES_SCREEN_CANCELS_ACTIVE_JOIN=true");uiBridge.stop();
  }
}
