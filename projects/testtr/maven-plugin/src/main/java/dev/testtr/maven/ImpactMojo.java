package dev.testtr.maven;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.testtr.ImpactAnalyzer;
import dev.testtr.ObjectRegistry;
import dev.testtr.RequirementCatalog;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

@Mojo(name = "impact", threadSafe = true)
public class ImpactMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    @Parameter(defaultValue = "${project.basedir}/src/test/testtr")
    private File sourceDirectory;
    @Parameter(property = "testtr.registry")
    private File registry;
    @Parameter(defaultValue = "${project.basedir}/src/test/helpers.json")
    private File helpers;
    @Parameter(property = "testtr.requirements")
    private File requirements;
    @Parameter(property = "testtr.impactConfig")
    private File impactConfig;
    @Parameter(defaultValue = "HEAD", property = "testtr.impactBase")
    private String base;
    @Parameter(defaultValue = "${project.build.directory}/testtr-impact.json", property = "testtr.impactReport")
    private File report;

    @Override public void execute() throws MojoExecutionException {
        analyzeAndWrite();
    }

    protected ImpactAnalyzer.Report analyzeAndWrite() throws MojoExecutionException {
        try {
            Path module = project.getBasedir().toPath().toAbsolutePath().normalize();
            Path repository = Path.of(git(module, "rev-parse", "--show-toplevel").strip()).toAbsolutePath().normalize();
            if (base == null || base.isBlank() || base.startsWith("-")) throw new IllegalArgumentException("Geçerli testtr.impactBase gereklidir.");
            String baseSha = git(repository, "rev-parse", "--verify", base + "^{commit}").strip();
            var changed = new ArrayList<String>();
            changed.addAll(paths(git(repository, "diff", "--name-only", "-z", "--no-renames", baseSha, "--")));
            changed.addAll(paths(git(repository, "ls-files", "--others", "--exclude-standard", "-z")));
            Path registryPath = registry == null ? module.resolve("src/test/registry") : registry.toPath();
            if (registry == null && !Files.exists(registryPath)) registryPath = registryPath.resolveSibling("registry.json");
            Path requirementsPath = requirements == null ? module.resolve("src/test/requirements.json") : requirements.toPath();
            var catalog = requirements != null || Files.exists(requirementsPath) ? RequirementCatalog.load(requirementsPath) : null;
            var json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            Path configPath = impactConfig == null ? module.resolve("src/test/impact.json") : impactConfig.toPath();
            if (impactConfig != null && !Files.isRegularFile(configPath)) throw new IOException("Etki eşleme dosyası bulunamadı: " + configPath);
            var config = Files.exists(configPath) ? json.readTree(configPath.toFile()) : null;
            var globalInputs = new ArrayList<Path>(List.of(registryPath, helpers.toPath(), requirementsPath, configPath));
            for (Path directory = module; directory != null && directory.startsWith(repository); directory = directory.getParent()) {
                globalInputs.add(directory.resolve("pom.xml"));
            }
            var result = new ImpactAnalyzer().analyze(repository, sourceDirectory.toPath(), ObjectRegistry.load(registryPath),
                    helpers.toPath(), catalog, config, changed, globalInputs);
            var document = json.valueToTree(result);
            ((com.fasterxml.jackson.databind.node.ObjectNode) document).put("baseRevision", baseSha);
            ((com.fasterxml.jackson.databind.node.ObjectNode) document).put("analysisSnapshot", "working_tree");
            Path output = report.toPath();
            Files.createDirectories(output.toAbsolutePath().getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), document);
            getLog().info(result.selected().size() + " TestTR senaryosu seçildi (" + result.mode() + "): " + output);
            if (!result.unknownFiles().isEmpty()) getLog().warn("Eşlenemeyen değişiklikler var; tüm TestTR senaryoları seçildi.");
            return result;
        } catch (Exception exception) {
            throw new MojoExecutionException("Etki analizi: " + exception.getMessage(), exception);
        }
    }

    private static List<String> paths(String text) {
        return Arrays.stream(text.split("\u0000")).filter(value -> !value.isEmpty()).toList();
    }

    static String git(Path directory, String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        // Git warnings (for example CRLF conversion) are not changed file names.
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) throw new IOException("Git komutu başarısız: " + arguments[0]);
        return new String(output, StandardCharsets.UTF_8);
    }
}
