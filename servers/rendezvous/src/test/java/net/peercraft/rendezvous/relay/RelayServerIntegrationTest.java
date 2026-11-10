package net.peercraft.rendezvous;

import com.google.gson.*;
import net.peercraft.rendezvous.relay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real UDP matching plus real loopback HTTP, with no Cloudflare/Mojang connection. */
@Disabled("Relay rollout postponed: production server intentionally does not start the broker")
class RelayServerIntegrationTest {
    @TempDir Path dir;
    RendezvousServer server; Thread thread; final AtomicInteger issued = new AtomicInteger(), polls = new AtomicInteger();
    @BeforeEach void start() throws Exception {
        RelayConfig config = new RelayConfig(true,"https://relay.example","127.0.0.1",0,true,null,null,
                Instant.now().minusSeconds(60),Instant.now().plusSeconds(86400),true,true,null,dir.resolve("issued.json"));
        TurnProvider fake = new TurnProvider() {
            public Credentials issue(int ttl,String identifier) { return new Credentials(List.of("turn:turn.cloudflare.com:3478?transport=udp"),
                    "user"+issued.incrementAndGet(),"secret",System.currentTimeMillis()+ttl*1000L); }
            public void revoke(String username) { }
            public Usage usage(Instant start,Instant end) { polls.incrementAndGet(); return new Usage(0,0,true); }
        };
        server = new RendezvousServer(0,System::currentTimeMillis,dir,true,config,fake);
        thread = new Thread(() -> { try { server.run(); } catch(Exception ignored) {} }); thread.setDaemon(true); thread.start();
        long deadline = System.nanoTime()+2_000_000_000L;
        while((server.getBoundPort()==0 || server.getRelayBoundPort()==0 || polls.get()==0) && System.nanoTime()<deadline) Thread.sleep(10);
        assertTrue(server.getRelayBoundPort()>0); Thread.sleep(20);
    }
    @AfterEach void stop() throws Exception { server.close(); thread.join(1000); }
    byte[] udp(DatagramSocket socket,byte[] request) throws Exception {
        socket.send(new DatagramPacket(request,request.length,InetAddress.getLoopbackAddress(),server.getBoundPort()));
        return receive(socket);
    }
    byte[] receive(DatagramSocket socket) throws Exception { byte[] data = new byte[2048]; DatagramPacket p = new DatagramPacket(data,data.length); socket.receive(p); return Arrays.copyOf(data,p.getLength()); }
    AccountProtocol.AuthOk register(DatagramSocket socket,String name) throws Exception {
        byte[] reply = udp(socket,AccountProtocol.encodeAccountRegister(name,new byte[AccountProtocol.SALT_LENGTH],new byte[AccountProtocol.PASSWORD_HASH_LENGTH]));
        return AccountProtocol.decodeAuthOk(reply,reply.length);
    }
    HttpResponse<String> post(String room,long token,UUID attempt,String role,byte[] session) throws Exception {
        JsonObject body = new JsonObject(); body.addProperty("roomCode",room); body.addProperty("pairToken",Long.toString(token));
        body.addProperty("attemptId",attempt.toString()); body.addProperty("role",role);
        body.addProperty("sessionToken",Base64.getEncoder().encodeToString(session)); body.addProperty("directChecksFailed",true);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getRelayBoundPort()+"/v1/relay/leases"))
                .header("Content-Type","application/json").header("X-PeerCraft-Client-IP","203.0.113.10")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test @Timeout(10) void actualMatchAdmitsBothRolesAndRejectsForgedAndAnonymousRequests() throws Exception {
        try(DatagramSocket h = new DatagramSocket(); DatagramSocket j = new DatagramSocket()) {
            h.setSoTimeout(2000); j.setSoTimeout(2000);
            var host = register(h,"RelayHost"); var joiner = register(j,"RelayJoiner");
            var ad = new RendezvousProtocol.ConnectivityAdvertisement(true,true,List.of());
            byte[] roomBytes = udp(h,RendezvousProtocol.encodeRegisterWithAccount(4,1,host.accountId(),host.sessionToken(),false,false,"","",ad));
            String code = RendezvousProtocol.decodeRoomCreated(roomBytes,roomBytes.length).code();
            byte[] joinBytes = udp(j,RendezvousProtocol.encodeJoinWithAccount(code,joiner.sessionToken(),new RendezvousProtocol.ConnectivityAdvertisement(true,true,List.of())));
            var joinOffer = RendezvousProtocol.decodePeerFound(joinBytes,joinBytes.length);
            byte[] hostBytes = receive(h); var hostOffer = RendezvousProtocol.decodePeerFound(hostBytes,hostBytes.length);
            assertTrue(joinOffer.networkOffer().orElseThrow().relayCapable());
            assertEquals(joinOffer.networkOffer().get().attemptId(),hostOffer.networkOffer().orElseThrow().attemptId());
            UUID attempt = joinOffer.networkOffer().get().attemptId();
            byte[] candidate = RendezvousProtocol.encodeDirectCandidate(hostOffer.networkOffer().get(),true,
                    new RendezvousProtocol.Address(InetAddress.getByName("8.8.8.8"),40000));
            h.send(new DatagramPacket(candidate,candidate.length,InetAddress.getLoopbackAddress(),server.getBoundPort()));
            assertArrayEquals(candidate,receive(j));
            assertEquals(403,post(code,joinOffer.token()+1,attempt,"host",host.sessionToken()).statusCode());
            assertEquals(403,post(code,joinOffer.token(),attempt,"joiner",host.sessionToken()).statusCode());
            var waiting = post(code,joinOffer.token(),attempt,"host",host.sessionToken()); assertEquals(200,waiting.statusCode());
            assertTrue(JsonParser.parseString(waiting.body()).getAsJsonObject().get("credentials").isJsonNull()); assertEquals(0,issued.get());
            var ready = post(code,joinOffer.token(),attempt,"joiner",joiner.sessionToken()); assertEquals(200,ready.statusCode()); assertEquals(2,issued.get());
        }
    }
    @Test @Timeout(10) void hostWithoutConsentStillGetsAuthenticatedDirectOfferButCannotUseRelay() throws Exception {
        try(DatagramSocket h = new DatagramSocket(); DatagramSocket j = new DatagramSocket()) {
            h.setSoTimeout(2000); j.setSoTimeout(2000); var host = register(h,"DirectHost"); var joiner = register(j,"DirectJoiner");
            byte[] room = udp(h,RendezvousProtocol.encodeRegisterWithAccount(4,1,host.accountId(),host.sessionToken(),false,false,"","",
                    new RendezvousProtocol.ConnectivityAdvertisement(true,false,List.of())));
            String code = RendezvousProtocol.decodeRoomCreated(room,room.length).code();
            byte[] response = udp(j,RendezvousProtocol.encodeJoinWithAccount(code,joiner.sessionToken(),new RendezvousProtocol.ConnectivityAdvertisement(true,true,List.of())));
            var found = RendezvousProtocol.decodePeerFound(response,response.length); assertFalse(found.networkOffer().orElseThrow().relayCapable());
            assertEquals(403,post(code,found.token(),found.networkOffer().get().attemptId(),"host",host.sessionToken()).statusCode()); assertEquals(0,issued.get());
        }
    }
}
