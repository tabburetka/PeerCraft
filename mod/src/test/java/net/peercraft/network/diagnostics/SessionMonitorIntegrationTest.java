package net.peercraft.network.diagnostics;

import net.peercraft.network.p2p.PeerAddress;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionMonitorIntegrationTest {
    @Test void threeRealUdpEndpointsExchangeMetricsAndProbeEachOtherWithoutChangingHost() throws Exception {
        try(Node host=new Node(true,"Host");Node a=new Node(false,"Alice");Node b=new Node(false,"Bob")) {
            host.add(a,"a");host.add(b,"b");a.add(host,"host");b.add(host,"host");
            host.monitor.publish("Host",35,1,4096,new String[]{"example-mod"});
            a.monitor.publish("Alice",-1,0,0,new String[0]);b.monitor.publish("Bob",-1,0,0,new String[0]);
            await(10000,()->host.monitor.views().size()==3&&host.monitor.views().get(1).supported);
            assertTrue(host.monitor.views().stream().anyMatch(v->v.rtt>=0));
            await(5000,()->a.monitor.views().size()==3);
            assertEquals(35,a.monitor.views().get(0).tick);
            assertEquals(1,a.monitor.views().get(0).transfers);
            host.monitor.requestComparison();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(19);
            while(System.nanoTime()<end) {
                host.monitor.publish("Host",35,1,4096,new String[]{"example-mod"});
                a.monitor.publish("Alice",-1,0,0,new String[0]);b.monitor.publish("Bob",-1,0,0,new String[0]);
                if(!host.monitor.pairs().isEmpty()&&host.monitor.pairs().get(0).samples>=5)break;
                Thread.sleep(100);
            }
            assertEquals(1,host.monitor.pairs().size());SessionMonitor.Pair pair=host.monitor.pairs().get(0);
            assertTrue(pair.samples>=5,"samples="+pair.samples+" rtt="+pair.rtt+" loss="+pair.loss);assertTrue(pair.rtt>=0,"rtt="+pair.rtt+" loss="+pair.loss);assertEquals(0,pair.loss);
            await(5000,()->a.monitor.pairs().size()==1 && a.monitor.pairs().get(0).samples>=5);
            assertEquals(3,net.peercraft.client.theme.SessionPresentation.lines("HOSTS",host.monitor,35,(key,args)->key)
                    .stream().filter("metric.rank"::equals).count());
            assertTrue(host.monitor.local().benchmark>0);
            assertTrue(a.monitor.local().benchmark>0);
            a.dropProbes=true;
            await(7000,()->host.monitor.views().stream().anyMatch(v->"a".equals(v.id)&&v.loss>0));
            assertEquals("diagnosis.files_suspected",net.peercraft.client.theme.SessionPresentation.diagnosis(host.monitor,35));
            host.peers.clear();await(4000,()->host.monitor.views().size()<=1&&host.monitor.pairs().isEmpty());
        }
    }
    static void await(long ms, java.util.function.BooleanSupplier ready) throws Exception {
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms);
        while(!ready.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(25);
        assertTrue(ready.getAsBoolean(),"Timed out waiting for measured data");
    }
    static final class Node implements AutoCloseable {
        final DatagramSocket socket=new DatagramSocket(0,InetAddress.getLoopbackAddress());
        final PeerAddress address=new PeerAddress(InetAddress.getLoopbackAddress(),socket.getLocalPort());
        final List<SessionMonitor.Peer> peers=new CopyOnWriteArrayList<>();
        final SessionMonitor monitor;final Thread receiver;volatile boolean dropProbes;
        Node(boolean host,String name)throws Exception {
            SessionMonitor.Wire wire=(peer,bytes)->{try{socket.send(new DatagramPacket(bytes,bytes.length,peer.host(),peer.port()));}catch(Exception ignored){}};
            monitor=new SessionMonitor(wire,wire,()->new ArrayList<>(peers),()->host);
            receiver=new Thread(()->{while(!socket.isClosed())try{
                byte[] data=new byte[25000];DatagramPacket p=new DatagramPacket(data,data.length);socket.receive(p);
                PeerAddress from=new PeerAddress(p.getAddress(),p.getPort());
                if(data[0]==SessionMonitor.MESH_MAGIC)monitor.receiveMesh(from,data,p.getLength());
                else if(!dropProbes || !new String(data,2,p.getLength()-2,java.nio.charset.StandardCharsets.UTF_8).contains("\"type\":1,")) monitor.receive(from,data,p.getLength());
            }catch(Exception ignored){}});receiver.setDaemon(true);receiver.start();
        }
        void add(Node other,String id){peers.add(new SessionMonitor.Peer(other.address,id,true,true));}
        public void close(){monitor.close();socket.close();}
    }
}
