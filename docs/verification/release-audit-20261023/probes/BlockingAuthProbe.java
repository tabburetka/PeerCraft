package net.peercraft.rendezvous.account;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.peercraft.rendezvous.*;
public class BlockingAuthProbe {
 public static void main(String[] args)throws Exception{
  Path dir=Files.createTempDirectory("peercraft-slow-auth-");
  LongSupplier clock=System::currentTimeMillis;
  CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
  AccountService service=new AccountService(new AccountStore(dir.resolve("accounts.json")),new SessionRegistry(clock),new PendingAuthRegistry(clock),
    (username,serverId)->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return Optional.empty();},clock);
  var c=RendezvousServer.class.getDeclaredConstructor(int.class,LongSupplier.class,Path.class,boolean.class);c.setAccessible(true);
  var server=c.newInstance(0,clock,dir,true);
  Field account=RendezvousServer.class.getDeclaredField("accountService");account.setAccessible(true);account.set(server,service);
  Field socketField=RendezvousServer.class.getDeclaredField("socket");socketField.setAccessible(true);
  Method run=RendezvousServer.class.getDeclaredMethod("run");run.setAccessible(true);
  System.setProperty("peercraft.analytics.httpPort","0");
  Thread t=new Thread(()->{try{run.invoke(server);}catch(Exception e){throw new RuntimeException(e);}});t.setDaemon(true);t.start();
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
  DatagramSocket udp;
  while((udp=(DatagramSocket)socketField.get(server))==null&&System.nanoTime()<deadline)Thread.sleep(10);
  if(udp==null)throw new AssertionError("No socket");int port=udp.getLocalPort();
  var challenge=(AccountService.Result.Ok<AccountService.ServerIdChallengeInfo>)service.beginLicensedAuth("AuditPlayer",InetAddress.getLoopbackAddress());
  try(DatagramSocket auth=new DatagramSocket();DatagramSocket room=new DatagramSocket()){
   byte[] confirm=AccountProtocol.encodeLicensedConfirm(challenge.value().requestId());
   auth.send(new DatagramPacket(confirm,confirm.length,InetAddress.getLoopbackAddress(),port));
   if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("Verifier not called");
   room.setSoTimeout(350);byte[] request=RendezvousProtocol.encodeRegister(1,0);
   room.send(new DatagramPacket(request,request.length,InetAddress.getLoopbackAddress(),port));
   try{room.receive(new DatagramPacket(new byte[256],256));throw new AssertionError("Unexpected reply while verifier held");}
   catch(SocketTimeoutException expected){System.out.println("SLOW_LICENSE_CHECK_BLOCKS_UNRELATED_ROOM_REQUEST=true");}
   release.countDown();room.setSoTimeout(1500);room.receive(new DatagramPacket(new byte[256],256));
   System.out.println("UNRELATED_ROOM_REPLY_ARRIVES_AFTER_LICENSE_CHECK_RELEASE=true");
  }finally{release.countDown();server.close();t.join(1000);}
 }
}
