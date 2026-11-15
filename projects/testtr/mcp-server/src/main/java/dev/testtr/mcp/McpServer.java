package dev.testtr.mcp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Local newline-delimited JSON-RPC stdio adapter. Business operations are shared with CLI. */
public final class McpServer implements AutoCloseable {
    private final Path root;
    private final MavenJobs jobs;
    private final Map<String,ObjectNode> definitions = new LinkedHashMap<>();
    private boolean initialized, ready;
    private final ProjectService projects;

    public McpServer(Path root, Path mavenHome, String allowTools) throws Exception {
        this.root = root.toRealPath();
        this.projects = new ProjectService(this.root);
        this.jobs = new MavenJobs(projects, mavenHome);
        define("validate_scenario", "Validate one TestTR source and check framework generation without writing files. Explicit paths; does not read POM configuration.", "source!,registry!,helpers,requirements,framework,relativeSource");
        define("generate_java", "Return Java previews using the existing compiler. relativeSource must match the path relative to the Maven source directory for identical IDs.", "source!,registry!,helpers,requirements,framework,relativeSource");
        define("calculate_impact", "Start Maven impact analysis using the project's POM; returns job ID.", "pom!,base,profile,timeoutSeconds");
        define("run_tests", "Run Maven tests, optionally selecting changed TestTR scenarios. Executes trusted project code; returns job ID.", "pom!,base,profile,selection,timeoutSeconds,reportsDirectory");
        define("job_status", "Read job state and cancellation request.", "jobId!");
        define("stop_job", "Cancel an owned Maven process tree. Poll until terminal state confirms cleanup.", "jobId!");
        define("read_results", "Read immutable structured results for a terminal job. Does not return raw Maven logs or XML system properties.", "jobId!");
        define("analyze_results", "Classify existing JUnit reports using typed evidence only; root cause remains unconfirmed. No tests or Registry are modified.", "reportsDirectory!");
        if (allowTools != null) {
            Set<String> allowed = new HashSet<>(Arrays.asList(allowTools.split(",")));
            if (!definitions.keySet().containsAll(allowed)) throw new IllegalArgumentException("Unknown allow-tools entry");
            definitions.keySet().retainAll(allowed);
        }
    }
    private void define(String name, String description, String fields) {
        ObjectNode def = ProjectService.JSON.createObjectNode(); def.put("name", name).put("description", description);
        ObjectNode schema = def.putObject("inputSchema"); schema.put("type", "object").put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties"); ArrayNode required = schema.putArray("required");
        for (String field : fields.split(",")) {
            boolean needed = field.endsWith("!"); String key = field.replace("!", "");
            ObjectNode prop = props.putObject(key);
            if (key.equals("selection")) prop.put("type", "boolean");
            else if (key.equals("timeoutSeconds")) prop.put("type", "integer").put("minimum", 1).put("maximum", 3600);
            else prop.put("type", "string").put("minLength", 1);
            if (needed) required.add(key);
        }
        definitions.put(name, def);
    }
    public void serve(InputStream input, OutputStream output) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        for (String line; (line = reader.readLine()) != null;) {
            ObjectNode response;
            try { response = handle(ProjectService.JSON.readTree(line)); }
            catch (Exception e) { response = error(NullNode.instance, -32700, "Parse error"); }
            if (response != null) { writer.write(ProjectService.JSON.writeValueAsString(response)); writer.newLine(); writer.flush(); }
        }
    }
    ObjectNode handle(JsonNode request) {
        JsonNode id = request == null ? NullNode.instance : request.path("id");
        if (request == null || !request.isObject() || !"2.0".equals(request.path("jsonrpc").asText()) || !request.path("method").isTextual()
            || (!id.isMissingNode() && !id.isTextual() && !id.isIntegralNumber())) return error(NullNode.instance, -32600, "Invalid Request");
        String method = request.path("method").asText();
        if (id.isMissingNode()) { if (method.equals("notifications/initialized") && initialized) ready = true; return null; }
        try {
            JsonNode result;
            if (method.equals("initialize")) {
                if (initialized) return error(id, -32600, "Already initialized");
                initialized = true;
                ObjectNode init = ProjectService.JSON.createObjectNode();
                String version = request.path("params").path("protocolVersion").asText();
                init.put("protocolVersion", Set.of("2024-11-05","2025-03-26","2025-06-18","2025-11-25").contains(version) ? version : "2025-11-25");
                init.putObject("capabilities").putObject("tools").put("listChanged", false);
                init.putObject("serverInfo").put("name", "testtr").put("version", "0.1.0");
                result = init;
            } else if (method.equals("ping")) result = ProjectService.JSON.createObjectNode();
            else if (!ready) return error(id, -32002, "Initialize and send notifications/initialized first");
            else if (method.equals("tools/list")) {
                ObjectNode list = ProjectService.JSON.createObjectNode(); list.set("tools", ProjectService.JSON.valueToTree(definitions.values())); result = list;
            } else if (method.equals("tools/call")) {
                String name = request.path("params").path("name").asText();
                if (!definitions.containsKey(name)) return error(id, -32602, "Unknown or disabled tool");
                JsonNode args = request.path("params").path("arguments");
                if (args.isMissingNode()) args = ProjectService.JSON.createObjectNode();
                try { validateArgs(name, args); } catch (IllegalArgumentException e) { return error(id, -32602, e.getMessage()); }
                ObjectNode tool = ProjectService.JSON.createObjectNode();
                try {
                    JsonNode value = call(name, args); tool.set("structuredContent", value);
                    tool.putArray("content").addObject().put("type", "text").put("text", ProjectService.JSON.writeValueAsString(value));
                    tool.put("isError", false);
                } catch (Exception e) {
                    tool.put("isError", true).putArray("content").addObject().put("type", "text").put("text", e instanceof IllegalArgumentException ? e.getMessage() : "Operation failed: " + e.getClass().getSimpleName());
                }
                result = tool;
            } else return error(id, -32601, "Method not found");
            ObjectNode response = ProjectService.JSON.createObjectNode(); response.put("jsonrpc", "2.0"); response.set("id", id); response.set("result", result); return response;
        } catch (Exception e) { return error(id, -32603, "Internal error"); }
    }
    private void validateArgs(String name, JsonNode args) {
        if (!args.isObject()) throw new IllegalArgumentException("arguments must be an object");
        JsonNode schema = definitions.get(name).path("inputSchema"), properties = schema.path("properties");
        for (JsonNode required : schema.path("required")) if (!args.has(required.asText())) throw new IllegalArgumentException("Required argument: " + required.asText());
        args.fields().forEachRemaining(e -> {
            JsonNode prop = properties.path(e.getKey()), v = e.getValue();
            if (prop.isMissingNode()) throw new IllegalArgumentException("Unknown argument: " + e.getKey());
            boolean valid = switch(prop.path("type").asText()) {
                case "string" -> v.isTextual() && !v.asText().isBlank();
                case "boolean" -> v.isBoolean();
                case "integer" -> v.isIntegralNumber() && v.canConvertToInt() && v.asInt() >= 1 && v.asInt() <= 3600;
                case "array" -> v.isArray() && !v.isEmpty();
                default -> false;
            };
            if (v.isArray()) for (JsonNode item : v) valid &= item.isTextual() && !item.asText().isBlank();
            if (!valid) throw new IllegalArgumentException("Invalid argument: " + e.getKey());
        });
    }
    private JsonNode call(String name, JsonNode args) throws Exception {
        return switch (name) {
            case "validate_scenario" -> projects.compile(args, false);
            case "generate_java" -> projects.compile(args, true);
            case "analyze_results" -> projects.analyze(args);
            case "job_status" -> jobs.status(args.path("jobId").asText());
            case "stop_job" -> jobs.cancel(args.path("jobId").asText());
            case "read_results" -> jobs.results(args.path("jobId").asText());
            case "calculate_impact", "run_tests" -> jobs.start(name, args);
            default -> throw new IllegalArgumentException("Unknown tool");
        };
    }
    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = ProjectService.JSON.createObjectNode(); response.put("jsonrpc", "2.0"); response.set("id", id);
        response.putObject("error").put("code", code).put("message", message); return response;
    }
    @Override public void close() { jobs.close(); }
}
