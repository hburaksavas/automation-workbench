package dev.testtr.maven;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.maven.plugin.MojoExecutionException;
import static org.junit.jupiter.api.Assertions.*;

class AnalyzeMojoTest {
    @TempDir Path root;
    private AnalyzeMojo mojo(Path reports, Path output) throws Exception {
        var mojo = new AnalyzeMojo();
        var reportsField = AnalyzeMojo.class.getDeclaredField("reports"); reportsField.setAccessible(true); reportsField.set(mojo, reports.toFile());
        var outputField = AnalyzeMojo.class.getDeclaredField("output"); outputField.setAccessible(true); outputField.set(mojo, output.toFile());
        return mojo;
    }
    @Test void writesSanitizedAnalysisAndPreservesFailedResult() throws Exception {
        Path reports = Files.createDirectory(root.resolve("reports"));
        Files.writeString(reports.resolve("TEST-failed.xml"), "<testsuite><testcase><failure type=\"org.openqa.selenium.SessionNotCreatedException\">PRIVATE_PASSWORD</failure></testcase></testsuite>");
        Path output = root.resolve("output/analysis.json");
        mojo(reports, output).execute();
        var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.toFile());
        assertEquals("environment", result.at("/suites/0/testcases/0/analysis/category").asText());
        assertEquals("failed", result.at("/suites/0/testcases/0/status").asText());
        assertFalse(result.toString().contains("PRIVATE_PASSWORD"));
    }
    @Test void cannotOverwriteInputReportsAndMissingInputIsNotSuccess() throws Exception {
        Path reports = Files.createDirectory(root.resolve("reports"));
        Path xml = reports.resolve("TEST-existing.xml");
        String content = "<testsuite><testcase/></testsuite>"; Files.writeString(xml, content);
        assertThrows(MojoExecutionException.class, () -> mojo(reports, xml).execute());
        assertEquals(content, Files.readString(xml));
        assertThrows(MojoExecutionException.class, () -> mojo(root.resolve("missing"), root.resolve("out.json")).execute());
    }
}
