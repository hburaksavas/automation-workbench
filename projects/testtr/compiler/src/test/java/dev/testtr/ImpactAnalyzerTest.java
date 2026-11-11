package dev.testtr;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ImpactAnalyzerTest {
    @TempDir Path root;
    Path sources;
    ObjectRegistry registry;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        sources = root.resolve("tests");
        Files.createDirectories(sources.resolve("ortak"));
        Path registryFile = root.resolve("registry.json");
        Files.writeString(registryFile, """
                {"pages":{"A":{"url":"a.html","objects":{}},"B":{"url":"b.html","objects":{}}}}
                """);
        registry = ObjectRegistry.load(registryFile);
        Files.writeString(sources.resolve("ortak/inner.testtr"), """
                Ortak adım: "A aç".
                  "A" sayfası açılır.
                Ortak adım sonu.
                """);
        Files.writeString(sources.resolve("ortak/outer.testtr"), """
                "inner.testtr" dosyası kullanılır.
                Ortak adım: "A akışı".
                  "A aç" ortak adımı uygulanır.
                Ortak adım sonu.
                """);
        Files.writeString(sources.resolve("multi.testtr"), """
                "ortak/outer.testtr" dosyası kullanılır.
                Senaryo: "Bir".
                  "A akışı" ortak adımı uygulanır.
                Senaryo sonu.
                Senaryo: "İki".
                  "B" sayfası açılır.
                Senaryo sonu.
                """);
        Files.writeString(sources.resolve("b.testtr"), "Senaryo: \"Üç\".\n\"B\" sayfası açılır.\nSenaryo sonu.\n");
    }

    private ImpactAnalyzer.Report analyze(List<String> changes, String config) throws Exception {
        return new ImpactAnalyzer().analyze(root, sources, registry, root.resolve("helpers.json"), null,
                config == null ? null : json.readTree(config), changes, List.of(root.resolve("registry.json")));
    }

    @Test void pageMappingHandlesJavaScriptAndTypeScriptAndNestedCommonCalls() throws Exception {
        assertEquals("Scenario_2b94533a8f4d2a3d697dea32807aa437f99930082340b71897e0f268980ac644Test",
                ScenarioIdentity.className("giris.testtr", "Giriş işlemi"));
        for (String extension : List.of("js", "jsx", "ts", "tsx")) {
            var report = analyze(List.of("src/pages/Login." + extension), """
                    {"schemaVersion":1,"pages":{"A":["src/pages/Login.*"]}}
                    """);
            assertEquals("selected", report.mode());
            assertEquals(1, report.selected().size());
            var selected = report.selected().get(0);
            assertEquals("Bir", selected.name());
            assertEquals(ScenarioIdentity.className("multi.testtr", "Bir"), selected.scenarioId());
            assertTrue(selected.dependencies().contains("tests/ortak/inner.testtr"));
            assertTrue(selected.reasons().get(0).contains("→ A"));
        }
    }

    @Test void nestedSharedFileSelectsOnlyItsCallersAndDirectFileSelectsEveryScenarioInIt() throws Exception {
        var shared = analyze(List.of("tests/ortak/inner.testtr"), null);
        assertEquals(List.of("Bir"), shared.selected().stream().map(ImpactAnalyzer.Selection::name).toList());
        var direct = analyze(List.of("tests/multi.testtr"), null);
        assertEquals(List.of("Bir", "İki"), direct.selected().stream().map(ImpactAnalyzer.Selection::name).toList());
    }

    @Test void preparationAndCleanupPagesAreIncluded() throws Exception {
        Files.writeString(sources.resolve("lifecycle.testtr"), """
                Her senaryodan önce:
                  "A" sayfası açılır.
                Hazırlık sonu.
                Senaryo: "Yaşam döngüsü".
                  "B" sayfası açılır.
                Senaryo sonu.
                Her senaryodan sonra:
                  "A" sayfası açılır.
                Temizlik sonu.
                """);
        var report = analyze(List.of("src/components/login/button.js"), """
                {"schemaVersion":1,"pages":{"A":["src/components/login/**"]}}
                """);
        assertEquals(2, report.selected().size());
        assertTrue(report.selected().stream().anyMatch(s -> s.name().equals("Yaşam döngüsü")));
    }

    @Test void unknownDeletedOrGlobalFilesSelectAll() throws Exception {
        for (String change : List.of("deleted/old-page.js", "registry.json")) {
            var report = analyze(List.of(change), null);
            assertEquals("all", report.mode());
            assertEquals(3, report.selected().size());
        }
        var global = analyze(List.of("package.json"), """
                {"schemaVersion":1,"pages":{},"global":["package.json"]}
                """);
        assertEquals("all", global.mode());
        assertTrue(global.unknownFiles().isEmpty());
    }

    @Test void unmappedCoverageDoesNotSilentlyProduceAnEmptySelection() throws Exception {
        Files.delete(sources.resolve("b.testtr"));
        Files.writeString(sources.resolve("multi.testtr"), "Senaryo: \"Bir\".\n\"A\" sayfası açılır.\nSenaryo sonu.\n");
        var report = analyze(List.of("src/B.js"), """
                {"schemaVersion":1,"pages":{"B":["src/B.js"]}}
                """);
        assertEquals("all", report.mode());
        assertTrue(report.selected().get(0).reasons().get(0).contains("mevcut senaryosu yok"));
    }

    @Test void recursiveGlobMatchesDirectAndNestedFilesAndNoChangesSelectsNone() throws Exception {
        for (String file : List.of("src/A.js", "src/nested/A.js")) {
            assertEquals(1, analyze(List.of(file), """
                    {"schemaVersion":1,"pages":{"A":["src/**/A.js"]}}
                    """).selected().size());
        }
        assertEquals("none", analyze(List.of(), null).mode());
        assertEquals(3, analyze(List.of(), null).unselectedCount());
    }

    @Test void invalidConfigAndBrokenSourcesFailInsteadOfReportingNoImpact() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> analyze(List.of("x"), "{\"schemaVersion\":1,\"pages\":{\"Missing\":[\"src/**\"]}}"));
        assertThrows(IllegalArgumentException.class, () -> analyze(List.of("x"), "{\"schemaVersion\":1,\"pages\":{\"A\":[\"../src/**\"]}}"));
        Files.writeString(sources.resolve("b.testtr"), "bozuk senaryo");
        assertThrows(IllegalArgumentException.class, () -> analyze(List.of(), null));
    }

    @Test void importedComponentSelectsItsPageAndUncertainImportWidensEvenMappedChanges() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/A.js"), "import Card from './Card';");
        Files.writeString(root.resolve("src/B.js"), "export default () => null;");
        Files.writeString(root.resolve("src/Card.jsx"), "export default () => null;");
        String config = "{\"schemaVersion\":1,\"pages\":{\"A\":[\"src/A.js\"],\"B\":[\"src/B.js\"]},\"imports\":{\"roots\":[\"src\"]}}";
        var selected = analyze(List.of("src/Card.jsx"), config);
        assertEquals("selected", selected.mode());
        assertEquals(List.of("Bir"), selected.selected().stream().map(ImpactAnalyzer.Selection::name).toList());
        assertTrue(selected.selected().get(0).reasons().get(0).contains("src/A.js → src/Card.jsx"));
        Files.writeString(root.resolve("src/A.js"), "import(variable);");
        var broad = analyze(List.of("src/B.js"), config);
        assertEquals("all", broad.mode());
        assertEquals(3, broad.selected().size());
        assertTrue(broad.fallbackReasons().stream().anyMatch(s -> s.contains("import")));
    }
}
