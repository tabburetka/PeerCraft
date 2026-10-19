package net.peercraft.network.modsync;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Arrays;

/** Run in a separate JVM: the offline checks install a process-local fake HTTPS handler. */
public final class ModrinthJava8Probe {
    static byte[] body = new byte[] {1, 2, 3};
    static int code = 200;
    static long length = -1;
    static boolean broken;
    static int checks;
    static void check(boolean value) { if (!value) throw new AssertionError("check " + checks); checks++; }
    static ModEntry entry(long size, byte[] bytes) throws Exception {
        return new ModEntry("probe", "1", size, MessageDigest.getInstance("SHA-512").digest(bytes),
                "probe.jar", ModEntry.Env.BOTH, "", "");
    }
    public static void main(String[] args) throws Exception {
        System.out.println(System.getProperty("java.runtime.version") + " " + System.getProperty("java.vendor"));
        Path dest = Files.createTempFile("modrinth-java8-", ".part");
        try {
            if (args.length == 3) {
                byte[] hash = new byte[64];
                for (int i = 0; i < 64; i++) hash[i] = (byte) Integer.parseInt(args[2].substring(i*2,i*2+2),16);
                ModEntry e = new ModEntry("live", "1", Long.parseLong(args[1]), hash,"live.jar",ModEntry.Env.BOTH,"","");
                ModSyncPlan.PlannedMod plan = ModrinthCatalogClient.lookup(e);
                check(plan.source() == ModSyncPlan.Source.HTTP);
                check(ModrinthDownloader.download(plan.httpUrl(), dest, e, e.sizeBytes(), () -> false, (n,t) -> {}));
                System.out.println("LIVE catalog + CDN + size + SHA-512 OK " + Files.size(dest));
                return;
            }
            URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
                protected URLConnection openConnection(URL url) {
                    return new HttpsURLConnection(url) {
                        public int getResponseCode() throws IOException { if (broken) throw new IOException("network"); return code; }
                        public long getContentLengthLong() { return length; }
                        public InputStream getInputStream() { return new ByteArrayInputStream(body); }
                        public void disconnect() {} public boolean usingProxy() { return false; } public void connect() {}
                        public String getCipherSuite() { return "fake"; }
                        public Certificate[] getLocalCertificates() { return null; }
                        public Certificate[] getServerCertificates() { return null; }
                    };
                }
            } : null);
            ModEntry e = entry(3, body);
            String hash = e.sha512Hex();
            String json = "{\"status\":\"listed\",\"files\":[{\"hashes\":{\"sha512\":\""+hash+"\"},\"url\":\"https://cdn.modrinth.com/probe.jar\"}]}";
            check(ModrinthCatalogClient.parsePlan(json,e).source()==ModSyncPlan.Source.HTTP);
            check(ModrinthCatalogClient.parsePlan(json.replace(hash, new String(new char[128]).replace('\0','0')),e).source()==ModSyncPlan.Source.P2P);
            check(ModrinthCatalogClient.parsePlan(json.replace("listed","unlisted"),e).source()==ModSyncPlan.Source.P2P);
            check(ModrinthCatalogClient.parsePlan(json.replace("https:","http:"),e).source()==ModSyncPlan.Source.P2P);
            check(ModrinthCatalogClient.parsePlan("{}",e).source()==ModSyncPlan.Source.P2P);
            check(ModrinthCatalogClient.parsePlan(json.replace("cdn.modrinth.com","cdn.modrinth.com.evil"),e).source()==ModSyncPlan.Source.P2P);
            String url="https://cdn.modrinth.com/probe.jar";
            check(ModrinthDownloader.download(url,dest,e,3,()->false,(n,t)->{}));
            check(Arrays.equals(body,Files.readAllBytes(dest)));
            body=new byte[]{1,2,4}; fail(url,dest,e,3);
            body=new byte[]{1,2}; fail(url,dest,e,3);
            body=new byte[]{1,2,3,4}; fail(url,dest,e,3);
            body=new byte[]{1,2,3}; length=4; fail(url,dest,e,3); length=-1;
            body=json.getBytes("UTF-8");
            check(ModrinthCatalogClient.lookup(e).source()==ModSyncPlan.Source.HTTP);
            code=404; check(ModrinthCatalogClient.lookup(e).catalogStatus()==ModSyncPlan.CatalogStatus.NOT_FOUND);
            code=503; check(ModrinthCatalogClient.lookup(e).source()==ModSyncPlan.Source.P2P);
            code=200; broken=true; check(ModrinthCatalogClient.lookup(e).source()==ModSyncPlan.Source.P2P); broken=false;
            body=new byte[]{1,2,3};
            code=302; fail(url,dest,e,3); code=404; fail(url,dest,e,3); code=200;
            broken=true; fail(url,dest,e,3); broken=false;
            fail(url,dest,e,2); fail("http://cdn.modrinth.com/probe.jar",dest,e,3);
            check(!ModrinthDownloader.download(url,dest,e,3,()->true,(n,t)->{}));
            check(!Files.exists(dest));
            check(!ModrinthDownloader.download(url,dest,e,3,()->false,(n,t)->{throw new RuntimeException("cancel");}));
            check(!Files.exists(dest));
            System.out.println("OFFLINE " + checks + " checks passed");
        } finally { Files.deleteIfExists(dest); }
    }
    static void fail(String url,Path dest,ModEntry e,long max) throws Exception {
        check(!ModrinthDownloader.download(url,dest,e,max,()->false,(n,t)->{}));
        check(!Files.exists(dest));
    }
}
