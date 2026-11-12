package dev.testtr.discovery;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {
    @TempDir Path temp;
    ObjectNode request(String method) { return JsonFiles.JSON.createObjectNode().put("jsonrpc","2.0").put("id",1).put("method",method); }
    void initialize(McpServer server) {
        ObjectNode init = request("initialize"); init.putObject("params").put("protocolVersion","2025-11-25");
        assertEquals("2025-11-25",server.handle(init).path("result").path("protocolVersion").asText());
        server.handle(JsonFiles.JSON.createObjectNode().put("jsonrpc","2.0").put("method","notifications/initialized"));
    }
    JsonNode call(McpServer server, String name, ObjectNode args) {
        ObjectNode req = request("tools/call"); req.putObject("params").put("name",name).set("arguments",args); return server.handle(req);
    }
    @Test void protocolLifecycleSchemasErrorsAndRootIsolation() throws Exception {
        try (McpServer server = new McpServer(temp,null)) {
            assertEquals(-32002,server.handle(request("tools/list")).path("error").path("code").asInt()); initialize(server);
            assertEquals(7, server.handle(request("tools/list")).path("result").path("tools").size());
            assertEquals(-32602,call(server,"start_discovery",JsonFiles.JSON.createObjectNode().put("url","https://example.test")).path("error").path("code").asInt());
            JsonNode outside = call(server,"read_report",JsonFiles.JSON.createObjectNode().put("path","../outside.json"));
            assertTrue(outside.path("result").path("isError").asBoolean());
            assertThrows(IllegalArgumentException.class, () -> server.path("../outside.json"));
            assertEquals(-32601, server.handle(request("unknown")).path("error").path("code").asInt());
        }
    }
    @Test void stdioEmitsOnlyJsonRpcAndAllowlistHidesDisabledTools() throws Exception {
        try (McpServer server = new McpServer(temp,"read_report")) {
            String input = "not json\n{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n";
            ByteArrayOutputStream output = new ByteArrayOutputStream(); server.serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),output);
            String[] lines = output.toString(StandardCharsets.UTF_8).strip().split("\\R"); assertEquals(3,lines.length);
            for (String line : lines) assertEquals("2.0",JsonFiles.JSON.readTree(line).path("jsonrpc").asText());
            assertEquals(1,JsonFiles.JSON.readTree(lines[2]).path("result").path("tools").size());
            assertEquals(-32602,call(server,"start_discovery",JsonFiles.JSON.createObjectNode()).path("error").path("code").asInt());
        }
    }
    @Test void realBrowserJobCanBeCancelledAndReportRead() throws Exception {
        Files.writeString(temp.resolve("page.html"),"<button id='go'>Git</button>");
        try (McpServer server = new McpServer(temp,null)) {
            initialize(server);
            JsonNode response = call(server,"start_discovery",JsonFiles.JSON.createObjectNode().put("url",temp.resolve("page.html").toUri().toString()).put("output","discovery.json").put("duration",60));
            String id = response.path("result").path("structuredContent").path("jobId").asText(); assertFalse(id.isBlank(), response.toString());
            long deadline = System.nanoTime() + 20_000_000_000L;
            while ((!Files.exists(temp.resolve("discovery.json")) || JsonFiles.read(temp.resolve("discovery.json")).path("objects").isEmpty()) && System.nanoTime() < deadline) Thread.sleep(50);
            assertTrue(Files.exists(temp.resolve("discovery.json")));
            assertFalse(JsonFiles.read(temp.resolve("discovery.json")).path("objects").isEmpty());
            ObjectNode argument = JsonFiles.JSON.createObjectNode().put("jobId",id); call(server,"stop_job",argument);
            String state;
            do {
                Thread.sleep(50); state = call(server,"job_status",argument).path("result").path("structuredContent").path("status").asText();
            } while (!state.equals("cancelled") && !state.equals("failed") && System.nanoTime() < deadline);
            assertEquals("cancelled",state);
            assertFalse(call(server,"read_report",JsonFiles.JSON.createObjectNode().put("path","discovery.json")).path("result").path("isError").asBoolean());
        }
    }
    @Test void registryToolsShareCliResultsAndApplyValidatedSelection() throws Exception {
        Path html = temp.resolve("page.html"); Files.writeString(html,"<button id='save'>Kaydet</button>");
        String url = html.toUri().toString();
        ObjectNode report = JsonFiles.JSON.createObjectNode();
        report.putArray("objects").addObject().put("url",url).put("name","Kaydet").put("type","button").put("css","#save")
            .put("mainFrame",true).put("frame","frame-1").put("quality","attribute").put("matches",1);
        JsonFiles.write(temp.resolve("discovery.json"),report); Files.writeString(temp.resolve("registry.json"),"{\"pages\":{}}");
        JsonFiles.write(temp.resolve("map.json"),JsonFiles.JSON.createObjectNode().put(url,"Demo"));
        try (McpServer server = new McpServer(temp,null)) {
            initialize(server);
            ObjectNode args = JsonFiles.JSON.createObjectNode().put("registry","registry.json").put("output","proposal.json").put("pageMap","map.json");
            args.putArray("discovery").add("discovery.json");
            JsonNode result = call(server,"suggest_registry",args);
            assertFalse(result.path("result").path("isError").asBoolean(),result.toString());
            ObjectNode cli = new RegistrySuggestions().suggest(java.util.List.of(temp.resolve("discovery.json")),temp.resolve("registry.json"),JsonFiles.read(temp.resolve("map.json")),"");
            ObjectNode mcp = (ObjectNode)JsonFiles.read(temp.resolve("proposal.json")); cli.remove("generatedAt"); mcp.remove("generatedAt"); assertEquals(cli,mcp);
            result = call(server,"validate_registry",JsonFiles.JSON.createObjectNode().put("proposal","proposal.json").put("output","validated.json"));
            String jobId = result.path("result").path("structuredContent").path("jobId").asText(); assertFalse(jobId.isBlank(),result.toString());
            long deadline = System.nanoTime()+20_000_000_000L; String status;
            do {
                Thread.sleep(50); status = call(server,"job_status",JsonFiles.JSON.createObjectNode().put("jobId",jobId)).path("result").path("structuredContent").path("status").asText();
            } while (!java.util.Set.of("completed","failed").contains(status) && System.nanoTime()<deadline);
            assertEquals("completed",status);
            JsonNode candidate = JsonFiles.read(temp.resolve("validated.json")).path("candidates").get(0);
            assertEquals("passed",candidate.path("validation").asText(),candidate.toPrettyString());
            ObjectNode apply = JsonFiles.JSON.createObjectNode().put("proposal","validated.json").put("registry","registry.json").put("output","next.json");
            apply.putArray("accept").add(candidate.path("id").asText());
            result = call(server,"apply_registry",apply); assertFalse(result.path("result").path("isError").asBoolean(),result.toString());
            assertTrue(JsonFiles.read(temp.resolve("next.json")).path("pages").path("Demo").path("objects").has("Kaydet"));
            assertTrue(JsonFiles.read(temp.resolve("registry.json")).path("pages").isEmpty());
        }
    }
}
