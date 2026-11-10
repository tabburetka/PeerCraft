package net.peercraft.rendezvous.account;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Positive contract for the real SMTP sender, TLS, authentication and UTF-8 message. */
class SmtpTlsDeliveryTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(booleans={true,false})
    void authenticatedTlsDeliversUtf8RecoveryMail(boolean implicitTls) throws Exception {
        String password="local-smtp-fixture";
        Path keys=directory.resolve("smtp.p12");
        Process keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                "-genkeypair","-alias","local","-keyalg","RSA","-storetype","PKCS12","-keystore",keys.toString(),
                "-storepass",password,"-keypass",password,"-dname","CN=127.0.0.1","-ext","SAN=ip:127.0.0.1",
                "-validity","2","-noprompt").redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertTrue(keytool.waitFor(20,TimeUnit.SECONDS)); assertEquals(0,keytool.exitValue());
        KeyStore store=KeyStore.getInstance("PKCS12");
        try(InputStream input=Files.newInputStream(keys)){store.load(input,password.toCharArray());}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store,password.toCharArray());
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(store);
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);
        SSLContext previous=SSLContext.getDefault();
        try(ServerSocket listener=implicitTls ? tls.getServerSocketFactory().createServerSocket(0,1,InetAddress.getLoopbackAddress())
                : new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(8000);
            CompletableFuture<byte[]> delivered=CompletableFuture.supplyAsync(()->receive(listener,tls,implicitTls));
            SSLContext.setDefault(tls);
            EmailConfig config=new EmailConfig(true,"127.0.0.1",0,true,null,null,"127.0.0.1",listener.getLocalPort(),implicitTls,
                    "fixture-user","fixture-password","accounts@example.org");
            try(SmtpMailSender sender=new SmtpMailSender(config)) {
                var mail=new EmailRecoveryService.Mail("player@example.org","PeerCraft: Код восстановления","Code / Код: 12345678\nВаш UUID сохранён.");
                assertTrue(sender.enqueue(mail));
                byte[] bytes=delivered.get(15,TimeUnit.SECONDS);
                MimeMessage parsed=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(bytes));
                assertEquals(mail.subject(),parsed.getSubject()); assertEquals(mail.recipient(),parsed.getAllRecipients()[0].toString());
                assertEquals("accounts@example.org",parsed.getFrom()[0].toString());
                assertEquals(mail.body(),((String)parsed.getContent()).replace("\r\n","\n")); assertEquals(0,sender.failedDeliveries());
            }
        } finally { SSLContext.setDefault(previous); }
    }
    private static byte[] receive(ServerSocket listener,SSLContext tls,boolean implicitTls) {
        try(Socket socket=listener.accept()) {
            socket.setSoTimeout(8000);
            BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
            BufferedWriter output=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.US_ASCII));
            reply(output,"220 local TLS fixture"); boolean authenticated=false, encrypted=implicitTls; ByteArrayOutputStream message=null; String line;
            while((line=input.readLine())!=null) {
                if(line.startsWith("EHLO ")) reply(output,"250-local TLS fixture\r\n"+(encrypted?"":"250-STARTTLS\r\n")+"250-AUTH PLAIN LOGIN\r\n250 SIZE 100000");
                else if(line.equals("STARTTLS")) {
                    assertFalse(encrypted); reply(output,"220 begin TLS");
                    SSLSocket secure=(SSLSocket)tls.getSocketFactory().createSocket(socket,"127.0.0.1",socket.getPort(),false);
                    secure.setUseClientMode(false); secure.startHandshake(); encrypted=true;
                    input=new BufferedReader(new InputStreamReader(secure.getInputStream(),StandardCharsets.US_ASCII));
                    output=new BufferedWriter(new OutputStreamWriter(secure.getOutputStream(),StandardCharsets.US_ASCII));
                }
                else if(line.equals("AUTH LOGIN")) {
                    assertTrue(encrypted);
                    reply(output,"334 VXNlcm5hbWU6"); assertEquals("fixture-user",decode(input.readLine()));
                    reply(output,"334 UGFzc3dvcmQ6"); assertEquals("fixture-password",decode(input.readLine()));
                    authenticated=true; reply(output,"235 authenticated");
                } else if(line.startsWith("AUTH PLAIN")) {
                    assertTrue(encrypted);
                    String payload=line.length()>11?line.substring(11):null;
                    if(payload==null){reply(output,"334 ");payload=input.readLine();}
                    assertEquals("\0fixture-user\0fixture-password",decode(payload));authenticated=true;reply(output,"235 authenticated");
                } else if(line.startsWith("MAIL FROM:")) {assertTrue(authenticated);reply(output,"250 sender accepted");}
                else if(line.startsWith("RCPT TO:")) {assertTrue(line.contains("player@example.org"));reply(output,"250 recipient accepted");}
                else if(line.equals("DATA")) {
                    assertTrue(authenticated);reply(output,"354 send message");message=new ByteArrayOutputStream();
                    while((line=input.readLine())!=null&&!line.equals(".")) {
                        if(line.startsWith(".."))line=line.substring(1);
                        message.write((line+"\r\n").getBytes(StandardCharsets.US_ASCII));
                    }
                    reply(output,"250 queued");
                } else if(line.equals("QUIT")) {reply(output,"221 bye");break;}
                else throw new IllegalStateException("Unexpected SMTP command");
            }
            assertNotNull(message);return message.toByteArray();
        } catch(Exception error) {throw new CompletionException(error);}
    }
    private static String decode(String value){return new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8);}
    private static void reply(BufferedWriter output,String text)throws IOException{output.write(text+"\r\n");output.flush();}
}
