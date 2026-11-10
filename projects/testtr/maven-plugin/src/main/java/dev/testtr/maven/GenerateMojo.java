package dev.testtr.maven;

import dev.testtr.ScenarioCompiler;
import dev.testtr.ObjectRegistry;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_TEST_SOURCES, threadSafe = true)
public final class GenerateMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    @Parameter(defaultValue = "${project.basedir}/src/test/testtr")
    private File sourceDirectory;
    @Parameter(property = "testtr.registry")
    private File registry;
    @Parameter(defaultValue = "${project.basedir}/src/test/helpers.json")
    private File helpers;
    @Parameter(defaultValue = "SELENIDE", property = "testtr.framework")
    private String framework;
    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    private PluginDescriptor plugin;
    @Parameter(property = "testtr.codeRevision")
    private String codeRevision;

    @Override public void execute() throws MojoExecutionException {
        try {
            var target = ScenarioCompiler.Target.valueOf(framework.toUpperCase(Locale.ROOT));
            var compiler = new ScenarioCompiler();
            var generated = new LinkedHashMap<String, String>();
            if (sourceDirectory.isDirectory()) {
                try (var paths = Files.walk(sourceDirectory.toPath())) {
                    var sources = paths.filter(p -> p.toString().endsWith(".testtr")).sorted().toList();
                    var metadata = new LinkedHashMap<String, String>();
                    ObjectRegistry objects = null;
                    if (!sources.isEmpty()) {
                        Path registryPath = project.getBasedir().toPath().resolve("src/test/registry");
                        if (registry != null) registryPath = registry.toPath();
                        else if (!Files.exists(registryPath)) registryPath = registryPath.resolveSibling("registry.json");
                        objects = ObjectRegistry.load(registryPath);
                        metadata.put("registrySha256", objects.sha256());
                        metadata.put("compilerVersion", plugin.getVersion());
                        metadata.put("framework", target.name());
                        if (codeRevision != null && !codeRevision.isBlank()) {
                            if (!codeRevision.matches("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})"))
                                throw new MojoExecutionException("testtr.codeRevision tam Git commit kimliği olmalıdır.");
                            metadata.put("codeRevision", codeRevision);
                        }
                    }
                    for (Path source : sources) {
                        String relative = sourceDirectory.toPath().relativize(source).toString().replace('\\', '/');
                        for (var scenario : compiler.parseAll(source, objects, helpers.toPath())) {
                            String identity = relative + "\n" + scenario.name();
                            String id = sha256(identity.getBytes(StandardCharsets.UTF_8));
                            String name = "Scenario_" + id + "Test";
                            generated.put(name + ".java", compiler.generate(scenario, relative, name, target, metadata));
                        }
                    }
                }
            }
            Path root = Path.of(project.getBuild().getDirectory(), "generated-test-sources", "testtr");
            Path output = root.resolve("dev/testtr/generated");
            Files.createDirectories(output);
            try (var paths = Files.list(output)) {
                for (Path old : paths.filter(p -> p.getFileName().toString().startsWith("Scenario_") && p.toString().endsWith(".java")).toList()) {
                    if (!generated.containsKey(old.getFileName().toString())) {
                        Files.delete(old);
                        Path compiled = Path.of(project.getBuild().getTestOutputDirectory(), "dev/testtr/generated", old.getFileName().toString().replace(".java", ".class"));
                        Files.deleteIfExists(compiled);
                    }
                }
            }
            for (var entry : generated.entrySet()) Files.writeString(output.resolve(entry.getKey()), entry.getValue());
            project.addTestCompileSourceRoot(root.toString());
            getLog().info(generated.size() + " Türkçe senaryo üretildi (" + target + ").");
        } catch (Exception exception) {
            throw new MojoExecutionException(exception.getMessage(), exception);
        }
    }

    private static String sha256(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
