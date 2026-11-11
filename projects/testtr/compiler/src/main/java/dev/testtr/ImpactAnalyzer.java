package dev.testtr;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative selection over the current source tree, with explicit page mappings. */
public final class ImpactAnalyzer {
    public record Selection(String scenarioId, String source, String name, List<String> pages,
                            List<String> dependencies, List<String> reasons) {}
    public record Report(int schemaVersion, String mode, List<String> changedFiles, List<String> unknownFiles,
                         List<String> fallbackReasons, List<Selection> selected, int unselectedCount) {}
    private record Indexed(Selection selection, Set<String> pages, Set<String> dependencies) {}
    private static final Pattern PAGE = Pattern.compile("^\"((?:\\\\.|[^\"\\\\])*)\" sayfası (?:açılır|açık olmalıdır)\\.$");

    public Report analyze(Path repository, Path sources, ObjectRegistry registry, Path helpers,
                          RequirementCatalog catalog, JsonNode config, List<String> changes,
                          List<Path> globalInputs) throws Exception {
        Path root = repository.toAbsolutePath().normalize();
        var mappings = new LinkedHashMap<String, List<Pattern>>();
        var globals = new ArrayList<Pattern>();
        if (config != null) {
            if (!config.isObject() || !config.path("schemaVersion").isInt() || config.path("schemaVersion").intValue() != 1
                    || !config.path("pages").isObject()) throw new IllegalArgumentException("Etki ayarında schemaVersion: 1 ve pages nesnesi gereklidir.");
            var fields = config.path("pages").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (registry.page(field.getKey()).isMissingNode()) throw new IllegalArgumentException("Etki eşlemesinde bilinmeyen sayfa: " + field.getKey());
                mappings.put(field.getKey(), patterns(field.getValue()));
            }
            if (config.has("global")) globals.addAll(patterns(config.get("global")));
        }
        var index = new ArrayList<Indexed>();
        var compiler = new ScenarioCompiler();
        if (Files.isDirectory(sources)) {
            try (var files = Files.walk(sources)) {
                for (Path file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".testtr")).sorted().toList()) {
                    var scenarios = compiler.parseAll(file, registry, helpers, catalog);
                    var expanded = ScenarioSource.readAll(file);
                    String relative = sources.relativize(file).toString().replace('\\', '/');
                    for (int i = 0; i < scenarios.size(); i++) {
                        var scenario = scenarios.get(i);
                        var pages = new LinkedHashSet<String>();
                        var dependencies = new LinkedHashSet<String>();
                        dependencies.add(relative(root, file));
                        for (var line : expanded.get(i)) {
                            var page = PAGE.matcher(line.text());
                            if (page.matches()) pages.add(ScenarioSource.decode(new com.fasterxml.jackson.databind.ObjectMapper(), page.group(1), line));
                            for (String location : line.location().split(" → ")) {
                                String path = location.replaceFirst(":\\d+$", "");
                                dependencies.add(relative(root, file.toAbsolutePath().getParent().resolve(path).normalize()));
                            }
                        }
                        var selection = new Selection(ScenarioIdentity.className(relative, scenario.name()), relative,
                                scenario.name(), List.copyOf(pages), List.copyOf(dependencies), List.of());
                        index.add(new Indexed(selection, pages, dependencies));
                    }
                }
            }
        }
        var changed = changes.stream().map(ImpactAnalyzer::normalize).distinct().sorted().toList();
        var imports = ImportGraph.build(root, config != null && config.has("imports") ? config.get("imports") : null, mappings);
        var reasons = new LinkedHashMap<String, LinkedHashSet<String>>();
        var unknown = new ArrayList<String>();
        var broadReasons = new LinkedHashSet<String>();
        if (!changed.isEmpty()) broadReasons.addAll(imports.uncertainties());
        for (String change : changed) {
            boolean known = false;
            for (Path input : globalInputs) {
                String path = relative(root, input);
                if (change.equals(path) || change.startsWith(path + "/")) {
                    broadReasons.add("Ortak test girdisi değişti: " + change);
                    known = true;
                }
            }
            if (globals.stream().anyMatch(p -> p.matcher(change).matches())) {
                broadReasons.add("Genel etki eşlemesi: " + change);
                known = true;
            }
            for (Indexed item : index) {
                if (item.dependencies().contains(change)) {
                    reasons.computeIfAbsent(item.selection().scenarioId(), key -> new LinkedHashSet<>()).add("Senaryo veya kullanılan ortak adım değişti: " + change);
                    known = true;
                }
            }
            for (var mapping : mappings.entrySet()) {
                String chain = imports.pages().getOrDefault(mapping.getKey(), java.util.Map.of()).get(change);
                if (chain == null && mapping.getValue().stream().noneMatch(p -> p.matcher(change).matches())) continue;
                known = true;
                boolean covered = false;
                for (Indexed item : index) {
                    if (!item.pages().contains(mapping.getKey())) continue;
                    covered = true;
                    reasons.computeIfAbsent(item.selection().scenarioId(), key -> new LinkedHashSet<>())
                            .add(chain == null ? "Sayfa eşlemesi: " + change + " → " + mapping.getKey()
                                    : "Import bağımlılığı: " + chain + " → sayfa " + mapping.getKey());
                }
                if (!covered) broadReasons.add("Eşlenen sayfanın mevcut senaryosu yok: " + mapping.getKey() + " (" + change + ")");
            }
            if (!known) {
                unknown.add(change);
                broadReasons.add("Etki eşlenemedi; tüm TestTR senaryoları seçildi: " + change);
            }
        }
        var selected = new ArrayList<Selection>();
        for (Indexed item : index) {
            var why = new LinkedHashSet<>(broadReasons);
            why.addAll(reasons.getOrDefault(item.selection().scenarioId(), new LinkedHashSet<>()));
            if (why.isEmpty()) continue;
            var s = item.selection();
            selected.add(new Selection(s.scenarioId(), s.source(), s.name(), s.pages(), s.dependencies(), List.copyOf(why)));
        }
        return new Report(1, !broadReasons.isEmpty() ? "all" : selected.isEmpty() ? "none" : "selected",
                changed, List.copyOf(unknown), List.copyOf(broadReasons), List.copyOf(selected), index.size() - selected.size());
    }

    private static List<Pattern> patterns(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) throw new IllegalArgumentException("Etki kalıpları boş olmayan dizi olmalıdır.");
        var result = new ArrayList<Pattern>();
        for (JsonNode value : node) {
            if (!value.isTextual()) throw new IllegalArgumentException("Etki kalıbı metin olmalıdır.");
            String glob = normalize(value.textValue());
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') { i++; regex.append("(?:.*/)?"); }
                    else regex.append(".*");
                } else if (c == '*') regex.append("[^/]*");
                else if (c == '?') regex.append("[^/]");
                else regex.append(Pattern.quote(String.valueOf(c)));
            }
            result.add(Pattern.compile(regex + "$"));
        }
        return result;
    }

    private static String normalize(String path) {
        String value = path.replace('\\', '/');
        if (value.isBlank() || value.startsWith("/") || value.matches("^[A-Za-z]:.*")
                || java.util.Arrays.asList(value.split("/")).contains(".."))
            throw new IllegalArgumentException("Depo köküne göre göreli yol gereklidir: " + value);
        return value.startsWith("./") ? value.substring(2) : value;
    }
    private static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
