package dev.testtr;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;

public final class ObjectRegistry {
    private final JsonNode pages;
    private final String sha256;

    private ObjectRegistry(JsonNode pages, String sha256) {
        this.pages = pages;
        this.sha256 = sha256;
    }

    public static ObjectRegistry load(Path path) throws IOException {
        boolean directory = Files.isDirectory(path);
        List<Path> files;
        if (directory) {
            try (var entries = Files.list(path)) {
                files = entries.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing((Path file) -> file.getFileName().toString().endsWith(".override.json"))
                                .thenComparing(file -> file.getFileName().toString()))
                        .toList();
            }
            if (files.isEmpty()) throw new IOException(path + " [DSL] Registry dizininde JSON dosyası bulunamadı.");
        } else {
            files = List.of(path);
        }
        var json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        var pages = json.createObjectNode();
        MessageDigest digest = sha256Digest();
        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            if (directory) {
                digest.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(sha256Digest().digest(bytes));
            } else {
                digest.update(bytes);
            }
            JsonNode document = json.readTree(bytes);
            JsonNode entries = document == null ? null : document.get("pages");
            if (directory && (entries == null || !entries.isObject() || entries.isEmpty()))
                throw new IOException(file + " [DSL] Registry dosyasında boş olmayan pages nesnesi gereklidir.");
            if (entries == null) continue;
            merge(pages, entries);
        }
        return new ObjectRegistry(pages, HexFormat.of().formatHex(digest.digest()));
    }

    private static void merge(ObjectNode destination, JsonNode overlay) {
        overlay.fields().forEachRemaining(field -> {
            JsonNode previous = destination.get(field.getKey());
            if (previous instanceof ObjectNode object && field.getValue().isObject()) {
                merge(object, field.getValue());
            } else {
                destination.set(field.getKey(), field.getValue());
            }
        });
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    JsonNode page(String name) { return pages.path(name); }
    public String sha256() { return sha256; }
    public JsonNode snapshot() { return pages.deepCopy(); }
}
