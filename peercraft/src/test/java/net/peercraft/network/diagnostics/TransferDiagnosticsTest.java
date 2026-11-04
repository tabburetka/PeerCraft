package net.peercraft.network.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferDiagnosticsTest {
    @Test void downloadDisappearsImmediatelyWhenClosedOrCancelled() {
        DownloadSample file=new DownloadSample("sample-mod",4096);
        try {file.progress(1024,4096);assertTrue(TransferRegistry.snapshot().stream().anyMatch(e->e.name.equals("sample-mod")&&e.remaining==3072));}
        finally {file.close();}
        assertTrue(TransferRegistry.snapshot().stream().noneMatch(e->e.name.equals("sample-mod")));
    }
    @Test void aggregatePayloadBudgetActuallyLimitsFileUploadAndCanBeDisabled() throws Exception {
        try {
            TransferTraffic.setLimitKiB(256);
            long start=System.nanoTime();
            for(int i=0;i<4;i++)TransferTraffic.acquire(65536);
            assertTrue(System.nanoTime()-start>=650_000_000L);
            TransferTraffic.setLimitKiB(0);
            assertEquals(0,TransferTraffic.limitKiB());
            TransferTraffic.acquire(1048576);
        } finally {TransferTraffic.setLimitKiB(0);}
    }
}
