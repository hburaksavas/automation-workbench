package dev.testtr.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import dev.testtr.FailureAnalyzer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single owned Maven process at a time avoids shared target/Surefire races. */
final class MavenJobs implements AutoCloseable {
    private final ProjectService projects;
    private final Path mavenHome;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String,Job> jobs = new LinkedHashMap<>();
    private boolean closed;
    private static final String GOAL = "dev.testtr:testtr-maven-plugin:0.1.0-SNAPSHOT:";
    private static final class Job {
        final String id = UUID.randomUUID().toString(), created = Instant.now().toString();
        final AtomicBoolean cancel = new AtomicBoolean();
        volatile String state = "queued";
        volatile ObjectNode result;
        volatile Integer exitCode;
        Path directory;
        boolean finished() { return Set.of("completed", "failed", "cancelled", "timed-out").contains(state); }
    }
    MavenJobs(ProjectService projects, Path mavenHome) throws Exception {
        this.projects = projects;
        this.mavenHome = mavenHome == null ? null : mavenHome.toRealPath();
    }
    synchronized ObjectNode start(String operation, JsonNode args) throws Exception {
        if (closed) throw new IllegalArgumentException("Server is closing");
        if (jobs.size() >= 64) throw new IllegalArgumentException("Job limit reached; restart after collecting results");
        if (jobs.values().stream().anyMatch(j -> !j.finished())) throw new IllegalArgumentException("A Maven job is already active; wait for cleanup");
        Path pom = projects.path(args.path("pom").asText());
        if (!Files.isRegularFile(pom) || !pom.getFileName().toString().equals("pom.xml")) throw new IllegalArgumentException("pom must be an existing pom.xml");
        String base = args.path("base").asText("HEAD"), profile = args.path("profile").asText("");
        if (base.startsWith("-") || base.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid base revision");
        if (!profile.isEmpty() && !profile.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) throw new IllegalArgumentException("Invalid profile");
        int timeout = args.path("timeoutSeconds").asInt(600);
        if (timeout < 1 || timeout > 3600) throw new IllegalArgumentException("timeoutSeconds must be 1..3600");
        var command = launcher(pom.getParent());
        Job job = new Job();
        job.directory = projects.path(pom.getParent().resolve("target/testtr-mcp/" + job.id).toString());
        Path report = projects.path(job.directory.resolve("impact.json").toString());
        Path reports = projects.path(args.path("reportsDirectory").asText(pom.getParent().resolve("target/surefire-reports").toString()));
        command.addAll(List.of("--batch-mode", "--no-transfer-progress", "-f", pom.toString()));
        if (!profile.isEmpty()) command.add("-P" + profile);
        boolean impact = operation.equals("calculate_impact");
        boolean selection = args.path("selection").asBoolean(false);
        if (impact || selection) command.add(GOAL + (impact ? "impact" : "select"));
        if (!impact) command.add("test");
        command.add("-Dtesttr.impactBase=" + base);
        command.add("-Dtesttr.impactReport=" + report);
        Files.createDirectories(job.directory);
        jobs.put(job.id, job);
        worker.submit(() -> execute(job, command, pom.getParent(), reports, report, impact, timeout));
        return snapshot(job);
    }
    /** Launch Maven's JVM directly; never interpolate client arguments into cmd/PowerShell. */
    private ArrayList<String> launcher(Path module) throws Exception {
        if (mavenHome == null) throw new IllegalArgumentException("Start server with --maven-home to enable Maven operations");
        Path boot = mavenHome.resolve("boot");
        List<Path> jars;
        try (var files = Files.list(boot)) { jars = files.filter(p -> p.getFileName().toString().matches("plexus-classworlds.*\\.jar")).toList(); }
        if (jars.size() != 1 || !Files.isRegularFile(mavenHome.resolve("bin/m2.conf"))) throw new IllegalArgumentException("Invalid Maven home");
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return new ArrayList<>(List.of(java, "-Dmaven.home=" + mavenHome, "-Dmaven.multiModuleProjectDirectory=" + module,
                "-Dclassworlds.conf=" + mavenHome.resolve("bin/m2.conf"), "-classpath", jars.get(0).toString(), "org.codehaus.plexus.classworlds.launcher.Launcher"));
    }
    private void execute(Job job, List<String> command, Path module, Path reports, Path impactReport, boolean impact, int timeout) {
        Process process = null;
        Map<Long,ProcessHandle> owned = new LinkedHashMap<>();
        String terminal = "failed";
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            Map<Path,FileStamp> before = stamps(reports);
            job.state = "running";
            if (job.cancel.get()) { terminal = "cancelled"; return; }
            ProcessBuilder builder = new ProcessBuilder(command).directory(module.toFile()).redirectErrorStream(true)
                    .redirectOutput(job.directory.resolve("maven.log").toFile());
            // Inherited JVM injection options are deliberately not a second command/configuration channel.
            builder.environment().remove("JAVA_TOOL_OPTIONS"); builder.environment().remove("JDK_JAVA_OPTIONS");
            process = builder.start();
            boolean timedOut = false;
            while (true) {
                process.descendants().forEach(p -> owned.put(p.pid(), p));
                if (job.cancel.get() || System.nanoTime() >= deadline) {
                    timedOut = !job.cancel.get();
                    terminal = timedOut ? "timed-out" : "cancelled";
                    break;
                }
                if (process.waitFor(50, TimeUnit.MILLISECONDS)) {
                    job.exitCode = process.exitValue();
                    terminal = job.exitCode == 0 ? "completed" : "failed";
                    break;
                }
            }
            if (!impact && !Set.of("cancelled", "timed-out").contains(terminal)) job.result = readTests(reports, before);
            else job.result = ProjectService.JSON.createObjectNode();
            if (Files.isRegularFile(impactReport) && Files.size(impactReport) <= 4_000_000) {
                job.result.set("impact", ProjectService.JSON.readTree(impactReport.toFile()));
            }
        } catch (Exception e) {
            if (!Set.of("cancelled", "timed-out").contains(terminal)) terminal = "failed";
            job.result = ProjectService.JSON.createObjectNode().put("error", "Operation failed: " + e.getClass().getSimpleName());
        } finally {
            // Terminal state is published only after all observed owned processes have exited.
            if (process != null) {
                process.descendants().forEach(p -> owned.put(p.pid(), p));
                terminate(owned.values());
                terminate(List.of(process.toHandle()));
            }
            if (job.result == null) job.result = ProjectService.JSON.createObjectNode();
            if (Set.of("failed", "timed-out").contains(terminal) && job.result.path("analysisSummary").path("testCount").asInt() == 0) {
                var evidence = job.result.putObject("executionAnalysis").put("category", "uncertain")
                        .put("certainty", "insufficient-evidence").put("rootCause", "unconfirmed").put("automaticMaintenanceAllowed", false);
                evidence.putArray("evidenceCodes").add(terminal.equals("timed-out") ? "JOB_TIMED_OUT_WITHOUT_TEST_EVIDENCE" : "MAVEN_FAILED_WITHOUT_TEST_EVIDENCE");
            }
            job.state = terminal;
        }
    }
    private static void terminate(Collection<ProcessHandle> processes) {
        for (ProcessHandle process : processes) if (process.isAlive()) process.destroyForcibly();
        for (ProcessHandle process : processes) {
            if (process.isAlive()) process.onExit().join();
        }
    }
    private record FileStamp(long modified, long size) {}
    private Map<Path,FileStamp> stamps(Path directory) throws Exception {
        Map<Path,FileStamp> result = new LinkedHashMap<>();
        if (Files.isDirectory(directory)) try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).sorted().toList()) {
                projects.path(file.toString());
                result.put(file, new FileStamp(Files.getLastModifiedTime(file).toMillis(), Files.size(file)));
            }
        }
        return result;
    }
    private ObjectNode readTests(Path directory, Map<Path,FileStamp> before) throws Exception {
        var fresh = new ArrayList<Path>();
        for (var file : stamps(directory).entrySet()) {
            if (!file.getValue().equals(before.get(file.getKey()))) fresh.add(file.getKey());
        }
        var result = new FailureAnalyzer().analyze(fresh);
        result.put("reportsDirectory", directory.toString());
        result.put("reportScope", "files created or modified during this job; empty does not prove tests ran");
        return result;
    }
    synchronized ObjectNode status(String id) { return snapshot(find(id)); }
    synchronized ObjectNode cancel(String id) { Job job = find(id); if (!job.finished()) job.cancel.set(true); return snapshot(job); }
    synchronized ObjectNode results(String id) {
        Job job = find(id);
        if (!job.finished()) throw new IllegalArgumentException("Job is not terminal; poll job_status");
        var result = snapshot(job); result.set("results", job.result.deepCopy()); return result;
    }
    private Job find(String id) { var job = jobs.get(id); if (job == null) throw new IllegalArgumentException("Unknown job ID"); return job; }
    private ObjectNode snapshot(Job job) {
        var node = ProjectService.JSON.createObjectNode().put("jobId", job.id).put("status", job.state)
                .put("createdAt", job.created).put("cancelRequested", job.cancel.get()).put("log", job.directory.resolve("maven.log").toString());
        if (job.exitCode != null) node.put("exitCode", job.exitCode);
        return node;
    }
    @Override public void close() {
        synchronized (this) { closed = true; jobs.values().forEach(j -> { if (!j.finished()) j.cancel.set(true); }); }
        worker.shutdown();
        try { if (!worker.awaitTermination(30, TimeUnit.SECONDS)) worker.shutdownNow(); }
        catch (InterruptedException e) { worker.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
