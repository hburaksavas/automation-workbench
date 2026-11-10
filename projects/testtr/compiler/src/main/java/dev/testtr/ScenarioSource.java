package dev.testtr;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Resolves reusable steps before the UI operations are type checked. */
final class ScenarioSource {
    enum Control { TEXT, REQUIREMENT, CLEANUP, COMMON_START, COMMON_END }
    record Line(String text, String location, Control control) {
        Line(String text, String location) { this(text, location, Control.TEXT); }
    }
    record CommonStep(Map<String, Boolean> parameters, List<Line> body) {}
    private static final String STRING = "\"((?:\\\\.|[^\"\\\\])*)\"";
    private static final Pattern IMPORT = Pattern.compile(STRING + " dosyası kullanılır\\.");
    private static final Pattern DEFINITION = Pattern.compile("Ortak adım: " + STRING + "\\.");
    private static final Pattern PARAMETER = Pattern.compile(STRING + " adlı (metin|gizli değer) alınır\\.");
    private static final Pattern CALL = Pattern.compile(STRING + " ortak adımı uygulanır([.:])");
    private static final Pattern ARGUMENT = Pattern.compile(STRING + " girdisine (" + STRING + "(?: değişkeninin değeri)?) verilir\\.");
    private static final Pattern VARIABLE = Pattern.compile(STRING + " değişkeninin değeri");
    private static final Pattern CAPTURE = Pattern.compile("(.*)" + STRING + " değişkenine alınır\\.");
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, CommonStep> definitions = new LinkedHashMap<>();
    private final Set<Path> loaded = new LinkedHashSet<>();
    private final Set<Path> loading = new LinkedHashSet<>();
    private final Path root;
    private int localScope;

    private ScenarioSource(Path root) { this.root = root; }

    static List<List<Line>> readAll(Path source) throws IOException {
        Path absolute = source.toAbsolutePath().normalize();
        var reader = new ScenarioSource(absolute.getParent());
        List<Line> main = reader.load(absolute, false);
        List<List<Line>> result = new ArrayList<>();
        for (List<Line> scenario : lifecycle(main)) {
            reader.localScope = 0;
            result.add(reader.expand(scenario, Map.of(), new LinkedHashSet<>(), ""));
        }
        return result;
    }

    private static List<List<Line>> lifecycle(List<Line> lines) {
        List<Line> preparation = new ArrayList<>();
        List<Line> cleanup = new ArrayList<>();
        List<List<Line>> scenarios = new ArrayList<>();
        List<Line> current = null;
        String closing = null;
        String openingLocation = "";
        boolean hasPreparation = false;
        boolean hasCleanup = false;
        for (Line line : lines) {
            if (line.text().startsWith("Senaryo: ")) {
                if (current != null) throw new IllegalArgumentException(line.location() + " [DSL] İç içe senaryo bloğu.");
                current = new ArrayList<>();
                scenarios.add(current);
                closing = "Senaryo sonu.";
                openingLocation = line.location();
                current.add(line);
            } else if (line.text().equals("Her senaryodan önce:") || line.text().equals("Her senaryodan sonra:")) {
                boolean before = line.text().equals("Her senaryodan önce:");
                if (current != null || (before ? hasPreparation : hasCleanup))
                    throw new IllegalArgumentException(line.location() + " [DSL] Tekrarlı veya iç içe yaşam döngüsü bloğu.");
                if (before) hasPreparation = true;
                else hasCleanup = true;
                current = before ? preparation : cleanup;
                closing = before ? "Hazırlık sonu." : "Temizlik sonu.";
                openingLocation = line.location();
            } else if (line.text().equals(closing)) {
                if (closing.equals("Senaryo sonu.")) current.add(line);
                current = null;
                closing = null;
            } else {
                if (current == null) throw new IllegalArgumentException(line.location() + " [DSL] Senaryo veya yaşam döngüsü bloğu bekleniyor.");
                current.add(line);
            }
        }
        if (closing != null) throw new IllegalArgumentException(openingLocation + " [DSL] " + closing + " bekleniyor.");
        if (scenarios.isEmpty()) {
            if (hasPreparation || hasCleanup) throw new IllegalArgumentException("[DSL] Yaşam döngüsü için senaryo gerekli.");
            return List.of();
        }
        List<List<Line>> result = new ArrayList<>();
        for (List<Line> scenario : scenarios) {
            List<Line> combined = new ArrayList<>();
            combined.add(scenario.get(0));
            int bodyStart = 1;
            if (scenario.get(bodyStart).text().startsWith("Gereksinim:")) {
                Line requirement = scenario.get(bodyStart++);
                combined.add(new Line(requirement.text(), requirement.location(), Control.REQUIREMENT));
            }
            combined.addAll(preparation);
            combined.addAll(scenario.subList(bodyStart, scenario.size() - 1));
            combined.add(new Line("", scenario.get(0).location(), Control.CLEANUP));
            combined.addAll(cleanup);
            combined.add(scenario.get(scenario.size() - 1));
            result.add(combined);
        }
        return List.copyOf(result);
    }

