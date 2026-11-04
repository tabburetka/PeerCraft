package net.peercraft.rendezvous.account;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SmtpMailSenderTest {
    @Test void refusesToSendCredentialsOrRecoveryCodeWithoutStartTls() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(5000);
            var commands = new CopyOnWriteArrayList<String>();
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    var output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
                    output.write("220 local fixture\r\n"); output.flush();
                    String line = input.readLine(); commands.add(line);
                    output.write("250-local fixture\r\n250 SIZE 10000\r\n"); output.flush();
                    while ((line = input.readLine()) != null) {
                        commands.add(line);
                        if (line.equals("QUIT")) { output.write("221 bye\r\n"); output.flush(); break; }
                    }
                } catch (IOException error) { throw new CompletionException(error); }
            });
            var config = new EmailConfig(true, "127.0.0.1", 0, true, null, null,
                    "127.0.0.1", listener.getLocalPort(), false, "fixture-user", "fixture-password", "accounts@example.org");
            try (SmtpMailSender sender = new SmtpMailSender(config)) {
                assertTrue(sender.enqueue(new EmailRecoveryService.Mail("player@example.org", "PeerCraft", "private recovery code")));
                peer.get(10, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (sender.failedDeliveries() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(1, sender.failedDeliveries());
                assertFalse(commands.isEmpty()); assertTrue(commands.getFirst().startsWith("EHLO "));
                assertTrue(commands.stream().allMatch(command -> command.startsWith("EHLO ") || command.equals("QUIT")));
            }
        }
    }
}
