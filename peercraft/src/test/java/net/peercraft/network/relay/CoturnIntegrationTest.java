package net.peercraft.network.relay;

import com.google.gson.*;
import net.peercraft.network.turn.TurnUdpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in compatibility gate against an actual coturn process, never a public server. */
@EnabledIfEnvironmentVariable(named = "PEERCRAFT_TEST_COTURN_BIN", matches = ".+")
class CoturnIntegrationTest {
    @TempDir Path dir;
    @Test void encryptedBidirectionalDatagramsRotationRetentionAndShutdown() throws Exception {
        try (LocalCoturn turn = new LocalCoturn(dir)) {
            RelayPeerTransportIntegrationTest.Broker broker = broker(turn);
            RelayPeerTransportIntegrationTest.Events host = new RelayPeerTransportIntegrationTest.Events(), join = new RelayPeerTransportIntegrationTest.Events();
            RelayPeerTransport a = broker.route(true,host), b = broker.route(false,join);
            try {
                a.start(); b.start();
                assertTrue(host.ready.await(12,TimeUnit.SECONDS), "Host relay failed: " + host.reason.get());
                assertTrue(join.ready.await(12,TimeUnit.SECONDS), "Join relay failed: " + join.reason.get());
                byte[] payload = new byte[60_000]; new Random(2026).nextBytes(payload); payload[0]=(byte)0xe4;
                for (int i=0;i<20;i++) { a.send(payload); assertArrayEquals(payload,join.data.poll(5,TimeUnit.SECONDS)); }
                byte[] reply={1,2,3}; b.send(reply); assertArrayEquals(reply,host.data.poll(3,TimeUnit.SECONDS));
                a.retainHandoff(77).get(3,TimeUnit.SECONDS); assertEquals(1,broker.retains.get());
                broker.offset.addAndGet(721_000);
                RelayPeerTransportIntegrationTest.await(broker::hostRotated,12_000);
                a.send(payload); assertArrayEquals(payload,join.data.poll(5,TimeUnit.SECONDS));
                b.send(reply); assertArrayEquals(reply,host.data.poll(3,TimeUnit.SECONDS));
                a.releaseHandoff(77); RelayPeerTransportIntegrationTest.await(() -> broker.releases.get()==1,5000);
                broker.blocked=true;
                assertTrue(host.failed.await(5,TimeUnit.SECONDS)); assertTrue(join.failed.await(5,TimeUnit.SECONDS));
                assertThrows(IOException.class,() -> a.send(reply));
            } finally { a.close(); b.close(); }
            assertTrue(turn.process.isAlive());
        }
    }
    @Test void wrongSecretCannotAllocate() throws Exception {
        try (LocalCoturn turn = new LocalCoturn(dir);
             TurnUdpClient client = new TurnUdpClient(turn.endpoint,(System.currentTimeMillis()/1000+900)+":bad-user","wrong-password",new TurnUdpClient.Listener() {
                 public void onData(byte[] data,InetSocketAddress source) { }
                 public void onFailure(IOException failure) { }
             })) {
            assertThrows(IOException.class,client::allocate);
        }
    }
    static RelayPeerTransportIntegrationTest.Broker broker(LocalCoturn turn) {
        return new RelayPeerTransportIntegrationTest.Broker(null) {
                @Override InetSocketAddress turnEndpoint() { return turn.endpoint; }
                @Override synchronized JsonObject request(int role, String method, String path, JsonObject body) throws IOException {
                    JsonObject result = super.request(role, method, path, body);
                    if (result.has("credentials") && result.get("credentials").isJsonObject()) {
                        JsonObject credentials = result.getAsJsonObject("credentials");
                        JsonArray urls = new JsonArray(); urls.add("turn:relay.peercraft.test:3478?transport=udp"); credentials.add("urls",urls);
                        String username = credentials.get("expiresAt").getAsLong() / 1000 + ":peer-" + role + "-" + generation[role];
                        credentials.addProperty("bulkBytesPerSecond",524288);
                        credentials.addProperty("username",username); credentials.addProperty("password",turn.password(username));
                    }
                    return result;
                }
            };
    }
    static class LocalCoturn implements AutoCloseable {
        final InetSocketAddress endpoint; final Process process;
        final String secret = "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3";
        LocalCoturn(Path dir) throws Exception {
            int port;
            try (DatagramSocket reserve = new DatagramSocket(0,InetAddress.getByName("127.0.0.1"))) { port=reserve.getLocalPort(); }
            endpoint=new InetSocketAddress("127.0.0.1",port);
            Path config=dir.resolve("turnserver.conf");
            Files.write(config,("listening-ip=127.0.0.1\nrelay-ip=127.0.0.1\nlistening-port="+port
                    +"\nmin-port=52100\nmax-port=52163\nrealm=peercraft-test\nuse-auth-secret\nstatic-auth-secret="+secret
                    +"\nno-tcp\nno-tls\nno-dtls\nno-tcp-relay\nno-cli\nallow-loopback-peers\n"
                    +"relay-threads=1\nuser-quota=1\ntotal-quota=8\nmax-bps=1048576\nbps-capacity=8388608\nmax-allocate-lifetime=900\nfingerprint\nlog-file=stdout\nsimple-log\n").getBytes(StandardCharsets.UTF_8));
            try { Files.setPosixFilePermissions(config, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            process=new ProcessBuilder(System.getenv("PEERCRAFT_TEST_COTURN_BIN"),"-c",config.toString())
                    .redirectErrorStream(true).redirectOutput(dir.resolve("coturn.log").toFile()).start();
            try {
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean ready=false;
                while (process.isAlive() && System.nanoTime()<until && !ready) {
                    try (DatagramSocket probe=new DatagramSocket()) {
                        probe.connect(endpoint); probe.setSoTimeout(200);
                        byte[] id=new byte[12]; new java.security.SecureRandom().nextBytes(id);
                        byte[] bytes=ByteBuffer.allocate(20).putShort((short)1).putShort((short)0).putInt(0x2112a442).put(id).array();
                        probe.send(new DatagramPacket(bytes,bytes.length));
                        byte[] response=new byte[1024]; DatagramPacket reply=new DatagramPacket(response,response.length); probe.receive(reply);
                        ready=reply.getLength()>=20 && ByteBuffer.wrap(response).getShort()==0x0101
                                && Arrays.equals(id,Arrays.copyOfRange(response,8,20));
                    } catch (IOException retry) { }
                }
                if (!ready) throw new IOException("Local coturn did not start; inspect private test log: "+dir.resolve("coturn.log"));
            } catch (Exception failure) { close(); throw failure; }
        }
        String password(String username) throws IOException {
            try {
                Mac mac=Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA1"));
                return Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.GeneralSecurityException e) { throw new IOException("Test HMAC unavailable"); }
        }
        public void close() {
            process.destroy();
            try { if (!process.waitFor(3,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(3,TimeUnit.SECONDS); } }
            catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
    }
}
