package dev.testtr.discovery;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

final class JsonFiles {
    static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    static JsonNode read(Path path) throws Exception {
        JsonNode value = JSON.readTree(path.toFile());
        if (value == null) throw new IllegalArgumentException("Empty JSON: " + path);
        return value;
    }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static String hash(JsonNode value) throws Exception { return hash(JSON.writeValueAsBytes(value)); }
    static void write(Path path, JsonNode value) throws Exception { writeText(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
    static void writeText(Path path, String value) throws Exception {
        path = path.toAbsolutePath(); Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "testtr-", ".tmp");
        try {
            Files.writeString(temp, value);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    static String required(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).asText().isBlank()) throw new IllegalArgumentException("Required text: " + key);
        return node.path(key).asText();
    }
}
