package dev.testtr.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Page;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) { usage(); return; }
        String command = args[0].startsWith("--") ? "discover" : args[0];
        int offset = args[0].startsWith("--") ? 0 : 1;
        Set<String> allowed = switch (command) {
            case "discover" -> Set.of("url","output","browser","channel","connect","cdp","duration","headless","flow");
            case "suggest-registry" -> Set.of("discovery","registry","page-map","base-url","output");
            case "validate-registry" -> Set.of("proposal","output","flow","browser","channel","connect","cdp","headless");
            case "apply-registry" -> Set.of("proposal","registry","accept","output");
            case "mcp" -> Set.of("root","allow-tools");
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        };
        Map<String,String> options = new LinkedHashMap<>();
        for (int i=offset; i<args.length; i++) {
            if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2)) || i+1 == args.length) throw new IllegalArgumentException("Unknown or incomplete option: " + args[i]);
            String key = args[i].substring(2);
            if (options.putIfAbsent(key, args[++i]) != null) throw new IllegalArgumentException("Duplicate option: " + key);
        }
        RegistrySuggestions suggestions = new RegistrySuggestions();
        switch (command) {
            case "discover" -> {
                if (options.containsKey("flow")) OutputPaths.protect(Path.of(options.getOrDefault("output","target/discovery.json")), List.of(Path.of(options.get("flow"))));
                new DiscoveryRunner().run(options, new AtomicBoolean());
            }
            case "suggest-registry" -> {
                List<Path> reports = Arrays.stream(required(options,"discovery").split(",")).map(Path::of).toList();
                JsonNode map = options.containsKey("page-map") ? JsonFiles.read(Path.of(options.get("page-map"))) : JsonFiles.JSON.createObjectNode();
                List<Path> inputs = new ArrayList<>(reports); inputs.add(Path.of(required(options,"registry")));
                if (options.containsKey("page-map")) inputs.add(Path.of(options.get("page-map")));
                OutputPaths.protect(Path.of(required(options,"output")), inputs);
                suggestions.save(Path.of(required(options,"output")), suggestions.suggest(reports, Path.of(required(options,"registry")), map, options.get("base-url")));
            }
            case "validate-registry" -> {
                List<Path> inputs = new ArrayList<>(List.of(Path.of(required(options,"proposal"))));
                if (options.containsKey("flow")) inputs.add(Path.of(options.get("flow")));
                OutputPaths.protect(Path.of(required(options,"output")), inputs);
                suggestions.save(Path.of(required(options,"output")), new LocatorValidator().validate(Path.of(required(options,"proposal")), options, new AtomicBoolean()));
            }
            case "apply-registry" -> suggestions.apply(Path.of(required(options,"proposal")), Path.of(required(options,"registry")), new HashSet<>(Arrays.asList(required(options,"accept").split(","))), Path.of(required(options,"output")));
            case "mcp" -> {
                try (McpServer server = new McpServer(Path.of(required(options,"root")), options.get("allow-tools"))) { server.serve(System.in, System.out); }
            }
        }
    }
    static String required(Map<String,String> options, String key) {
        String value = options.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + key + " is required"); return value;
    }
    static void runFlow(Page page, Path file) throws Exception { DiscoveryRunner.runFlow(page, file); }
    private static void usage() {
        System.out.println("""
            TestTR Discovery
              discover --url URL [--output FILE] [--browser chromium|firefox|webkit] [--channel chrome|msedge]
                       [--connect WS | --cdp HTTP] [--headless true|false] [--duration SECONDS] [--flow FILE]
              suggest-registry --discovery REPORT[,REPORT2] --registry FILE_OR_DIR --output PROPOSAL.json
                               [--page-map FILE] [--base-url URL]
              validate-registry --proposal FILE --output VALIDATED.json [--flow FILE] [--browser chromium]
                                [--channel chrome|msedge] [--connect WS | --cdp HTTP] [--headless true|false]
              apply-registry --proposal FILE --registry FILE_OR_DIR --accept ID[,ID2] --output NEW_REGISTRY.json
              mcp --root PROJECT_DIR [--allow-tools TOOL,TOOL]
            Legacy --url syntax is supported. Registry application always creates a separate snapshot.
            """);
    }
}
