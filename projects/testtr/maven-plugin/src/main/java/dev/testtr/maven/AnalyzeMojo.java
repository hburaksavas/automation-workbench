package dev.testtr.maven;

import dev.testtr.FailureAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

/** Analyze explicitly existing JUnit reports without rerunning tests or changing expectations. */
@Mojo(name = "analyze", threadSafe = true)
public final class AnalyzeMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.build.directory}/surefire-reports", property = "testtr.analysisReports")
    private File reports;
    @Parameter(defaultValue = "${project.build.directory}/testtr-analysis.json", property = "testtr.analysisOutput")
    private File output;

    @Override public void execute() throws MojoExecutionException {
        try {
            var analyzer = new FailureAnalyzer();
            var files = analyzer.reports(reports.toPath());
            var result = analyzer.analyze(files);
            result.put("reportScope", "existing-reports; files may belong to different runs");
            var destination = output.toPath().toAbsolutePath().normalize();
            if (files.stream().anyMatch(file -> file.toAbsolutePath().normalize().equals(destination)
                    || (Files.exists(destination) && sameFile(file, destination)))) throw new IllegalArgumentException("Analysis output must not overwrite a JUnit report");
            Files.createDirectories(destination.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), result);
            getLog().info("JUnit failure analysis: " + destination);
        } catch (Exception e) { throw new MojoExecutionException("Failure analysis failed: " + e.getClass().getSimpleName(), e); }
    }
    private static boolean sameFile(java.nio.file.Path first, java.nio.file.Path second) {
        try { return Files.isSameFile(first, second); } catch (java.io.IOException e) { return false; }
    }
}
