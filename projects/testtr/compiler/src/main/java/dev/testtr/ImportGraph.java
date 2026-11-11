package dev.testtr;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Literal import dependency scanning. Ambiguous syntax/resolution widens selection. */
final class ImportGraph {
    record Result(Map<String, Map<String, String>> pages, List<String> uncertainties) {}
    private static final List<String> EXTENSIONS = List.of(".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs");
    private static final Pattern STATIC = Pattern.compile(
            "\\bimport\\s+(?:[\\p{L}\\p{N}_$*{},\\s]+?\\s+from\\s*)?(['\"])([^'\"\\r\\n]+)\\1"
            + "|\\bexport\\s+(?:type\\s+)?(?:\\*(?:\\s+as\\s+[\\p{L}\\p{N}_$]+)?|\\{[^}]*})\\s+from\\s*(['\"])([^'\"\\r\\n]+)\\3");
    private static final Pattern CALL = Pattern.compile("\\b(?:import|require)\\s*\\(\\s*(['\"`])([^'\"`\\r\\n]+)\\1\\s*\\)");
    private static final Pattern IMPORT_TOKEN = Pattern.compile("\\brequire\\b|\\bimport\\b(?!\\s*\\.)");
    private static final Pattern EXPORT_TOKEN = Pattern.compile("\\bexport\\s+(?:type\\s+)?(?:\\*|\\{)");
    private final Path root;
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final List<String> external = new ArrayList<>();
    private final Set<String> uncertainties = new LinkedHashSet<>();
    private final Map<Path, List<Path>> cache = new LinkedHashMap<>();

    private ImportGraph(Path root, JsonNode config) {
        this.root = root;
        if (!config.isObject() || !config.path("roots").isArray() || config.path("roots").isEmpty())
            throw new IllegalArgumentException("imports.roots boş olmayan yol dizisi olmalıdır.");
        if (config.has("aliases")) {
            if (!config.path("aliases").isObject()) throw new IllegalArgumentException("imports.aliases nesne olmalıdır.");
            config.path("aliases").fields().forEachRemaining(entry -> {
                if (entry.getKey().isBlank() || !entry.getValue().isTextual()) throw new IllegalArgumentException("Geçersiz import alias eşlemesi.");
                checkedPath(entry.getValue().textValue());
                aliases.put(entry.getKey(), entry.getValue().textValue());
            });
        }
        if (config.has("external")) {
            if (!config.path("external").isArray()) throw new IllegalArgumentException("imports.external dizi olmalıdır.");
            for (JsonNode value : config.path("external")) {
                if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("Harici paket adı metin olmalıdır.");
                external.add(value.textValue());
            }
        }
    }

