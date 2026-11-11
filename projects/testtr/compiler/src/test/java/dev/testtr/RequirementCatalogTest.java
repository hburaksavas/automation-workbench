package dev.testtr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RequirementCatalogTest {
    @TempDir Path directory;

    private RequirementCatalog catalog(String entries) throws Exception {
        Path file = directory.resolve("requirements.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"requirements\":[" + entries + "]}");
        return RequirementCatalog.load(file);
    }

    private String entry(String status) {
        return "{\"id\":\"REQ-1\",\"version\":\"2\",\"status\":\"" + status
                + "\",\"text\":\"Karşılama mesajı görünür.\"}";
    }

    @Test void approvedVersionAndSnapshotReachGeneratedReportWithoutChangingScenarioIdentity() throws Exception {
        var catalog = catalog(entry("approved"));
        var metadata = catalog.metadata("REQ-1", "giris.testtr:2");
        assertEquals("2", metadata.get("requirementVersion"));
        assertTrue(metadata.get("requirementsSha256").matches("[a-f0-9]{64}"));
        var compiler = new ScenarioCompiler();
        var scenario = new ScenarioCompiler.Scenario("Giriş", java.util.List.of(), java.util.List.of(), "REQ-1");
        for (var target : ScenarioCompiler.Target.values()) {
            String java = compiler.generate(scenario, "giris.testtr", "SameTest", target, metadata);
            assertTrue(java.contains("requirementVersion"));
            assertTrue(java.contains(metadata.get("requirementsSha256")));
            assertTrue(java.contains("SameTest"));
            assertFalse(java.contains("Karşılama mesajı görünür."));
        }
        assertEquals(Map.of(), catalog.metadata("", "unlinked.testtr"));
        assertNotEquals(metadata.get("requirementsSha256"), catalog(entry("approved").replace("görünür", "görüntülenir"))
                .metadata("REQ-1", "giris.testtr:2").get("requirementsSha256"));
    }

    @Test void unknownAndUnapprovedReferencesStopCompilationAtTheRequirementLine() throws Exception {
        Path source = directory.resolve("giris.testtr");
        Files.writeString(source, "Senaryo: \"Giriş\".\r\n  Gereksinim: \"REQ-1\".\r\n  \"user\" ayarı \"Kullanıcı\" değişkenine alınır.\r\nSenaryo sonu.\r\n");
        Path registry = directory.resolve("registry.json");
        Files.writeString(registry, "{}");
        var objects = ObjectRegistry.load(registry);
        for (var catalog : java.util.List.of(catalog(""), catalog(entry("draft")), catalog(entry("rejected")))) {
            var error = assertThrows(IllegalArgumentException.class, () -> new ScenarioCompiler().parseAll(
                    source, objects, directory.resolve("helpers.json"), catalog));
            assertTrue(error.getMessage().contains("giris.testtr:2 [DSL]"));
        }
        assertEquals(1, new ScenarioCompiler().parseAll(source, objects, directory.resolve("helpers.json"), catalog(entry("approved"))).size());
        assertEquals(1, new ScenarioCompiler().parseAll(source, objects, directory.resolve("helpers.json")).size());
    }

    @Test void duplicateIdsAndMalformedCatalogsAreRejected() throws Exception {
        assertThrows(java.io.IOException.class, () -> catalog(entry("approved") + "," + entry("draft")));
        assertThrows(java.io.IOException.class, () -> catalog(entry("unknown")));
        assertThrows(java.io.IOException.class, () -> catalog(entry("approved").replace("\"version\":\"2\"", "\"version\":2")));
        assertThrows(java.io.IOException.class, () -> catalog(entry("approved").replace("Karşılama mesajı görünür.", " ")));
        Path file = directory.resolve("invalid.json");
        for (String content : new String[]{"null", "[]", "{}", "{\"schemaVersion\":2,\"requirements\":[]}",
                "{\"schemaVersion\":1,\"schemaVersion\":1,\"requirements\":[]}"}) {
            Files.writeString(file, content);
            assertThrows(java.io.IOException.class, () -> RequirementCatalog.load(file));
        }
    }
}
