package dev.testtr.mcp;

import java.nio.file.Path;
import java.util.*;

public final class Main {
    public static void main(String[] args) throws Exception {
        Map<String,String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length || !Set.of("--root", "--maven-home", "--allow-tools").contains(args[i])
                    || options.putIfAbsent(args[i], args[i + 1]) != null) throw new IllegalArgumentException("Use --root PATH [--maven-home PATH] [--allow-tools names]");
        }
        if (!options.containsKey("--root")) throw new IllegalArgumentException("--root is required");
        try (var server = new McpServer(Path.of(options.get("--root")),
                options.containsKey("--maven-home") ? Path.of(options.get("--maven-home")) : null, options.get("--allow-tools"))) {
            server.serve(System.in, System.out);
        }
    }
}
