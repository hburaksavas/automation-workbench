package dev.testtr.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryJobsTest {
    @TempDir Path temp;
    @Test void timeoutWaitsForCleanupAndRejectsConcurrentOutputWriters() throws Exception {
        try (DiscoveryJobs jobs = new DiscoveryJobs()) {
            CountDownLatch started = new CountDownLatch(1), cleaned = new CountDownLatch(1);
            String id = jobs.start(temp.resolve("report.json"),1, cancel -> {
                started.countDown();
                try { while (!cancel.get()) Thread.sleep(10); } finally { cleaned.countDown(); }
            }).path("jobId").asText();
            assertTrue(started.await(3,TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class, () -> jobs.start(temp.resolve("report.json"),1,c -> {}));
            assertTrue(cleaned.await(4,TimeUnit.SECONDS));
            long deadline = System.nanoTime()+1_000_000_000L;
            while (!"timed-out".equals(jobs.status(id).path("status").asText()) && System.nanoTime()<deadline) Thread.sleep(10);
            assertEquals("timed-out",jobs.status(id).path("status").asText());
        }
    }
}
