package dev.testtr.discovery;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Local newline-delimited JSON-RPC stdio adapter. Business operations are shared with CLI. */
public final class McpServer implements AutoCloseable {
    private final Path root;
    private final DiscoveryJobs jobs = new DiscoveryJobs();
    private final Map<String,ObjectNode> definitions = new LinkedHashMap<>();
    private boolean initialized, ready;
    private final RegistrySuggestions suggestions = new RegistrySuggestions();

    public McpServer(Path root, String allowTools) throws Exception {
        this.root = root.toRealPath();
        if (!Files.isDirectory(this.root)) throw new IllegalArgumentException("Root must be a directory");
        define("start_discovery", "Start browser discovery; returns a job ID. Poll job_status, then read_report.",
            "url!,output!,browser,channel,connect,cdp,headless,duration,flow,timeoutSeconds");
        define("job_status", "Read discovery or validation job state.", "jobId!");
        define("stop_job", "Request cancellation; poll until terminal state confirms resource cleanup.", "jobId!");
        define("read_report", "Read a discovery report or proposal JSON within project root.", "path!");
        define("suggest_registry", "Compare discovery reports to Registry; write JSON proposal and HTML diff. Registry is unchanged.", "discovery!,registry!,output!,pageMap,baseUrl");
        define("validate_registry", "Validate proposal locators in two isolated contexts; optional flow runs twice per URL. Returns job ID.", "proposal!,output!,flow,browser,channel,connect,cdp,headless,timeoutSeconds");
        define("apply_registry", "Apply explicitly accepted, browser-validated candidate IDs to a new Registry snapshot. Never overwrites source.", "proposal!,registry!,accept!,output!");
        if (allowTools != null) {
            Set<String> allowed = new HashSet<>(Arrays.asList(allowTools.split(",")));
            if (!definitions.keySet().containsAll(allowed)) throw new IllegalArgumentException("Unknown allow-tools entry");
            definitions.keySet().retainAll(allowed);
        }
    }
    private void define(String name, String description, String fields) {
        ObjectNode def = JsonFiles.JSON.createObjectNode(); def.put("name", name).put("description", description);
        ObjectNode schema = def.putObject("inputSchema"); schema.put("type", "object").put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties"); ArrayNode required = schema.putArray("required");
        for (String field : fields.split(",")) {
            boolean needed = field.endsWith("!"); String key = field.replace("!", "");
            ObjectNode prop = props.putObject(key);
            if (Set.of("discovery", "accept").contains(key)) { prop.put("type", "array").put("minItems", 1); prop.putObject("items").put("type", "string"); }
            else if (key.equals("headless")) prop.put("type", "boolean");
            else if (Set.of("duration","timeoutSeconds").contains(key)) prop.put("type", "integer").put("minimum", 1).put("maximum", 3600);
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
            try { response = handle(JsonFiles.JSON.readTree(line)); }
            catch (Exception e) { response = error(NullNode.instance, -32700, "Parse error"); }
            if (response != null) { writer.write(JsonFiles.JSON.writeValueAsString(response)); writer.newLine(); writer.flush(); }
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
                ObjectNode init = JsonFiles.JSON.createObjectNode();
                String version = request.path("params").path("protocolVersion").asText();
                init.put("protocolVersion", Set.of("2024-11-05","2025-03-26","2025-06-18","2025-11-25").contains(version) ? version : "2025-11-25");
                init.putObject("capabilities").putObject("tools").put("listChanged", false);
                init.putObject("serverInfo").put("name", "testtr-discovery").put("version", "0.1.0");
                result = init;
            } else if (method.equals("ping")) result = JsonFiles.JSON.createObjectNode();
            else if (!ready) return error(id, -32002, "Initialize and send notifications/initialized first");
            else if (method.equals("tools/list")) {
                ObjectNode list = JsonFiles.JSON.createObjectNode(); list.set("tools", JsonFiles.JSON.valueToTree(definitions.values())); result = list;
            } else if (method.equals("tools/call")) {
                String name = request.path("params").path("name").asText();
                if (!definitions.containsKey(name)) return error(id, -32602, "Unknown or disabled tool");
                JsonNode args = request.path("params").path("arguments");
                if (args.isMissingNode()) args = JsonFiles.JSON.createObjectNode();
                try { validateArgs(name, args); } catch (IllegalArgumentException e) { return error(id, -32602, e.getMessage()); }
                ObjectNode tool = JsonFiles.JSON.createObjectNode();
                try {
                    JsonNode value = call(name, args); tool.set("structuredContent", value);
                    tool.putArray("content").addObject().put("type", "text").put("text", JsonFiles.JSON.writeValueAsString(value));
                    tool.put("isError", false);
                } catch (Exception e) {
                    tool.put("isError", true).putArray("content").addObject().put("type", "text").put("text", e instanceof IllegalArgumentException ? e.getMessage() : "Operation failed: " + e.getClass().getSimpleName());
                }
                result = tool;
            } else return error(id, -32601, "Method not found");
            ObjectNode response = JsonFiles.JSON.createObjectNode(); response.put("jsonrpc", "2.0"); response.set("id", id); response.set("result", result); return response;
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
        switch (name) {
            case "job_status": return jobs.status(args.path("jobId").asText());
            case "stop_job": return jobs.cancel(args.path("jobId").asText());
            case "read_report": {
                ObjectNode result = JsonFiles.JSON.createObjectNode(); result.set("report", JsonFiles.read(path(args.path("path").asText()))); return result;
            }
            case "start_discovery": {
                Map<String,String> options = browserOptions(args);
                checkUrl(args.path("url").asText()); options.put("url", args.path("url").asText());
                Path output = path(args.path("output").asText()); guardOutput(output, args, "flow"); options.put("output", output.toString());
                options.put("duration", args.path("duration").asText("300")); options.putIfAbsent("headless", "true");
                return jobs.start(output, args.path("timeoutSeconds").asInt(600), cancel -> new DiscoveryRunner().run(options, cancel));
            }
            case "suggest_registry": {
                List<Path> reports = new ArrayList<>(); for (JsonNode p : args.path("discovery")) reports.add(path(p.asText()));
                Path output = path(args.path("output").asText()); guardOutput(output, args, "registry", "pageMap");
                OutputPaths.protect(output, reports);
                JsonNode map = args.has("pageMap") ? JsonFiles.read(path(args.path("pageMap").asText())) : JsonFiles.JSON.createObjectNode();
                JsonNode proposal = suggestions.suggest(reports, registryPath(args.path("registry").asText()), map, args.path("baseUrl").asText());
                suggestions.save(output, proposal); return proposal;
            }
            case "validate_registry": {
                Path proposal = path(args.path("proposal").asText()), output = path(args.path("output").asText());
                guardOutput(output, args, "flow", "proposal");
                for (JsonNode c : JsonFiles.read(proposal).path("candidates")) checkUrl(c.path("url").asText());
                Map<String,String> options = browserOptions(args);
                return jobs.start(output, args.path("timeoutSeconds").asInt(600), cancel -> {
                    JsonNode validated = new LocatorValidator().validate(proposal, options, cancel);
                    if (!cancel.get()) suggestions.save(output, validated);
                });
            }
            case "apply_registry": {
                Path output = path(args.path("output").asText()); guardOutput(output, args, "proposal");
                Set<String> ids = new HashSet<>(); args.path("accept").forEach(v -> ids.add(v.asText()));
                suggestions.apply(path(args.path("proposal").asText()), registryPath(args.path("registry").asText()), ids, output);
                return JsonFiles.JSON.createObjectNode().put("output", output.toString());
            }
            default: throw new IllegalArgumentException("Unknown tool");
        }
    }
    private Map<String,String> browserOptions(JsonNode args) throws Exception {
        Map<String,String> options = new HashMap<>();
        for (String key : List.of("browser","channel","connect","cdp","headless")) if (args.has(key)) options.put(key, args.path(key).asText());
        if (args.has("flow")) options.put("flow", path(args.path("flow").asText()).toString());
        if (options.containsKey("connect") && options.containsKey("cdp")) throw new IllegalArgumentException("Choose connect or cdp");
        return options;
    }
    private void checkUrl(String url) throws Exception {
        URI uri = URI.create(url);
        if ("file".equals(uri.getScheme())) path(Path.of(uri).toString());
        else if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), ""))) throw new IllegalArgumentException("URL must use http, https or a file inside root");
    }
    private void guardOutput(Path output, JsonNode args, String... inputs) throws Exception {
        path(OutputPaths.html(output).toString());
        List<Path> sources = new ArrayList<>();
        for (String input : inputs) if (args.has(input)) {
            sources.add(path(args.path(input).asText()));
        }
        OutputPaths.protect(output, sources);
    }
    private Path registryPath(String value) throws Exception {
        Path registry = path(value);
        if (Files.isDirectory(registry)) try (var files = Files.list(registry)) {
            for (Path file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList()) path(file.toString());
        }
        return registry;
    }
    Path path(String value) throws Exception {
        Path candidate = root.resolve(value).toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) throw new IllegalArgumentException("Path is outside project root");
        Path existing = candidate;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (!existing.toRealPath().startsWith(root)) throw new IllegalArgumentException("Path resolves outside project root");
        return candidate;
    }
    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = JsonFiles.JSON.createObjectNode(); response.put("jsonrpc", "2.0"); response.set("id", id);
        response.putObject("error").put("code", code).put("message", message); return response;
    }
    @Override public void close() { jobs.close(); }
}
