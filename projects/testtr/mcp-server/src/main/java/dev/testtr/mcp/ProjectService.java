package dev.testtr.mcp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.testtr.*;
import java.nio.file.*;
import java.util.*;

/** Framework-independent validation and preview; never writes generated sources. */
final class ProjectService {
    static final ObjectMapper JSON = new ObjectMapper();
    private final Path root;
    ProjectService(Path root) throws Exception { this.root = root.toRealPath(); }
    Path path(String value) throws Exception {
        Path candidate = root.resolve(value).normalize().toAbsolutePath();
        if (!candidate.startsWith(root)) throw new IllegalArgumentException("Path is outside project root");
        Path ancestor = candidate;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (!ancestor.toRealPath().startsWith(root)) throw new IllegalArgumentException("Path resolves outside project root");
        return candidate;
    }
    ObjectNode compile(JsonNode args, boolean generate) throws Exception {
        Path source = path(args.path("source").asText()), registry = path(args.path("registry").asText());
        Path helpers = path(args.path("helpers").asText("src/test/helpers.json"));
        RequirementCatalog catalog = null;
        Path requirements = path(args.path("requirements").asText("src/test/requirements.json"));
        if (args.has("requirements") || Files.exists(requirements)) catalog = RequirementCatalog.load(requirements);
        // Imported files are resolved by the existing compiler. Root is a trusted project boundary,
        // not a sandbox for project code or its declared dependencies.
        var objects = ObjectRegistry.load(registry);
        var compiler = new ScenarioCompiler();
        var scenarios = compiler.parseAll(source, objects, helpers, catalog);
        var target = ScenarioCompiler.Target.valueOf(args.path("framework").asText("SELENIDE").toUpperCase(Locale.ROOT));
        var result = JSON.createObjectNode().put("valid", true).put("framework", target.name());
        var list = result.putArray("scenarios");
        String relative = args.path("relativeSource").asText(source.getFileName().toString());
        for (var scenario : scenarios) {
            String id = ScenarioIdentity.className(relative, scenario.name());
            var metadata = new LinkedHashMap<String,String>();
            metadata.put("registrySha256", objects.sha256()); metadata.put("framework", target.name());
            metadata.put("compilerVersion", "0.1.0-SNAPSHOT");
            if (catalog != null) metadata.putAll(catalog.metadata(scenario.requirement(), relative));
            String java = compiler.generate(scenario, relative, id, target, metadata);
            var item = list.addObject().put("scenarioId", id).put("name", scenario.name()).put("source", relative);
            if (generate) item.put("java", java);
        }
        return result;
    }
}
