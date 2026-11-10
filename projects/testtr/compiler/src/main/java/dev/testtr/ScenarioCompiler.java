package dev.testtr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.util.regex.Pattern;

public final class ScenarioCompiler {
    public enum Target { SELENIDE, PLAYWRIGHT, SELENIUM }
    public record Input(String value, boolean reference) {}
    public record HelperCall(String method, Map<String, Input> inputs, String result, boolean secret) {}
    public record Step(int line, String operation, String selector, String value, String location,
                       boolean reference, HelperCall helper) {
        public Step(int line, String operation, String selector, String value, String location) {
            this(line, operation, selector, value, location, false, null);
        }
    }
    public record Scenario(String name, List<Step> steps, List<Step> cleanup, String requirement) {
        public Scenario(String name, List<Step> steps, List<Step> cleanup) {
            this(name, steps, cleanup, "");
        }
    }
    private static final Pattern QUOTED = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    private final ObjectMapper json = new ObjectMapper();

    public Scenario parse(Path source, Path registryPath) throws IOException {
        return parse(source, registryPath, registryPath.resolveSibling("helpers.json"));
    }

    public Scenario parse(Path source, Path registryPath, Path helpersPath) throws IOException {
        List<Scenario> scenarios = parseAll(source, registryPath, helpersPath);
        if (scenarios.size() > 1) throw error(source.toString(), "Birden fazla senaryo için parseAll kullanılmalıdır.");
        return scenarios.isEmpty() ? null : scenarios.get(0);
    }

    public List<Scenario> parseAll(Path source, Path registryPath, Path helpersPath) throws IOException {
        return parseAll(source, ObjectRegistry.load(registryPath), helpersPath);
    }

    public List<Scenario> parseAll(Path source, ObjectRegistry registry, Path helpersPath) throws IOException {
        JsonNode helpers = Files.exists(helpersPath) ? json.readTree(helpersPath.toFile()) : json.createObjectNode();
        List<Scenario> result = new ArrayList<>();
        var names = new java.util.HashSet<String>();
        for (var lines : ScenarioSource.readAll(source)) {
            Scenario scenario = parseScenario(source, lines, registry, helpers);
            if (!names.add(scenario.name())) throw error(lines.get(0).location(), "Tekrarlı senaryo adı: " + scenario.name());
            result.add(scenario);
        }
        return List.copyOf(result);
    }

