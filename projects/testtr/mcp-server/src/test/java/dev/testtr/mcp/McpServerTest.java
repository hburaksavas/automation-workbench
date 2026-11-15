package dev.testtr.mcp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.testtr.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {
    @Test void existingReportsAreAnalyzedWithoutMavenAndRawFailureDetailsStayLocal() throws Exception {
        Files.createDirectory(root.resolve("reports"));
        Files.writeString(root.resolve("reports/TEST-failed.xml"), """
            <testsuite><testcase name="scenario" classname="Scenario_1Test"><failure type="dev.testtr.runtime.ScenarioFailure" message="source.testtr:3 [TESTTR_DIAGNOSTIC:DATA_MISSING]">PRIVATE_PASSWORD</failure><system-out>PRIVATE_PASSWORD</system-out></testcase></testsuite>
            """);
        try (var server = new McpServer(root, null, null)) {
            ready(server);
            var response = call(server, "analyze_results", ProjectService.JSON.createObjectNode().put("reportsDirectory", "reports"));
            assertFalse(response.at("/result/isError").asBoolean());
            assertEquals("test-data", response.at("/result/structuredContent/suites/0/testcases/0/analysis/category").asText());
            assertFalse(response.toString().contains("PRIVATE_PASSWORD"));
            assertTrue(call(server, "analyze_results", ProjectService.JSON.createObjectNode().put("reportsDirectory", "../outside")).at("/result/isError").asBoolean());
        }
    }

    @TempDir Path root;
    private ObjectNode request(String method) { return ProjectService.JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", method); }
    private void ready(McpServer server) {
        ObjectNode init = request("initialize"); init.putObject("params").put("protocolVersion", "2025-11-25");
        assertEquals("testtr", server.handle(init).at("/result/serverInfo/name").asText());
        assertNull(server.handle(ProjectService.JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/initialized")));
    }
    private JsonNode call(McpServer server, String name, ObjectNode args) {
        ObjectNode req = request("tools/call"); req.putObject("params").put("name", name).set("arguments", args); return server.handle(req);
    }
    private ObjectNode fixture() throws Exception {
        Files.writeString(root.resolve("registry.json"), """
            {"pages":{"Giriş":{"url":"https://example.test","objects":{"Mesaj":{"type":"text","css":"#message"}}}}}
            """);
        Files.writeString(root.resolve("giris.testtr"), """
            Senaryo: "Giriş doğrulanır".
            "Giriş" sayfası açılır.
            "Mesaj" görünür olmalıdır.
            Senaryo sonu.
            """);
        return ProjectService.JSON.createObjectNode().put("source", "giris.testtr").put("registry", "registry.json");
    }
    @Test void generationMatchesCompilerForAllFrameworksWithoutWriting() throws Exception {
        var args = fixture();
        try (var server = new McpServer(root, null, null)) {
            ready(server);
            for (var target : ScenarioCompiler.Target.values()) {
                args.put("framework", target.name());
                var response = call(server, "generate_java", args);
                assertFalse(response.at("/result/isError").asBoolean(), response.toString());
                var compiler = new ScenarioCompiler();
                var scenario = compiler.parse(root.resolve("giris.testtr"), root.resolve("registry.json"));
                String id = ScenarioIdentity.className("giris.testtr", scenario.name());
                var metadata = java.util.Map.of("registrySha256", ObjectRegistry.load(root.resolve("registry.json")).sha256(), "compilerVersion", "0.1.0-SNAPSHOT", "framework", target.name());
                assertEquals(compiler.generate(scenario, "giris.testtr", id, target, metadata), response.at("/result/structuredContent/scenarios/0/java").asText());
            }
            assertFalse(Files.exists(root.resolve("target")));
            assertFalse(call(server, "validate_scenario", args).at("/result/structuredContent/scenarios/0").has("java"));
        }
    }
    @Test void invalidSourceReturnsToolErrorWithTurkishLocation() throws Exception {
        var args = fixture();
        Files.writeString(root.resolve("giris.testtr"), "Senaryo: \"Hatalı\".\nTanımsız adım.\nSenaryo sonu.\n");
        try (var server = new McpServer(root, null, null)) {
            ready(server); var response = call(server, "validate_scenario", args);
            assertTrue(response.at("/result/isError").asBoolean());
            assertTrue(response.at("/result/content/0/text").asText().contains("giris.testtr:2"));
        }
    }
    @Test void handshakeSchemasAndAllowListAreEnforced() throws Exception {
        try (var server = new McpServer(root, null, "job_status")) {
            assertEquals(-32002, server.handle(request("tools/list")).at("/error/code").asInt());
            ready(server);
            assertEquals(1, server.handle(request("tools/list")).at("/result/tools").size());
            assertEquals(-32602, call(server, "run_tests", ProjectService.JSON.createObjectNode()).at("/error/code").asInt());
            assertEquals(-32602, call(server, "job_status", ProjectService.JSON.createObjectNode().put("jobId", 42)).at("/error/code").asInt());
            assertEquals(-32602, call(server, "job_status", ProjectService.JSON.createObjectNode().put("jobId", "x").put("extra", true)).at("/error/code").asInt());
            assertTrue(call(server, "job_status", ProjectService.JSON.createObjectNode().put("jobId", "unknown")).at("/result/isError").asBoolean());
        }
    }
    @Test void pathsCannotEscapeThroughTraversalOrSymlink() throws Exception {
        var projects = new ProjectService(root);
        assertThrows(IllegalArgumentException.class, () -> projects.path("../outside"));
        Path external = Files.createTempDirectory("testtr-external-");
        try {
            try { Files.createSymbolicLink(root.resolve("link"), external); }
            catch (UnsupportedOperationException | IOException e) { return; } // Windows may disallow symlink creation.
            assertThrows(IllegalArgumentException.class, () -> projects.path("link/new.json"));
        } finally { Files.deleteIfExists(root.resolve("link")); Files.delete(external); }
    }
    @Test void stdioHasOnlyProtocolOutputAndRecoversFromMalformedJson() throws Exception {
        try (var server = new McpServer(root, null, null)) {
            var output = new ByteArrayOutputStream();
            server.serve(new ByteArrayInputStream(("{broken\n" + request("ping") + "\n").getBytes(StandardCharsets.UTF_8)), output);
            var lines = output.toString(StandardCharsets.UTF_8).lines().toList();
            assertEquals(2, lines.size());
            assertEquals(-32700, ProjectService.JSON.readTree(lines.get(0)).at("/error/code").asInt());
            assertTrue(ProjectService.JSON.readTree(lines.get(1)).has("result"));
        }
    }
    @Test void mavenRequiresConfiguredHomeAndValidArguments() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        try (var server = new McpServer(root, null, null)) {
            ready(server);
            var args = ProjectService.JSON.createObjectNode().put("pom", "pom.xml");
            assertTrue(call(server, "run_tests", args).at("/result/isError").asBoolean());
            args.put("timeoutSeconds", 0);
            assertEquals(-32602, call(server, "run_tests", args).at("/error/code").asInt());
        }
    }
}
