package dev.testtr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioCompilerTest {
    @TempDir Path directory;

    @Test void pageAssertionEstablishesRegistryScopeWithoutNavigation() throws Exception {
        Path source = directory.resolve("page.testtr");
        Path registry = directory.resolve("registry.json");
        Files.writeString(registry, """
                {"pages":{"Sonuç":{"url":"result?q=1#tab","objects":{"Mesaj":{"type":"text","css":"#message"}}}}}
                """);
        Files.writeString(source, """
                Senaryo: "Yönlendirme".
                  "Sonuç" sayfası açık olmalıdır.
                  "Mesaj" görünür olmalıdır.
                Senaryo sonu.
                """);
        var compiler = new ScenarioCompiler();
        var scenario = compiler.parse(source, registry);
        assertEquals("page", scenario.steps().get(0).operation());
        for (var target : ScenarioCompiler.Target.values()) {
            String java = compiler.generate(scenario, "page.testtr", "PageTest", target);
            assertTrue(java.contains(".expectUrl("));
            assertTrue(java.contains("result?q=1#tab"));
        }
    }

    @Test void multipleScenariosHaveIndependentVariablesAndLifecycle() throws Exception {
        Path source = directory.resolve("multiple.testtr");
        Path registry = directory.resolve("registry.json");
        Files.writeString(registry, "{}");
        Files.writeString(source, """
                Her senaryodan önce:
                  "user" ayarı "Kullanıcı" değişkenine alınır.
                Hazırlık sonu.
                Senaryo: "Bir".
                  "item" ayarı "Yerel" değişkenine alınır.
                Senaryo sonu.
                Senaryo: "İki".
                  "item" ayarı "Yerel" değişkenine alınır.
                Senaryo sonu.
                Her senaryodan sonra:
                  "cleanup" ayarı "Temizlik" değişkenine alınır.
                Temizlik sonu.
                """);
        var compiler = new ScenarioCompiler();
        var scenarios = compiler.parseAll(source, registry, directory.resolve("helpers.json"));
        assertEquals(java.util.List.of("Bir", "İki"), scenarios.stream().map(ScenarioCompiler.Scenario::name).toList());
        for (var scenario : scenarios) {
            assertEquals(2, scenario.steps().size());
            assertEquals("Kullanıcı", scenario.steps().get(0).value());
            assertEquals(1, scenario.cleanup().size());
        }
        assertThrows(IllegalArgumentException.class, () -> compiler.parse(source, registry));
        Files.writeString(source, Files.readString(source).replace("Senaryo: \"İki\".", "Senaryo: \"Bir\"."));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> compiler.parseAll(source, registry, directory.resolve("helpers.json")))
                .getMessage().contains("Tekrarlı senaryo adı"));
    }

    @Test void rejectsLifecycleBlocksInsideScenarios() throws Exception {
        Path source = directory.resolve("nested.testtr");
        Path registry = directory.resolve("registry.json");
        Files.writeString(registry, "{}");
        Files.writeString(source, """
                Senaryo: "İç içe".
                Her senaryodan önce:
                Hazırlık sonu.
                Senaryo sonu.
                """);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new ScenarioCompiler().parse(source, registry)).getMessage().contains("nested.testtr:2"));
    }

    @Test void emitsNestedCommonCallsAsJavaMethods() throws Exception {
        Path source = directory.resolve("methods.testtr");
        Path registry = directory.resolve("registry.json");
        Files.writeString(registry, "{}");
        Files.writeString(source, """
                Ortak adım: "İç".
                  "user" ayarı "Yerel" değişkenine alınır.
                Ortak adım sonu.
                Ortak adım: "Dış".
                  "İç" ortak adımı uygulanır.
                Ortak adım sonu.
                Senaryo: "Metotlar".
                  "Dış" ortak adımı uygulanır.
                Senaryo sonu.
                """);
        var compiler = new ScenarioCompiler();
        var scenario = compiler.parse(source, registry);
        for (var target : ScenarioCompiler.Target.values()) {
            String java = compiler.generate(scenario, "methods.testtr", "MethodsTest", target);
            assertEquals(2, java.split("private static void ortakAdim_", -1).length - 1);
            assertTrue(java.contains("methods.testtr:8 → methods.testtr:5 → methods.testtr:2"));
        }
    }

    @Test void runtimeVariablesCanBePassedThroughCommonSteps() throws Exception {
        Path source = directory.resolve("runtime.testtr");
        Path registry = directory.resolve("registry.json");
        Files.writeString(registry, """
                {"pages":{"Giriş":{"url":"index.html","objects":{"Parola":{"type":"input","css":"#password"}}}}}
                """);
        Files.writeString(source, """
                Ortak adım: "Doldur".
                  "Parola" adlı gizli değer alınır.
                  "Giriş" sayfası açılır.
                  "Parola" alanına "Parola" değişkeninin değeri yazılır.
                Ortak adım sonu.
                Senaryo: "Çalışma zamanı".
                  "TEST_PASSWORD" sırrı "Gizli" değişkenine alınır.
                  "Doldur" ortak adımı uygulanır:
                    "Parola" girdisine "Gizli" değişkeninin değeri verilir.
                  Ortak adım çağrısı sonu.
                Senaryo sonu.
                """);
        var compiler = new ScenarioCompiler();
        var scenario = compiler.parse(source, registry);
        assertTrue(scenario.steps().stream().filter(step -> step.operation().equals("fill")).findFirst().orElseThrow().reference());
        for (var target : ScenarioCompiler.Target.values()) {
            String java = compiler.generate(scenario, "runtime.testtr", "RuntimeTest", target);
            assertTrue(java.contains("context.secret(\"TEST_PASSWORD\", \"Gizli\")"));
            assertTrue(java.contains("context.value(\"Gizli\")"));
        }
        Files.writeString(source, Files.readString(source).replace(" sırrı ", " ayarı "));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> compiler.parse(source, registry)).getMessage().contains("türü uyuşmuyor"));
    }

    @Test void validatesHelperInputsAndResultBeforeJavaGeneration() throws Exception {
        Path source = directory.resolve("helper.testtr");
        Path registry = directory.resolve("registry.json");
        Path helpers = directory.resolve("helpers.json");
        Files.writeString(registry, "{}");
        Files.writeString(helpers, """
                {"Oluştur":{"method":"example.Helpers.create","inputs":{"Tür":"text"},"result":"text"}}
                """);
        Files.writeString(source, """
                Senaryo: "Veri".
                  "Oluştur" yardımcı işlemi çalıştırılır:
                    "Tür" girdisine "Bireysel" verilir.
                    Sonuç "Müşteri" değişkenine alınır.
                  Yardımcı işlem çağrısı sonu.
                Senaryo sonu.
                """);
        var compiler = new ScenarioCompiler();
        var scenario = compiler.parse(source, registry);
        assertEquals("Müşteri", scenario.steps().get(0).helper().result());
        Files.writeString(source, Files.readString(source).replace("\"Tür\" girdisine", "\"Yanlış\" girdisine"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> compiler.parse(source, registry)).getMessage().contains("girdisi veya türü"));
    }

    @Test void commonStepLocalVariablesDoNotCollideAcrossCalls() throws Exception {
        Path source = directory.resolve("locals.testtr");
        Path registry = directory.resolve("registry.json");
        Files.writeString(registry, "{}");
        Files.writeString(source, """
                Ortak adım: "Ayar oku".
                  "testtr.user" ayarı "Yerel" değişkenine alınır.
                Ortak adım sonu.
                Senaryo: "İki çağrı".
                  "Ayar oku" ortak adımı uygulanır.
                  "Ayar oku" ortak adımı uygulanır.
                Senaryo sonu.
                """);
        var scenario = new ScenarioCompiler().parse(source, registry);
        var settings = scenario.steps().stream().filter(step -> step.operation().equals("setting")).toList();
        assertNotEquals(settings.get(0).value(), settings.get(1).value());
    }

    @Test void expandsParametersAndPreservesCallLocations() throws Exception {
        Path source = directory.resolve("giris.testtr");
        Path common = directory.resolve("ortak.testtr");
        Path registry = directory.resolve("registry.json");
        Files.writeString(registry, """
                {"pages":{"Giriş":{"url":"index.html","objects":{"Ad":{"type":"input","css":"#username"}}}}}
                """);
        Files.writeString(common, """
                Ortak adım: "Doldur".
                  "İsim" adlı metin alınır.
                  "Giriş" sayfası açılır.
                  "Ad" alanına "İsim" değişkeninin değeri yazılır.
                Ortak adım sonu.
                """);
        Files.writeString(source, """
                "ortak.testtr" dosyası kullanılır.
                Her senaryodan önce:
                  "Doldur" ortak adımı uygulanır:
                    "İsim" girdisine "Çağrı" verilir.
                  Ortak adım çağrısı sonu.
                Hazırlık sonu.
                Senaryo: "Kontrol".
                  "Ad" alanının değeri "Çağrı" olmalıdır.
                Senaryo sonu.
                Her senaryodan sonra:
                  "Giriş" sayfası açılır.
                Temizlik sonu.
                """);
        var scenario = new ScenarioCompiler().parse(source, registry);
        var fill = scenario.steps().stream().filter(step -> step.operation().equals("fill")).findFirst().orElseThrow();
        assertEquals("Çağrı", fill.value());
        assertEquals("giris.testtr:3 → ortak.testtr:4", fill.location());
        assertEquals(1, scenario.cleanup().size());
        assertNull(new ScenarioCompiler().parse(common, registry));
    }

    @Test void rejectsRecursiveCommonStepsAndMissingArguments() throws Exception {
        Path source = directory.resolve("cycle.testtr");
        Path registry = directory.resolve("registry.json");
        Files.writeString(registry, "{}");
        Files.writeString(source, """
                Ortak adım: "Tekrar".
                  "Tekrar" ortak adımı uygulanır.
                Ortak adım sonu.
                Senaryo: "Döngü".
                  "Tekrar" ortak adımı uygulanır.
                Senaryo sonu.
                """);
        var error = assertThrows(IllegalArgumentException.class, () -> new ScenarioCompiler().parse(source, registry));
        assertTrue(error.getMessage().contains("Döngüsel"));
        Files.writeString(source, """
                Ortak adım: "Doldur".
                  "İsim" adlı metin alınır.
                Ortak adım sonu.
                Senaryo: "Eksik".
                  "Doldur" ortak adımı uygulanır.
                Senaryo sonu.
                """);
        error = assertThrows(IllegalArgumentException.class, () -> new ScenarioCompiler().parse(source, registry));
        assertTrue(error.getMessage().contains("girdileri uyuşmuyor"));
    }

    @Test void rejectsImportCycle() throws Exception {
        Path source = directory.resolve("a.testtr");
        Path other = directory.resolve("b.testtr");
        Path registry = directory.resolve("registry.json");
        Files.writeString(registry, "{}");
        Files.writeString(source, "\"b.testtr\" dosyası kullanılır.");
        Files.writeString(other, "\"a.testtr\" dosyası kullanılır.");
        var error = assertThrows(IllegalArgumentException.class, () -> new ScenarioCompiler().parse(source, registry));
        assertTrue(error.getMessage().contains("Döngüsel dosya"));
    }

    @Test void reportsOriginalLocationInsideCommonStep() throws Exception {
        Path source = directory.resolve("main.testtr");
        Path common = directory.resolve("common.testtr");
        Path registry = directory.resolve("registry.json");
        Files.writeString(registry, "{\"pages\":{\"Giriş\":{\"url\":\"index.html\",\"objects\":{}}}}");
        Files.writeString(common, """
                Ortak adım: "Bozuk".

                  "Giriş" sayfası açılır.
                  "Eksik" düğmesine tıklanır.
                Ortak adım sonu.
                """);
        Files.writeString(source, """
                "common.testtr" dosyası kullanılır.
                Senaryo: "Konum".
                  "Bozuk" ortak adımı uygulanır.
                Senaryo sonu.
                """);
        var error = assertThrows(IllegalArgumentException.class, () -> new ScenarioCompiler().parse(source, registry));
        assertTrue(error.getMessage().contains("main.testtr:3 → common.testtr:4"));
    }

    @Test void reportsUnknownObjectAtSourceLine() throws Exception {
        Path source = directory.resolve("hata.testtr");
        Path registry = directory.resolve("registry.json");
        Files.writeString(registry, "{\"pages\":{\"Giriş\":{\"url\":\"index.html\",\"objects\":{}}}}");
        Files.writeString(source, "Senaryo: \"Hata\".\n\"Giriş\" sayfası açılır.\n\"Eksik\" düğmesine tıklanır.\nSenaryo sonu.");
        var error = assertThrows(IllegalArgumentException.class, () -> new ScenarioCompiler().parse(source, registry));
        assertTrue(error.getMessage().contains("hata.testtr:3"));
    }

    @Test void reportsInvalidEscapeAndUnsupportedLocatorAtTheDslLine() throws Exception {
        Path source = directory.resolve("hata.testtr");
        Path registry = directory.resolve("registry.json");
        Files.writeString(registry, """
                {"pages":{"Giriş":{"url":"index.html","objects":{"Alan":{"type":"input","xpath":"//input"}}}}}
                """);
        var compiler = new ScenarioCompiler();
        String scenario = """
                Senaryo: "Hata".
                  "Giriş" sayfası açılır.
                  "Alan" alanına "%s" yazılır.
                Senaryo sonu.
                """;
        Files.writeString(source, scenario.formatted("\\q"));
        var escape = assertThrows(IllegalArgumentException.class, () -> compiler.parse(source, registry));
        assertEquals("hata.testtr:3 [DSL] Tırnak içindeki kaçış geçersiz.", escape.getMessage());

        Files.writeString(source, scenario.formatted("değer"));
        var locator = assertThrows(IllegalArgumentException.class, () -> compiler.parse(source, registry));
        assertEquals("hata.testtr:3 [DSL] Obje için boş olmayan CSS locator gereklidir: Alan", locator.getMessage());
    }

    @Test void preservesTurkishAndProducesAllTargetsDeterministically() throws Exception {
        Path source = directory.resolve("giris.testtr");
        Path registry = directory.resolve("registry.json");
        Files.writeString(registry, "{\"pages\":{\"Giriş\":{\"url\":\"index.html\",\"objects\":{}}}}");
        Files.writeString(source, "Senaryo: \"Türkçe\".\r\n\"Giriş\" sayfası açılır.\r\nSenaryo sonu.");
        var compiler = new ScenarioCompiler();
        var scenario = compiler.parse(source, registry);
        for (var target : ScenarioCompiler.Target.values()) {
            String java = compiler.generate(scenario, "giris.testtr", "GeneratedTest", target);
            assertEquals(java, compiler.generate(scenario, "giris.testtr", "GeneratedTest", target));
            assertTrue(java.contains("Türkçe"));
            assertTrue(java.contains("giris.testtr:2"));
        }
    }
}
