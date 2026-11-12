package dev.testtr.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;

/** Mirrors TestTR's ordered JSON overlays; application always writes a separate snapshot. */
final class RegistryStore {
    static ObjectNode load(Path path) throws Exception {
        ObjectNode result = JsonFiles.JSON.createObjectNode(); result.putObject("pages");
        List<Path> files;
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                files = entries.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString().endsWith(".override.json"))
                        .thenComparing(p -> p.getFileName().toString())).toList();
            }
            if (files.isEmpty()) throw new IllegalArgumentException("Registry directory has no JSON files");
        } else files = List.of(path);
        for (Path file : files) {
            JsonNode input = JsonFiles.read(file);
            if (!input.path("pages").isObject()) throw new IllegalArgumentException("Registry requires pages object: " + file);
            merge((ObjectNode) result.path("pages"), input.path("pages"));
        }
        var pages = result.path("pages").fields();
        while (pages.hasNext()) {
            var entry = pages.next(); JsonFiles.required(entry.getValue(), "url");
            if (!entry.getValue().path("objects").isObject()) throw new IllegalArgumentException("Page requires objects: " + entry.getKey());
        }
        return result;
    }
    private static void merge(ObjectNode target, JsonNode input) {
        input.fields().forEachRemaining(e -> {
            if (target.get(e.getKey()) instanceof ObjectNode old && e.getValue().isObject()) merge(old, e.getValue());
            else target.set(e.getKey(), e.getValue().deepCopy());
        });
    }
}