    private Scenario parseScenario(Path source, List<ScenarioSource.Line> lines, ObjectRegistry registry, JsonNode helpers) throws IOException {
        Map<String, Boolean> variables = new LinkedHashMap<>();
        String name = null;
        String requirement = "";
        String page = null;
        boolean ended = false;
        List<Step> steps = new ArrayList<>();
        List<Step> cleanup = new ArrayList<>();
        List<Step> destination = steps;
        for (int index = 0; index < lines.size(); index++) {
            var current = lines.get(index);
            if (current.control() == ScenarioSource.Control.CLEANUP) {
                destination = cleanup;
                page = null;
                continue;
            }
            if (current.control() == ScenarioSource.Control.COMMON_START || current.control() == ScenarioSource.Control.COMMON_END) {
                destination.add(new Step(index + 1, current.control() == ScenarioSource.Control.COMMON_START ? "commonStart" : "commonEnd",
                        current.text(), "", current.location()));
                continue;
            }
            String text = lines.get(index).text();
            if (index == 0 && text.startsWith("\uFEFF")) text = text.substring(1);
            if (text.isEmpty()) continue;
            int line = index + 1;
            var matcher = QUOTED.matcher(text);
            List<String> arguments = new ArrayList<>();
            StringBuilder template = new StringBuilder();
            while (matcher.find()) {
                arguments.add(ScenarioSource.decode(json, matcher.group(1), current));
                matcher.appendReplacement(template, "@");
            }
            matcher.appendTail(template);
            String syntax = template.toString();
            String location = lines.get(index).location();
            if (syntax.equals("Senaryo: @.") && name == null) {
                name = arguments.get(0);
                continue;
            }
            if (name == null || ended) throw error(lines.get(index).location(), "Senaryo bloğu bekleniyor.");
            if (syntax.startsWith("Gereksinim:")) {
                if (!syntax.equals("Gereksinim: @.") || arguments.size() != 1
                        || arguments.get(0).isBlank() || current.control() != ScenarioSource.Control.REQUIREMENT)
                    throw error(location, "Gereksinim, senaryo başlığından hemen sonra ve bir kez yazılmalıdır.");
                requirement = arguments.get(0);
                continue;
            }
            if (syntax.equals("Senaryo sonu.")) { ended = true; continue; }
            if (syntax.equals("@ ayarı @ değişkenine alınır.") || syntax.equals("@ sırrı @ değişkenine alınır.")) {
                boolean secret = syntax.contains(" sırrı ");
                declare(variables, arguments.get(1), secret, location);
                destination.add(new Step(line, secret ? "secret" : "setting", arguments.get(0), arguments.get(1), location));
                continue;
            }
            if (syntax.endsWith(" girdisi metin olmalıdır.") || syntax.endsWith(" girdisi gizli değer olmalıdır.")) {
                boolean reference = syntax.startsWith("@ değişkeninin değeri");
                boolean actual = reference && variableType(variables, arguments.get(0), location);
                if (actual != syntax.endsWith(" girdisi gizli değer olmalıdır."))
                    throw error(location, "Ortak adım girdisinin türü uyuşmuyor.");
                continue;
            }
            if (syntax.equals("@ yardımcı işlemi çalıştırılır.") || syntax.equals("@ yardımcı işlemi çalıştırılır:")) {
                JsonNode helper = helpers.path(arguments.get(0));
                String method = helper.path("method").asText();
                if (!method.matches("[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)+"))
                    throw error(location, "Yardımcı işlem kaydı bulunamadı veya metot adı geçersiz.");
                Map<String, Input> inputs = new LinkedHashMap<>();
                String result = "";
                if (syntax.endsWith(":")) {
                    boolean closed = false;
                    while (++index < lines.size()) {
                        var item = lines.get(index);
                        if (item.text().equals("Yardımcı işlem çağrısı sonu.")) { closed = true; break; }
                        var inputMatcher = QUOTED.matcher(item.text());
                        List<String> values = new ArrayList<>();
                        StringBuilder inputSyntax = new StringBuilder();
                        while (inputMatcher.find()) {
                            values.add(ScenarioSource.decode(json, inputMatcher.group(1), item));
                            inputMatcher.appendReplacement(inputSyntax, "@");
                        }
                        inputMatcher.appendTail(inputSyntax);
                        String shape = inputSyntax.toString();
                        if (shape.equals("Sonuç @ değişkenine alınır.")) {
                            if (!result.isEmpty()) throw error(item.location(), "Tekrarlı sonuç.");
                            result = values.get(0);
                        } else if (shape.equals("@ girdisine @ verilir.") || shape.equals("@ girdisine @ değişkeninin değeri verilir.")) {
                            boolean reference = shape.contains("değişkeninin");
                            boolean secret = reference && variableType(variables, values.get(1), item.location());
                            String expected = helper.path("inputs").path(values.get(0)).asText();
                            if (!expected.equals(secret ? "secret" : "text"))
                                throw error(item.location(), "Yardımcı işlem girdisi veya türü uyuşmuyor.");
                            if (inputs.putIfAbsent(values.get(0), new Input(values.get(1), reference)) != null)
                                throw error(item.location(), "Tekrarlı yardımcı işlem girdisi.");
                        } else throw error(item.location(), "Yardımcı işlem girdisi veya sonuç bekleniyor.");
                    }
                    if (!closed) throw error(location, "Yardımcı işlem çağrısı sonu bekleniyor.");
                }
                if (!helper.path("inputs").isObject() || inputs.size() != helper.path("inputs").size())
                    throw error(location, "Yardımcı işlem girdileri eksik.");
                String resultType = helper.path("result").asText("void");
                if (!List.of("void", "text", "secret").contains(resultType))
                    throw error(location, "Yardımcı işlem sonuç türü geçersiz.");
                if (!result.isEmpty()) {
                    if (resultType.equals("void")) throw error(location, "Yardımcı işlem sonuç döndürmüyor.");
                    declare(variables, result, resultType.equals("secret"), location);
                }
                destination.add(new Step(line, "helper", "", "", location, false,
                        new HelperCall(method, java.util.Collections.unmodifiableMap(inputs), result, resultType.equals("secret"))));
                continue;
            }
            if (syntax.equals("@ sayfası açılır.") || syntax.equals("@ sayfası açık olmalıdır.")) {
                page = arguments.get(0);
                JsonNode url = registry.page(page).path("url");
                if (!url.isTextual()) throw error(lines.get(index).location(), "Sayfa bulunamadı: " + page);
                destination.add(new Step(line, syntax.equals("@ sayfası açılır.") ? "open" : "page", "", url.asText(), lines.get(index).location()));
                continue;
            }
            boolean reference = syntax.contains("@ değişkeninin değeri");
            if (reference) {
                if (arguments.size() != 2) throw error(location, "Değer konumunda değişken bekleniyor.");
                variableType(variables, arguments.get(1), location);
                syntax = syntax.replace("@ değişkeninin değeri", "@");
            }
            String operation = switch (syntax) {
                case "@ alanına @ yazılır." -> "fill";
                case "@ alanı temizlenir." -> "clear";
                case "@ alanından sekme tuşuyla çıkılır." -> "tab";
                case "@ listesinden @ seçilir." -> "select";
                case "@ onay kutusu işaretlenir." -> "check";
                case "@ onay kutusunun işareti kaldırılır." -> "uncheck";
                case "@ düğmesine tıklanır." -> "click";
                case "@ metni @ olmalıdır." -> "text";
                case "@ metni @ ifadesini içermelidir." -> "contains";
                case "@ alanının değeri @ olmalıdır." -> "value";
                case "@ alanının değeri @ değişkenine alınır." -> "capture";
                case "@ alanı boş olmalıdır." -> "value";
                case "@ görünür olmalıdır." -> "visible";
                case "@ görünür olmamalıdır." -> "hidden";
                case "@ düğmesi etkin olmalıdır." -> "enabled";
                case "@ düğmesi devre dışı olmalıdır." -> "disabled";
                case "@ listesinde @ seçili olmalıdır." -> "selected";
                case "@ onay kutusu işaretli olmalıdır." -> "checked";
                case "@ onay kutusu işaretsiz olmalıdır." -> "unchecked";
                default -> throw error(lines.get(index).location(), "Desteklenmeyen cümle kalıbı.");
            };
            if (page == null) throw error(lines.get(index).location(), "Önce sayfa açılmalıdır.");
            JsonNode object = registry.page(page).path("objects").path(arguments.get(0));
            if (object.isMissingNode()) throw error(location, "Obje bulunamadı: " + arguments.get(0));
            if (!object.path("css").isTextual() || object.path("css").asText().isBlank())
                throw error(location, "Obje için boş olmayan CSS locator gereklidir: " + arguments.get(0));
            String type = switch (operation) {
                case "fill", "value", "clear", "tab", "capture" -> "input";
                case "click", "disabled", "enabled" -> "button";
                case "select", "selected" -> "select";
                case "check", "uncheck", "checked", "unchecked" -> "checkbox";
                default -> "";
            };
            if (!type.isEmpty() && !type.equals(object.path("type").asText()))
                throw error(lines.get(index).location(), "Obje tipi uyumsuz.");
            if (operation.equals("capture")) declare(variables, arguments.get(1), false, location);
            destination.add(new Step(line, operation, object.path("css").asText(), arguments.size() > 1 ? arguments.get(1) : "", location, reference, null));
        }
        if (!ended || steps.stream().noneMatch(step -> !step.operation().startsWith("common")))
            throw error(source.getFileName().toString(), "Tamamlanmış senaryo bekleniyor.");
        return new Scenario(name, List.copyOf(steps), List.copyOf(cleanup), requirement);
    }