    static Result build(Path root, JsonNode config, Map<String, List<Pattern>> mappings) throws IOException {
        if (config == null) return new Result(Map.of(), List.of());
        var graph = new ImportGraph(root, config);
        var files = new LinkedHashSet<Path>();
        for (JsonNode directory : config.path("roots")) {
            if (!directory.isTextual()) throw new IllegalArgumentException("Import kökü metin olmalıdır.");
            Path path = graph.checkedPath(directory.textValue());
            if (!Files.isDirectory(path)) throw new IOException("Import kök dizini bulunamadı: " + directory.textValue());
            Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override public java.nio.file.FileVisitResult preVisitDirectory(Path directory, java.nio.file.attribute.BasicFileAttributes attributes) {
                    return !directory.equals(path) && Set.of("node_modules", ".git", "target", "dist", "build").contains(directory.getFileName().toString())
                            ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                    if (attributes.isRegularFile()) files.add(file);
                    if (files.size() > 10000) throw new IOException("Import grafiği 10000 dosya sınırını aşıyor.");
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
        var pages = new LinkedHashMap<String, Map<String, String>>();
        for (var mapping : mappings.entrySet()) {
            var dependencies = new LinkedHashMap<String, String>();
            for (Path file : files.stream().sorted().toList()) {
                if (mapping.getValue().stream().anyMatch(p -> p.matcher(graph.relative(file)).matches()))
                    graph.walk(file, dependencies, graph.relative(file));
            }
            if (dependencies.isEmpty()) graph.uncertainties.add("Import köklerinde sayfa giriş dosyası bulunamadı: " + mapping.getKey());
            pages.put(mapping.getKey(), dependencies);
        }
        return new Result(pages, List.copyOf(graph.uncertainties));
    }

    private void walk(Path file, Map<String, String> dependencies, String chain) throws IOException {
        if (dependencies.putIfAbsent(relative(file), chain) != null) return;
        if (!cache.containsKey(file)) {
            if (cache.size() >= 10000) throw new IOException("Import grafiği 10000 kaynak sınırını aşıyor.");
            cache.put(file, imports(file));
        }
        for (Path child : cache.get(file)) walk(child, dependencies, chain + " → " + relative(child));
    }

    private List<Path> imports(Path file) throws IOException {
        if (EXTENSIONS.stream().noneMatch(e -> file.toString().endsWith(e))) {
            if (file.toString().matches(".*\\.(?:css|scss|sass|less)$")
                    && Pattern.compile("@(?:import|use|forward)\\b|url\\s*\\(").matcher(Files.readString(file)).find())
                uncertainties.add("Stil dosyasında ek bağımlılık var: " + relative(file));
            return List.of();
        }
        String source = Files.readString(file);
        if (Pattern.compile("\\bimport\\s*\\.\\s*meta").matcher(source).find())
            uncertainties.add("import.meta bağımlılığı çözülemiyor: " + relative(file));
        var found = new LinkedHashMap<Integer, String>();
        var literal = STATIC.matcher(source);
        while (literal.find()) found.put(literal.start(), literal.group(2) != null ? literal.group(2) : literal.group(4));
        var calls = CALL.matcher(source);
        while (calls.find()) found.put(calls.start(), calls.group(2));
        var token = IMPORT_TOKEN.matcher(source);
        while (token.find()) {
            if (!found.containsKey(token.start())) uncertainties.add("Çözülemeyen import sözdizimi: " + relative(file));
        }
        var exported = EXPORT_TOKEN.matcher(source);
        while (exported.find()) {
            if (!found.containsKey(exported.start())) uncertainties.add("Çözülemeyen export sözdizimi: " + relative(file));
        }
        var dependencies = new LinkedHashSet<Path>();
        for (String specifier : found.values()) {
            if (specifier.contains("${") || specifier.contains("\\")) {
                uncertainties.add("Dinamik veya kaçışlı import: " + relative(file));
                continue;
            }
            String path = specifier.split("[?#]", 2)[0];
            Path candidate = null;
            if (path.startsWith("./") || path.startsWith("../")) candidate = file.getParent().resolve(path).normalize();
            else {
                for (String alias : aliases.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
                    if (path.equals(alias) || path.startsWith(alias.endsWith("/") ? alias : alias + "/")) {
                        String suffix = path.substring(alias.length()).replaceFirst("^/", "");
                        candidate = checkedPath(aliases.get(alias)).resolve(suffix).normalize();
                        break;
                    }
                }
                if (candidate == null && (path.startsWith("node:") || external.stream().anyMatch(e -> path.equals(e) || path.startsWith(e + "/")))) continue;
            }
            if (candidate == null || !candidate.startsWith(root)) {
                uncertainties.add("Import eşlenemedi: " + relative(file) + " → " + specifier);
                continue;
            }
            var matches = new LinkedHashSet<Path>();
            if (Files.isRegularFile(candidate)) matches.add(candidate);
            for (String extension : EXTENSIONS) {
                Path sibling = Path.of(candidate + extension);
                if (Files.isRegularFile(sibling)) matches.add(sibling);
                Path index = candidate.resolve("index" + extension);
                if (Files.isRegularFile(index)) matches.add(index);
            }
            if (matches.size() == 1) dependencies.add(matches.iterator().next());
            else uncertainties.add("Eksik veya belirsiz import: " + relative(file) + " → " + specifier);
        }
        return List.copyOf(dependencies);
    }

    private Path checkedPath(String path) {
        String value = path.replace('\\', '/');
        if (value.isBlank() || value.startsWith("/") || value.matches("^[A-Za-z]:.*") || List.of(value.split("/")).contains(".."))
            throw new IllegalArgumentException("Import yolu depo köküne göre göreli olmalıdır: " + path);
        return root.resolve(value).normalize();
    }
    private String relative(Path file) { return root.relativize(file).toString().replace('\\', '/'); }
}
