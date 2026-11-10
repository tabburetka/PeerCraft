package net.peercraft.network.privacy;

import org.junit.jupiter.api.Test;
import org.apache.logging.log4j.Logger;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PrivateNetworkLoggerTest {
    @Test void hidesIpv4Ipv6AndMappedAddresses() {
        assertEquals("peer [IP hidden]:51000", PrivateNetworkLogger.redact("peer 203.0.113.17:51000"));
        for (String address : new String[]{"2001:db8::42", "::1", "fe80::1%eth0", "::ffff:203.0.113.17"}) {
            String safe = PrivateNetworkLogger.redact(address);
            assertFalse(safe.contains("203.0.113.17"));
            assertFalse(safe.contains("2001:db8"));
            assertFalse(safe.contains("fe80::"));
            assertFalse(safe.contains("::1"));
        }
        assertEquals("session=42 latency=17 ms", PrivateNetworkLogger.redact("session=42 latency=17 ms"));
    }
    @Test void sanitizesFormatArgumentsAndExceptionBeforeDelegating() throws Exception {
        AtomicReference<Object[]> received = new AtomicReference<>();
        Logger delegate = (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class[]{Logger.class}, (p,m,a)->{
            received.set(a); return m.getReturnType()==boolean.class ? false : null;
        });
        Logger logger = PrivateNetworkLogger.wrap(delegate);
        logger.info("peer {} port {}", InetAddress.getByName("203.0.113.17"), 51000);
        assertFalse(java.util.Arrays.deepToString(received.get()).contains("203.0.113.17"));
        assertTrue(java.util.Arrays.deepToString(received.get()).contains("51000"));
        logger.info("{} {} {}", "203.0.113.17", "2001:db8::42", 5);
        assertFalse(java.util.Arrays.deepToString(received.get()).contains("203.0.113.17"));
        assertFalse(java.util.Arrays.deepToString(received.get()).contains("2001:db8"));
        java.io.IOException failure = new java.io.IOException("to 203.0.113.17", new Exception("2001:db8::42"));
        failure.addSuppressed(new Exception("203.0.113.18"));
        logger.warn("failed", failure);
        Throwable safe=(Throwable)received.get()[1];
        assertFalse(safe.toString().contains("203.0.113.17"));
        assertTrue(safe.getStackTrace().length>0);
        assertNotNull(safe.getCause());
        assertEquals(1, safe.getSuppressed().length);
        java.io.StringWriter out = new java.io.StringWriter();
        safe.printStackTrace(new java.io.PrintWriter(out));
        assertFalse(out.toString().contains("203.0.113"));
        assertFalse(out.toString().contains("2001:db8"));
    }
}
