package dev.testtr;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned requirement snapshots supplied by the test project. */
public final class RequirementCatalog {
    public record Requirement(String id, String version, String status, String text) {}
    private final Map<String, Requirement> requirements;
    private final String sha256;

    private RequirementCatalog(Map<String, Requirement> requirements, String sha256) {
        this.requirements = Map.copyOf(requirements);
        this.sha256 = sha256;
    }

    public static RequirementCatalog load(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        var json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        JsonNode root = json.readTree(bytes);
        if (root == null || !root.isObject() || !root.path("schemaVersion").isIntegralNumber()
                || !root.path("schemaVersion").canConvertToInt()
                || root.path("schemaVersion").intValue() != 1 || !root.path("requirements").isArray())
            throw new IOException(file + " [DSL] Gereksinim kataloğunda schemaVersion: 1 ve requirements dizisi gereklidir.");
        var requirements = new LinkedHashMap<String, Requirement>();
        for (JsonNode entry : root.path("requirements")) {
            for (String field : new String[]{"id", "version", "status", "text"}) {
                if (!entry.path(field).isTextual() || entry.path(field).textValue().isBlank())
                    throw new IOException(file + " [DSL] Gereksinim alanı boş olmayan metin olmalıdır: " + field);
            }
            String id = entry.path("id").textValue();
            String status = entry.path("status").textValue();
            if (!java.util.Set.of("approved", "draft", "rejected").contains(status))
                throw new IOException(file + " [DSL] Geçersiz gereksinim durumu: " + status);
            if (requirements.putIfAbsent(id, new Requirement(id, entry.path("version").textValue(), status, entry.path("text").textValue())) != null)
                throw new IOException(file + " [DSL] Tekrarlı gereksinim kimliği: " + id);
        }
        try {
            return new RequirementCatalog(requirements,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public Map<String, String> metadata(String id, String location) {
        if (id.isEmpty()) return Map.of();
        Requirement requirement = requirements.get(id);
        if (requirement == null)
            throw new IllegalArgumentException(location + " [DSL] Gereksinim katalogda bulunamadı: " + id);
        if (!requirement.status().equals("approved"))
            throw new IllegalArgumentException(location + " [DSL] Gereksinim onaylı değil: " + id);
        return Map.of("requirementVersion", requirement.version(), "requirementsSha256", sha256);
    }

    public java.util.List<Requirement> approved() {
        return requirements.values().stream().filter(r -> r.status().equals("approved"))
                .sorted(java.util.Comparator.comparing(Requirement::id)).toList();
    }

    public String sha256() { return sha256; }
}
