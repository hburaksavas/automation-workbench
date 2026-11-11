package dev.testtr.maven;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.testtr.DraftGenerator;
import dev.testtr.ObjectRegistry;
import dev.testtr.RequirementCatalog;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.plugin.descriptor.PluginDescriptor;

@Mojo(name = "draft", threadSafe = true, requiresProject = true, aggregator = false)
public final class DraftMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    private PluginDescriptor plugin;
    @Parameter(defaultValue = "${project.basedir}/src/test/requirements.json", property = "testtr.requirements")
    private File requirements;
    @Parameter(property = "testtr.registry")
    private File registry;
    @Parameter(defaultValue = "${project.basedir}/src/test/testtr")
    private File sourceDirectory;
    @Parameter(defaultValue = "${project.basedir}/src/test/helpers.json")
    private File helpers;
    @Parameter(defaultValue = "${project.build.directory}/testtr-drafts", property = "testtr.draftDirectory")
    private File draftDirectory;
    @Parameter(property = "testtr.aiModel")
    private String model;
    @Parameter(defaultValue = "2", property = "testtr.aiMaxAttempts")
    private int maxAttempts;
    @Parameter(defaultValue = "4000", property = "testtr.aiMaxOutputTokens")
    private int maxTokens;
    @Parameter(property = "testtr.aiResponse")
    private File responseFile;

    @Override public void execute() throws MojoExecutionException {
        try {
            Path registryPath = project.getBasedir().toPath().resolve("src/test/registry");
            if (registry != null) registryPath = registry.toPath();
            else if (!Files.exists(registryPath)) registryPath = registryPath.resolveSibling("registry.json");
            var catalog = RequirementCatalog.load(requirements.toPath());
            var objects = ObjectRegistry.load(registryPath);
            Path output = draftDirectory.toPath().toAbsolutePath().normalize();
            Path source = sourceDirectory.toPath().toAbsolutePath().normalize();
            if (output.startsWith(source) || source.startsWith(output))
                throw new IllegalArgumentException("Taslak dizini senaryo kaynaklarından ayrı olmalıdır.");
            var existing = new LinkedHashMap<String, String>();
            if (Files.isDirectory(source)) {
                try (var paths = Files.walk(source)) {
                    for (Path file : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".testtr")).sorted().toList())
                        existing.put(source.relativize(file).toString().replace('\\', '/'), Files.readString(file));
                }
            }
            DraftGenerator.Model provider = responseFile == null
                    ? new ResponsesDraftModel(model, System.getenv("OPENAI_API_KEY"), maxTokens)
                    : prompt -> new ObjectMapper().readTree(responseFile);
            var result = new DraftGenerator().generate(catalog, objects, helpers.toPath(), existing,
                    output, maxAttempts, provider);
            var json = new ObjectMapper();
            Path manifestPath = result.directory().resolve("manifest.json");
            var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(manifestPath.toFile());
            manifest.put("compilerVersion", plugin.getVersion());
            manifest.put("origin", responseFile == null ? "openai" : "response_file");
            if (responseFile == null) manifest.put("requestedModel", model);
            json.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
            getLog().info(result.drafts() + " doğrulanmış taslak, " + result.reviewItems()
                    + " inceleme maddesi. Otomatik çalıştırılmadı: " + result.directory());
        } catch (Exception exception) {
            // Do not leak HTTP payloads or API credentials through Maven's cause chain.
            throw new MojoExecutionException("Taslak üretimi: " + exception.getMessage());
        }
    }
}