    private List<Line> load(Path file, boolean imported) throws IOException {
        file = file.toRealPath();
        if (loading.contains(file)) throw new IllegalArgumentException("Döngüsel dosya kullanımı: " + file);
        if (loaded.contains(file)) return List.of();
        loading.add(file);
        List<Line> lines = new ArrayList<>();
        List<String> content = Files.readAllLines(file);
        for (int index = 0; index < content.size(); index++) {
            String text = content.get(index).strip();
            if (index == 0 && text.startsWith("\uFEFF")) text = text.substring(1);
            if (!text.isEmpty()) lines.add(new Line(text, root.relativize(file).toString().replace('\\', '/') + ":" + (index + 1)));
        }
        List<Line> main = new ArrayList<>();
        String mainClosing = null;
        for (int index = 0; index < lines.size(); index++) {
            Line line = lines.get(index);
            var use = IMPORT.matcher(line.text());
            var definition = DEFINITION.matcher(line.text());
            if (mainClosing != null && (use.matches() || definition.matches()))
                throw error(line, "Dosya kullanımı ve ortak adım tanımı blok dışında olmalıdır.");
            if (use.matches()) {
                Path dependency = file.getParent().resolve(decode(json, use.group(1), line)).normalize();
                load(dependency, true);
            } else if (definition.matches()) {
                String name = decode(json, definition.group(1), line);
                Map<String, Boolean> parameters = new LinkedHashMap<>();
                List<Line> body = new ArrayList<>();
                boolean closed = false;
                while (++index < lines.size()) {
                    Line item = lines.get(index);
                    if (item.text().equals("Ortak adım sonu.")) { closed = true; break; }
                    var parameter = PARAMETER.matcher(item.text());
                    if (parameter.matches()) {
                        String key = decode(json, parameter.group(1), item);
                        if (!body.isEmpty() || parameters.containsKey(key)) throw error(item, "Geçersiz veya tekrarlı parametre.");
                        parameters.put(key, parameter.group(2).equals("gizli değer"));
                    } else body.add(item);
                }
                if (!closed) throw error(line, "Ortak adım sonu bekleniyor.");
                if (definitions.putIfAbsent(name, new CommonStep(java.util.Collections.unmodifiableMap(parameters), List.copyOf(body))) != null)
                    throw error(line, "Ortak adım birden fazla tanımlı: " + name);
            } else {
                if (imported) throw error(line, "Kullanılan dosya yalnızca ortak adımlar içerebilir.");
                if (line.text().startsWith("Senaryo: ")) mainClosing = "Senaryo sonu.";
                else if (line.text().equals("Her senaryodan önce:")) mainClosing = "Hazırlık sonu.";
                else if (line.text().equals("Her senaryodan sonra:")) mainClosing = "Temizlik sonu.";
                else if (line.text().equals(mainClosing)) mainClosing = null;
                main.add(line);
            }
        }
        loading.remove(file);
        loaded.add(file);
        return main;
    }

