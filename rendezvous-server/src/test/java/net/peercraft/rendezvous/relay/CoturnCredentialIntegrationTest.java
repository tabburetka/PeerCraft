package net.peercraft.rendezvous.relay;

import net.peercraft.network.turn.TurnUdpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Proves production provider credentials authenticate against real coturn and the actual client codec. */
@EnabledIfEnvironmentVariable(named="PEERCRAFT_TEST_COTURN_BIN",matches=".+")
class CoturnCredentialIntegrationTest {
    @TempDir Path dir;
    @Test void productionCredentialsAuthenticateBothAllocationsAndCarryData() throws Exception {
        String secret="d4".repeat(32); int port;
        try (DatagramSocket reserved=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"))) { port=reserved.getLocalPort(); }
        Properties properties=new Properties(); properties.setProperty("coturn.publicHost","relay.example.org");
        properties.setProperty("coturn.healthPort",Integer.toString(port));
        CoturnTurnProvider provider=new CoturnTurnProvider(properties,secret,System::currentTimeMillis);
        Path config=dir.resolve("turnserver.conf");
        Files.writeString(config,"listening-ip=127.0.0.1\nrelay-ip=127.0.0.1\nlistening-port="+port
                +"\nmin-port=52200\nmax-port=52231\nrealm=production-provider-test\nuse-auth-secret\nstatic-auth-secret="+secret
                +"\nno-tcp\nno-tls\nno-dtls\nno-tcp-relay\nno-cli\nallow-loopback-peers\nrelay-threads=1\nuser-quota=1\ntotal-quota=8\nlog-file=stdout\nsimple-log\n");
        Files.setPosixFilePermissions(config,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Process process=new ProcessBuilder(System.getenv("PEERCRAFT_TEST_COTURN_BIN"),"-c",config.toString())
                .redirectErrorStream(true).redirectOutput(dir.resolve("coturn.log").toFile()).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); boolean ready=false;
            while(process.isAlive() && System.nanoTime()<deadline && !ready) {
                try { provider.checkHealth(); ready=true; } catch(IOException retry) { }
            }
            assertTrue(ready,"Local coturn did not start; inspect private test log");
            var host=provider.issue(900,"host"); var join=provider.issue(900,"join");
            assertNotEquals(host.username(),join.username()); assertEquals(524288,host.bulkBytesPerSecond());
            InetSocketAddress endpoint=new InetSocketAddress("127.0.0.1",port);
            BlockingQueue<byte[]> hostData=new LinkedBlockingQueue<>(), joinData=new LinkedBlockingQueue<>();
            AtomicReference<IOException> failed=new AtomicReference<>();
            try (TurnUdpClient a=new TurnUdpClient(endpoint,host.username(),host.password(),host.expiresAt(),listener(hostData,failed));
                 TurnUdpClient b=new TurnUdpClient(endpoint,join.username(),join.password(),join.expiresAt(),listener(joinData,failed))) {
                var first=a.allocate(); var second=b.allocate(); a.bindPeer(second); b.bindPeer(first);
                byte[] message=new byte[1196]; new Random(83).nextBytes(message);
                a.send(message); assertArrayEquals(message,joinData.poll(3,TimeUnit.SECONDS));
                b.send(message); assertArrayEquals(message,hostData.poll(3,TimeUnit.SECONDS));
                a.refresh(); b.refresh(); assertNull(failed.get());
            }
        } finally {
            process.destroy(); if(!process.waitFor(3,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(3,TimeUnit.SECONDS); }
        }
    }
    private TurnUdpClient.Listener listener(BlockingQueue<byte[]> data,AtomicReference<IOException> failure) {
        return new TurnUdpClient.Listener() {
            public void onData(byte[] bytes,InetSocketAddress source) { data.add(bytes); }
            public void onFailure(IOException error) { failure.set(error); }
        };
    }
}
