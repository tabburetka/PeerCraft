package net.peercraft.network.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LinkMetricsTest {
    @Test void lateAndDuplicateRepliesDoNotHideLoss() {
        LinkMetrics m=new LinkMetrics();
        m.sent(1,1);m.sent(2,1);m.reply(1,10_000_001);m.reply(1,11_000_001);
        assertEquals(0,m.snapshot(1_000_000_001).loss);
        m.reply(2,3_000_000_002L);
        LinkMetrics.Snapshot s=m.snapshot(3_000_000_003L);
        assertEquals(50,s.loss);assertEquals(2,s.samples);assertEquals(10,s.rtt);assertEquals(-1,s.jitter);
    }
    @Test void measuresRollingJitterAndRatesAndBoundedWindow() {
        LinkMetrics m=new LinkMetrics();
        m.snapshot(1);m.traffic(true,2048);m.traffic(false,1024);m.queued(8192);m.retry();
        for(int i=0;i<40;i++){long t=1L+i*100_000_000L;m.sent(i,t);m.reply(i,t+(i%2==0?10:20)*1_000_000L);}
        LinkMetrics.Snapshot s=m.snapshot(4_000_000_001L);
        assertEquals(30,s.samples);assertEquals(15,s.rtt);assertEquals(10,s.jitter);assertEquals(512,s.upload);assertEquals(256,s.download);assertEquals(8192,s.queuedBytes);assertEquals(1,s.retries);
    }
}