    private List<Line> expand(List<Line> input, Map<String, String> bindings,
                              Set<String> stack, String chain) throws IOException {
        List<Line> result = new ArrayList<>();
        bindings = new LinkedHashMap<>(bindings);
        String prefix = "__ortak_" + localScope++ + "_";
        for (int index = 0; index < input.size(); index++) {
            Line original = input.get(index);
            Line line = new Line(substitute(original, bindings, !stack.isEmpty()), chain + original.location(), original.control());
            var capture = CAPTURE.matcher(line.text());
            if (capture.matches() && !stack.isEmpty()) {
                String name = decode(json, capture.group(2), line);
                if (bindings.containsKey(name)) throw error(line, "Yerel değişken birden fazla tanımlı: " + name);
                String local = json.writeValueAsString(prefix + name);
                bindings.put(name, local + " değişkeninin değeri");
                line = new Line(capture.group(1) + local + " değişkenine alınır.", line.location());
            }
            var call = CALL.matcher(line.text());
            if (!call.matches()) { result.add(line); continue; }
            String name = decode(json, call.group(1), line);
            CommonStep step = definitions.get(name);
            if (step == null) throw error(line, "Ortak adım bulunamadı: " + name);
            if (!stack.add(name)) throw error(line, "Döngüsel ortak adım çağrısı: " + name);
            Map<String, String> arguments = new LinkedHashMap<>();
            if (call.group(2).equals(":")) {
                boolean closed = false;
                while (++index < input.size()) {
                    Line item = input.get(index);
                    if (item.text().equals("Ortak adım çağrısı sonu.")) { closed = true; break; }
                    var argument = ARGUMENT.matcher(substitute(item, bindings, stack.size() > 1));
                    if (!argument.matches()) throw error(item, "Ortak adım girdisi bekleniyor.");
                    String key = decode(json, argument.group(1), item);
                    if (arguments.putIfAbsent(key, argument.group(2)) != null)
                        throw error(item, "Girdi birden fazla verildi: " + key);
                }
                if (!closed) throw error(line, "Ortak adım çağrısı sonu bekleniyor.");
            }
            if (!arguments.keySet().equals(step.parameters().keySet()))
                throw error(line, "Ortak adım girdileri uyuşmuyor. Beklenen: " + step.parameters());
            for (var parameter : step.parameters().entrySet()) {
                String type = parameter.getValue() ? "gizli değer" : "metin";
                result.add(new Line(arguments.get(parameter.getKey()) + " girdisi " + type + " olmalıdır.", line.location()));
            }
            String methodName = "ortakAdim_" + localScope;
            result.add(new Line(methodName, line.location(), Control.COMMON_START));
            result.addAll(expand(step.body(), arguments, stack, line.location() + " → "));
            result.add(new Line(methodName, line.location(), Control.COMMON_END));
            stack.remove(name);
        }
        return result;
    }

    private String substitute(Line line, Map<String, String> bindings, boolean local) throws IOException {
        var variable = VARIABLE.matcher(line.text());
        StringBuilder result = new StringBuilder();
        while (variable.find()) {
            String name = decode(json, variable.group(1), line);
            if (local && !bindings.containsKey(name)) throw error(line, "Değişken bulunamadı: " + name);
            String replacement = bindings.getOrDefault(name, variable.group());
            variable.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        variable.appendTail(result);
        return result.toString();
    }

    static String decode(ObjectMapper json, String escaped, Line line) {
        try {
            return json.readValue("\"" + escaped + "\"", String.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw error(line, "Tırnak içindeki kaçış geçersiz.");
        }
    }

    private static IllegalArgumentException error(Line line, String message) {
        return new IllegalArgumentException(line.location() + " [DSL] " + message);
    }
}
