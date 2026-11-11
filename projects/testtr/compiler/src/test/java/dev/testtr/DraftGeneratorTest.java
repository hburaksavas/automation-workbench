package dev.testtr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DraftGeneratorTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final String source = "Senaryo: \"Mesaj\".\nGereksinim: \"REQ-1\".\n\"Ana\" sayfası açılır.\n\"Mesaj\" metni \"Merhaba\" olmalıdır.\nSenaryo sonu.\n";

    private RequirementCatalog catalog() throws Exception {
        Path file = directory.resolve("requirements.json");
        Files.writeString(file, """
                {"schemaVersion":1,"requirements":[
                {"id":"REQ-1","version":"2","status":"approved","text":"Merhaba mesajı görünür."},
                {"id":"PRIVATE-DRAFT","version":"1","status":"draft","text":"unapproved text"}]}
                """);
        return RequirementCatalog.load(file);
    }

    private ObjectRegistry registry() throws Exception {
        Path file = directory.resolve("registry.json");
        Files.writeString(file, """
                {"pages":{"Ana":{"url":"index.html","objects":{"Mesaj":{"type":"text","css":"#mesaj"}}}}}
                """);
        return ObjectRegistry.load(file);
    }

    private JsonNode response(String source) {
        var root = json.createObjectNode();
        root.putArray("drafts").addObject().put("requirement", "REQ-1").put("version", "2")
                .put("source", source).put("rationale", "Eksik karşılama kontrolü");
        root.putArray("review");
        return root;
    }

    @Test void validDraftIsSeparateAndLinkedToSnapshot() throws Exception {
        var result = new DraftGenerator().generate(catalog(), registry(), directory.resolve("helpers.json"),
                Map.of("existing.testtr", "existing context"), directory.resolve("drafts"), 2, prompt -> {
                    assertTrue(prompt.contains("existing context"));
                    assertTrue(prompt.contains("Merhaba mesajı görünür."));
                    assertFalse(prompt.contains("PRIVATE-DRAFT"));
                    return response(source);
                });
        assertEquals(1, result.drafts());
        assertEquals(1, result.attempts());
        assertEquals(source, Files.readString(result.directory().resolve("draft-1.testtr")));
        JsonNode manifest = json.readTree(result.directory().resolve("manifest.json").toFile());
        assertEquals("ready_for_review", manifest.path("status").asText());
        assertEquals(catalog().sha256(), manifest.path("requirementsSha256").asText());
        assertFalse(Files.exists(directory.resolve("src/test/testtr")));
    }

    @Test void compilerDiagnosticsDriveOneBoundedRepair() throws Exception {
        var calls = new AtomicInteger();
        var result = new DraftGenerator().generate(catalog(), registry(), directory.resolve("helpers.json"), Map.of(),
                directory.resolve("drafts"), 2, prompt -> {
                    if (calls.incrementAndGet() == 1) return response(source.replace("\"Mesaj\" metni", "\"Eksik\" metni"));
                    assertTrue(prompt.contains("DERLEYİCİ TANISI"));
                    assertTrue(prompt.contains("Eksik"));
                    return response(source);
                });
        assertEquals(2, result.attempts());
        assertTrue(Files.exists(result.directory().resolve("diagnostic-1.txt")));
    }

    @Test void invalidVersionsLinksAndImportsNeverBecomeExecutableDrafts() throws Exception {
        for (int index = 0; index < 4; index++) {
            var response = response(source);
            var draft = (com.fasterxml.jackson.databind.node.ObjectNode) response.path("drafts").get(0);
            if (index == 0) draft.put("version", "old");
            if (index == 1) draft.put("source", source.replace("REQ-1", "OTHER"));
            if (index == 2) draft.put("source", "\"../../private.testtr\" dosyası kullanılır.\n" + source);
            if (index == 3) draft.put("source", source.replace("\"Mesaj\" metni", "\"Eksik\" metni"));
            Path output = directory.resolve("rejected-" + index);
            var calls = new AtomicInteger();
            assertThrows(java.io.IOException.class, () -> new DraftGenerator().generate(catalog(), registry(),
                    directory.resolve("helpers.json"), Map.of(), output, 2, prompt -> { calls.incrementAndGet(); return response; }));
            assertEquals(2, calls.get());
            try (var paths = Files.walk(output)) {
                assertFalse(paths.anyMatch(p -> p.toString().endsWith(".testtr") || p.getFileName().toString().equals("manifest.json")));
            }
        }
    }

    @Test void ambiguousRequirementsCanProduceReviewOnly() throws Exception {
        var response = json.readTree("{\"drafts\":[],\"review\":[{\"requirement\":\"REQ-1\",\"reason\":\"Negatif davranış belirtilmemiş\"}]}");
        var result = new DraftGenerator().generate(catalog(), registry(), directory.resolve("helpers.json"), Map.of(),
                directory.resolve("drafts"), 1, prompt -> response);
        assertEquals(0, result.drafts());
        assertEquals(1, result.reviewItems());
    }

    @Test void exactExistingDuplicatesAreRejected() throws Exception {
        assertThrows(java.io.IOException.class, () -> new DraftGenerator().generate(catalog(), registry(),
                directory.resolve("helpers.json"), Map.of("existing.testtr", source), directory.resolve("drafts"), 1,
                prompt -> response(source)));
    }

    @Test void invalidBudgetMakesNoModelCall() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new DraftGenerator().generate(catalog(), registry(),
                directory.resolve("helpers.json"), Map.of(), directory.resolve("drafts"), 4,
                prompt -> { fail("Must not call provider"); return null; }));
    }
}
