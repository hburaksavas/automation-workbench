package dev.testtr.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real Maven/Java subprocess acceptance tests; no browser or model API required. */
@EnabledIfSystemProperty(named = "testtr.test.mavenHome", matches = ".+")
class MavenJobsTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path root;
    private MavenJobs jobs() throws Exception { return new MavenJobs(new ProjectService(root), Path.of(System.getProperty("testtr.test.mavenHome"))); }
    private ObjectNode args() { return ProjectService.JSON.createObjectNode().put("pom", "pom.xml").put("timeoutSeconds", 90); }
    private ObjectNode await(MavenJobs jobs, String id) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(95);
        while (System.nanoTime() < deadline) {
            var status = jobs.status(id);
            if (Set.of("completed", "failed", "cancelled", "timed-out").contains(status.path("status").asText())) return jobs.results(id);
            Thread.sleep(50);
        }
        fail("Maven job did not finish"); return null;
    }
    private void fixture(String body) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>testtr.acceptance</groupId><artifactId>consumer</artifactId><version>1</version>
            <properties><maven.compiler.release>17</maven.compiler.release></properties>
            <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency></dependencies>
            <build><plugins>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version></plugin>
            </plugins></build></project>
            """);
        Files.createDirectories(root.resolve("src/test/java"));
        testBody(body);
        Files.createDirectories(root.resolve("src/test/registry"));
        Files.writeString(root.resolve("src/test/registry/page.json"), "{\"pages\":{\"Login\":{\"url\":\"https://example.test\",\"objects\":{\"Mesaj\":{\"type\":\"text\",\"css\":\"#message\"}}}}}");
        Files.createDirectories(root.resolve("src/test/testtr"));
        Files.writeString(root.resolve("src/test/testtr/example.testtr"), "Senaryo: \"Örnek\".\n\"demo\" ayarı \"Değer\" değişkenine alınır.\nSenaryo sonu.\n");
        Files.writeString(root.resolve(".gitignore"), "target/\nstarted\n");
    }
    private void testBody(String body) throws Exception {
        Files.writeString(root.resolve("src/test/java/ConsumerTest.java"), "public class ConsumerTest { @org.junit.jupiter.api.Test void sample() throws Exception { " + body + " } }");
    }
    private void git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", root.toString())); command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), output);
    }
    @Test void realMavenResultsAreFreshAndFailuresRemainFailures() throws Exception {
        fixture("System.out.println(\"DO_NOT_RETURN_SECRET\");");
        try (var jobs = jobs()) {
            String id = jobs.start("run_tests", args()).path("jobId").asText();
            assertThrows(IllegalArgumentException.class, () -> jobs.results(id));
            assertThrows(IllegalArgumentException.class, () -> jobs.start("run_tests", args()));
            var passed = await(jobs, id);
            assertEquals("completed", passed.path("status").asText(), passed.toString());
            assertEquals("passed", passed.at("/results/suites/0/testcases/0/status").asText(), passed.toString());
            assertFalse(passed.toString().contains("DO_NOT_RETURN_SECRET"));
            testBody("org.junit.jupiter.api.Assertions.fail(\"DO_NOT_RETURN_SECRET\");");
            var failed = await(jobs, jobs.start("run_tests", args()).path("jobId").asText());
            assertEquals("failed", failed.path("status").asText());
            assertEquals(1, failed.path("exitCode").asInt());
            assertEquals("failed", failed.at("/results/suites/0/testcases/0/status").asText(), failed.toString());
            assertEquals("uncertain", failed.at("/results/suites/0/testcases/0/analysis/category").asText());
            assertFalse(failed.toString().contains("DO_NOT_RETURN_SECRET"));
            assertEquals(passed, jobs.results(id)); // Later reports cannot mutate previous results.
        }
    }
    @Test void impactAndEmptySelectionUseExistingMavenPlugin() throws Exception {
        fixture("org.junit.jupiter.api.Assertions.fail(\"Unselected Java test must not run\");");
        git("init"); git("add", "."); git("-c", "user.name=Acceptance", "-c", "user.email=acceptance@example.test", "commit", "-m", "baseline");
        try (var jobs = jobs()) {
            var impact = await(jobs, jobs.start("calculate_impact", args()).path("jobId").asText());
            assertEquals("completed", impact.path("status").asText(), impact.toString());
            assertEquals("none", impact.at("/results/impact/mode").asText(), impact.toString());
            var selected = await(jobs, jobs.start("run_tests", args().put("selection", true)).path("jobId").asText());
            assertEquals("completed", selected.path("status").asText(), selected.toString());
            assertEquals(0, selected.at("/results/suites").size());
            Files.writeString(root.resolve("src/test/testtr/example.testtr"), "Senaryo: \"Değişen\".\n\"demo\" ayarı \"Değer\" değişkenine alınır.\nSenaryo sonu.\n");
            var changed = await(jobs, jobs.start("calculate_impact", args()).path("jobId").asText());
            assertEquals("selected", changed.at("/results/impact/mode").asText(), changed.toString());
            assertEquals(1, changed.at("/results/impact/selected").size());
        }
    }
    @Test void cancellationAndTimeoutCleanUpOwnedProcesses() throws Exception {
        fixture("java.nio.file.Files.writeString(java.nio.file.Path.of(\"started\"), Long.toString(ProcessHandle.current().pid())); Thread.sleep(60000);");
        try (var jobs = jobs()) {
            String id = jobs.start("run_tests", args()).path("jobId").asText();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            while (!Files.exists(root.resolve("started")) && System.nanoTime() < deadline) Thread.sleep(50);
            assertTrue(Files.exists(root.resolve("started")), jobs.status(id).toString());
            long child = Long.parseLong(Files.readString(root.resolve("started")));
            jobs.cancel(id);
            assertEquals("cancelled", await(jobs, id).path("status").asText());
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
            var timedOut = await(jobs, jobs.start("run_tests", args().put("timeoutSeconds", 1)).path("jobId").asText());
            assertEquals("timed-out", timedOut.path("status").asText());
            assertEquals("uncertain", timedOut.at("/results/executionAnalysis/category").asText());
        }
    }
    @Test void closingServerCancelsActiveJob() throws Exception {
        fixture("Thread.sleep(60000);");
        var jobs = jobs();
        String id = jobs.start("run_tests", args()).path("jobId").asText();
        jobs.close();
        assertEquals("cancelled", jobs.status(id).path("status").asText());
        assertThrows(IllegalArgumentException.class, () -> jobs.start("run_tests", args()));
    }
}