    private IllegalArgumentException error(String location, String message) {
        return new IllegalArgumentException(location + " [DSL] " + message);
    }

    private void declare(Map<String, Boolean> variables, String name, boolean secret, String location) {
        if (variables.putIfAbsent(name, secret) != null) throw error(location, "Değişken zaten tanımlı: " + name);
    }

    private boolean variableType(Map<String, Boolean> variables, String name, String location) {
        if (!variables.containsKey(name)) throw error(location, "Değişken bulunamadı: " + name);
        return variables.get(name);
    }

    private String quote(String value) {
        try { return json.writeValueAsString(value); }
        catch (IOException exception) { throw new IllegalArgumentException(exception); }
    }

    public String generate(Scenario scenario, String source, String className, Target target) {
        return generate(scenario, source, className, target, Map.of());
    }

    public String generate(Scenario scenario, String source, String className, Target target, Map<String, String> buildMetadata) {
        String setup = switch (target) {
            case SELENIDE -> "com.codeborne.selenide.Configuration.headless = true;\n"
                    + "        com.codeborne.selenide.Configuration.timeout = dev.testtr.runtime.UiContract.timeoutMillis();\n"
                    + "        com.codeborne.selenide.Configuration.screenshots = false;\n"
                    + "        com.codeborne.selenide.Configuration.savePageSource = false;";
            case SELENIUM -> "var options = new org.openqa.selenium.chrome.ChromeOptions();\n"
                    + "        options.addArguments(\"--headless=new\");\n"
                    + "        var driver = new org.openqa.selenium.chrome.ChromeDriver(options);";
            case PLAYWRIGHT -> "var playwright = com.microsoft.playwright.Playwright.create();";
        };
        String close = switch (target) {
            case SELENIDE -> "com.codeborne.selenide.Selenide.closeWebDriver();";
            case SELENIUM -> "driver.quit();";
            case PLAYWRIGHT -> "playwright.close();";
        };
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("scenarioId", className);
        metadata.put("source", source);
        if (!scenario.requirement().isEmpty()) metadata.put("requirement", scenario.requirement());
        new java.util.TreeMap<>(buildMetadata).forEach(metadata::putIfAbsent);
        String report;
        try {
            report = json.writeValueAsString(metadata);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception);
        }
        StringBuilder code = new StringBuilder("package dev.testtr.generated;\n\n")
                .append("public class ").append(className).append(" {\n")
                .append("    @org.junit.jupiter.api.Test\n")
                .append("    @org.junit.jupiter.api.DisplayName(").append(quote(scenario.name())).append(")\n")
                .append("    void scenario() {\n")
                .append("        System.out.println(").append(quote("TESTTR " + report)).append(");\n")
                .append("        var context = new dev.testtr.runtime.ScenarioContext();\n")
                .append("        ").append(setup).append("\n        try {\n");
        if (target == Target.PLAYWRIGHT) code.append(
                "            var browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions().setChannel(\"chrome\").setHeadless(true));\n"
                + "            var page = browser.newPage();\n"
                + "            page.setDefaultTimeout(dev.testtr.runtime.UiContract.timeoutMillis());\n");
        StringBuilder methods = new StringBuilder();
        code.append("            Throwable primary = null;\n            try {\n");
        appendSteps(code, scenario.steps(), target, methods, "                ");
        code.append("            } catch (RuntimeException | AssertionError failure) {\n")
            .append("                primary = failure;\n                throw failure;\n")
            .append("            } finally {\n")
            .append("                String cleanupStatus = \"failed\";\n")
            .append("                try {\n");
        appendSteps(code, scenario.cleanup(), target, methods, "                    ");
        code.append("                    cleanupStatus = ")
            .append(quote(scenario.cleanup().isEmpty() ? "not_defined" : "passed")).append(";\n")
            .append("                } catch (RuntimeException | AssertionError cleanupFailure) {\n")
            .append("                    if (primary != null) primary.addSuppressed(cleanupFailure);\n")
            .append("                    else throw cleanupFailure;\n")
            .append("                } finally {\n")
            .append("                    context.reportCleanup(cleanupStatus);\n")
            .append("                }\n            }\n");
        return code.append("        } finally {\n            ").append(close).append("\n        }\n    }\n")
                .append(methods).append("}\n").toString();
    }

    private void appendSteps(StringBuilder code, List<Step> steps, Target target, StringBuilder methods, String indent) {
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            if (step.operation().equals("commonStart")) {
                int end = index + 1;
                while (end < steps.size() && !(steps.get(end).operation().equals("commonEnd") && steps.get(end).selector().equals(step.selector()))) end++;
                if (end == steps.size()) throw new IllegalArgumentException("Ortak adım sınırı bulunamadı.");
                String arguments = switch (target) {
                    case SELENIDE -> "context";
                    case PLAYWRIGHT -> "context, page";
                    case SELENIUM -> "context, driver";
                };
                String parameters = "dev.testtr.runtime.ScenarioContext context" + switch (target) {
                    case SELENIDE -> "";
                    case PLAYWRIGHT -> ", com.microsoft.playwright.Page page";
                    case SELENIUM -> ", org.openqa.selenium.WebDriver driver";
                };
                code.append(indent).append(step.selector()).append("(").append(arguments).append(");\n");
                StringBuilder method = new StringBuilder("\n    private static void ").append(step.selector())
                        .append("(").append(parameters).append(") {\n");
                appendSteps(method, steps.subList(index + 1, end), target, methods, "        ");
                method.append("    }\n");
                methods.append(method);
                index = end;
                continue;
            }
            code.append(indent).append("try {\n").append(indent).append("    ")
                .append(statement(step, target).replace("\n", "\n" + indent + "    ")).append("\n")
                .append(indent).append("} catch (RuntimeException | AssertionError failure) {\n")
                .append(indent).append("    throw context.failure(").append(quote(step.location())).append(", failure);\n")
                .append(indent).append("}\n");
        }
    }

    private String statement(Step step, Target target) {
        String selector = quote(step.selector());
        String value = step.reference() ? "context.value(" + quote(step.value()) + ")" : quote(step.value());
        if (step.operation().equals("setting") || step.operation().equals("secret"))
            return "context." + step.operation() + "(" + selector + ", " + value + ");";
        if (step.operation().equals("helper")) {
            HelperCall helper = step.helper();
            List<String> entries = new ArrayList<>();
            for (var entry : helper.inputs().entrySet()) {
                Input input = entry.getValue();
                String expression = input.reference() ? "context.value(" + quote(input.value()) + ")" : quote(input.value());
                entries.add("java.util.Map.entry(" + quote(entry.getKey()) + ", " + expression + ")");
            }
            String call = helper.method() + "(context, java.util.Map.ofEntries(" + String.join(", ", entries) + "))";
            String code = helper.result().isEmpty() ? call + ";" :
                    "context.put(" + quote(helper.result()) + ", " + call + ", " + helper.secret() + ");";
            return helper.secret() ? "context.markSensitive(); " + code : code;
        }
        String contract = "dev.testtr.runtime.UiContract";
        String url = contract + ".resolveUrl(System.getProperty(\"testtr.baseUrl\", \"\"), " + value + ")";
        if (step.operation().equals("open")) {
            return switch (target) {
                case SELENIDE -> "com.codeborne.selenide.Selenide.open(" + url + ");";
                case PLAYWRIGHT -> "page.navigate(" + url + ");";
                case SELENIUM -> "driver.get(" + url + ");";
            };
        }
        if (step.operation().equals("page")) {
            String current = switch (target) {
                case SELENIDE -> "com.codeborne.selenide.WebDriverRunner.getWebDriver().getCurrentUrl()";
                case PLAYWRIGHT -> "page.url()";
                case SELENIUM -> "driver.getCurrentUrl()";
            };
            return contract + ".expectUrl(() -> " + current + ", " + url + ");";
        }
        String snapshot = switch (target) {
            case SELENIDE -> "com.codeborne.selenide.Selenide.executeJavaScript(\"return (\" + " + contract + ".SNAPSHOT + \")(arguments[0]);\", " + selector + ")";
            case PLAYWRIGHT -> "page.evaluate(" + contract + ".SNAPSHOT, " + selector + ")";
            case SELENIUM -> "((org.openqa.selenium.JavascriptExecutor) driver).executeScript(\"return (\" + " + contract + ".SNAPSHOT + \")(arguments[0]);\", " + selector + ")";
        };
        if (step.operation().equals("capture"))
            return "context.put(" + value + ", " + contract + ".capture(() -> " + snapshot + "), false);";
        if (List.of("text", "contains", "value", "visible", "hidden", "enabled", "disabled", "checked", "unchecked", "selected").contains(step.operation()))
            return contract + ".expect(() -> " + snapshot + ", " + quote(step.operation()) + ", " + value + ");";
        String ready = contract + ".ready(() -> " + snapshot + ", " + quote(step.operation()) + ");\n";
        if (step.operation().equals("select"))
            ready += "int selectedIndex = " + contract + ".selectionIndex(() -> " + snapshot + ", " + value + ");\n";
        if (target == Target.SELENIDE) {
            String element = "com.codeborne.selenide.Selenide.$(" + selector + ")";
            return ready + switch (step.operation()) {
                case "fill" -> element + ".setValue(" + value + ");";
                case "clear" -> element + ".clear();";
                case "tab" -> element + ".pressTab();";
                case "select" -> element + ".selectOption(selectedIndex);";
                case "check" -> element + ".setSelected(true);";
                case "uncheck" -> element + ".setSelected(false);";
                case "click" -> element + ".click();";
                default -> throw new IllegalArgumentException(step.operation());
            };
        }
        if (target == Target.PLAYWRIGHT) {
            String element = "page.locator(" + selector + ")";
            return ready + switch (step.operation()) {
                case "fill" -> element + ".fill(" + value + ");";
                case "clear" -> element + ".fill(\"\");";
                case "tab" -> element + ".press(\"Tab\");";
                case "select" -> element + ".selectOption(new com.microsoft.playwright.options.SelectOption().setIndex(selectedIndex));";
                case "check" -> element + ".check();";
                case "uncheck" -> element + ".uncheck();";
                case "click" -> element + ".click();";
                default -> throw new IllegalArgumentException(step.operation());
            };
        }
        String element = "driver.findElement(org.openqa.selenium.By.cssSelector(" + selector + "))";
        return ready + switch (step.operation()) {
            case "fill" -> element + ".clear(); " + element + ".sendKeys(" + value + ");";
            case "clear" -> element + ".clear();";
            case "tab" -> element + ".sendKeys(org.openqa.selenium.Keys.TAB);";
            case "select" -> "new org.openqa.selenium.support.ui.Select(" + element + ").selectByIndex(selectedIndex);";
            case "check" -> "if (!" + element + ".isSelected()) " + element + ".click();";
            case "uncheck" -> "if (" + element + ".isSelected()) " + element + ".click();";
            case "click" -> element + ".click();";
            default -> throw new IllegalArgumentException(step.operation());
        };
    }
}
