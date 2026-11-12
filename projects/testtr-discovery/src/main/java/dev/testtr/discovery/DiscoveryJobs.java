package dev.testtr.discovery;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Playwright objects stay on their owning worker; cancellation is cooperative and bounded by operation timeouts. */
final class DiscoveryJobs implements AutoCloseable {
    interface Work { void run(AtomicBoolean cancelled) throws Exception; }
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private final Map<String,Job> jobs = new LinkedHashMap<>();
    private static final class Job {
        final String id = UUID.randomUUID().toString(), created = Instant.now().toString();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final Path output;
        volatile String status = "queued", error = "";
        volatile boolean timedOut;
        Job(Path output) { this.output = output; }
        boolean finished() { return Set.of("completed","failed","cancelled","timed-out").contains(status); }
    }
    synchronized ObjectNode start(Path output, int timeoutSeconds, Work work) {
        if (jobs.size() >= 64) throw new IllegalArgumentException("Job limit reached; restart server after collecting results");
        if (jobs.values().stream().anyMatch(j -> !j.finished() && (j.output.equals(output) || OutputPaths.html(j.output).equals(OutputPaths.html(output))))) throw new IllegalArgumentException("Output is in use by another job");
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) throw new IllegalArgumentException("timeoutSeconds must be 1..3600");
        Job job = new Job(output); jobs.put(job.id, job);
        ScheduledFuture<?> timeout = timers.schedule(() -> { if (!job.finished()) { job.timedOut = true; job.cancelled.set(true); } }, timeoutSeconds, TimeUnit.SECONDS);
        workers.submit(() -> {
            job.status = "running";
            try { if (!job.cancelled.get()) work.run(job.cancelled); job.status = job.timedOut ? "timed-out" : job.cancelled.get() ? "cancelled" : "completed"; }
            catch (Exception e) {
                job.status = job.timedOut ? "timed-out" : job.cancelled.get() ? "cancelled" : "failed";
                job.error = e instanceof IllegalArgumentException ? e.getMessage() : "Operation failed (" + e.getClass().getSimpleName() + "); inspect inputs and browser environment";
            } finally { timeout.cancel(false); }
        });
        return snapshot(job);
    }
    synchronized ObjectNode status(String id) { return snapshot(find(id)); }
    synchronized ObjectNode cancel(String id) { Job job = find(id); if (!job.finished()) job.cancelled.set(true); return snapshot(job); }
    private Job find(String id) { Job job = jobs.get(id); if (job == null) throw new IllegalArgumentException("Unknown job ID"); return job; }
    private ObjectNode snapshot(Job job) {
        ObjectNode result = JsonFiles.JSON.createObjectNode();
        result.put("jobId", job.id).put("status", job.status).put("cancelRequested", job.cancelled.get()).put("createdAt", job.created).put("output", job.output.toString());
        if (!job.error.isBlank()) result.put("error", job.error);
        return result;
    }
    @Override public void close() {
        synchronized (this) { jobs.values().forEach(j -> j.cancelled.set(true)); }
        timers.shutdownNow(); workers.shutdown();
        try { if (!workers.awaitTermination(30, TimeUnit.SECONDS)) workers.shutdownNow(); }
        catch (InterruptedException e) { workers.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
