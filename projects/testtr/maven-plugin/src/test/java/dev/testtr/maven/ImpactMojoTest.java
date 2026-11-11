package dev.testtr.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ImpactMojoTest {
    @TempDir Path root;

    @BeforeEach void setup() throws Exception {
        Files.createDirectories(root.resolve("tests"));
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve(".gitignore"), "target/\n");
        Files.writeString(root.resolve("registry.json"), "{\"pages\":{\"A\":{\"url\":\"a.html\"},\"B\":{\"url\":\"b.html\"}}}");
        Files.writeString(root.resolve("impact.json"), "{\"schemaVersion\":1,\"pages\":{\"A\":[\"app/Login.*\"]}}");
        Files.writeString(root.resolve("app/Login.js"), "export default function Login() {}\n");
        Files.writeString(root.resolve("tests/a.testtr"), "Senaryo: \"A testi\".\n\"A\" sayfası açılır.\nSenaryo sonu.\n");
        Files.writeString(root.resolve("tests/b.testtr"), "Senaryo: \"B testi\".\n\"B\" sayfası açılır.\nSenaryo sonu.\n");
        ImpactMojo.git(root, "init");
        ImpactMojo.git(root, "config", "core.autocrlf", "true");
        ImpactMojo.git(root, "config", "core.safecrlf", "warn");
        ImpactMojo.git(root, "add", ".");
        ImpactMojo.git(root, "-c", "user.name=TestTR", "-c", "user.email=testtr@example.invalid", "commit", "-m", "fixture");
    }

    private ImpactMojo mojo() throws Exception {
        var mojo = new ImpactMojo();
        var project = new MavenProject();
        project.setFile(root.resolve("pom.xml").toFile());
        set(mojo, "project", project);
        set(mojo, "sourceDirectory", root.resolve("tests").toFile());
        set(mojo, "registry", root.resolve("registry.json").toFile());
        set(mojo, "helpers", root.resolve("helpers.json").toFile());
        set(mojo, "impactConfig", root.resolve("impact.json").toFile());
        set(mojo, "base", "HEAD");
        set(mojo, "report", root.resolve("target/impact.json").toFile());
        return mojo;
    }

    private static void set(ImpactMojo mojo, String name, Object value) throws Exception {
        var field = ImpactMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
    }

    private JsonNode analyze() throws Exception {
        mojo().execute();
        return new ObjectMapper().readTree(root.resolve("target/impact.json").toFile());
    }

    @Test void tracksWorktreeStagedRenamedDeletedAndUntrackedPathsWithoutLosingUnicode() throws Exception {
        assertEquals("none", analyze().path("mode").asText());
        Files.writeString(root.resolve("app/Login.js"), "changed\n");
        assertEquals("A testi", analyze().path("selected").get(0).path("name").asText());
        ImpactMojo.git(root, "add", "app/Login.js");
        assertEquals(1, analyze().path("selected").size());
        ImpactMojo.git(root, "mv", "app/Login.js", "app/Login.jsx");
        assertEquals(2, analyze().path("changedFiles").size());
        assertEquals(1, analyze().path("selected").size());
        Files.delete(root.resolve("app/Login.jsx"));
        assertEquals(1, analyze().path("selected").size());
        Files.writeString(root.resolve("yeni dosya-ş.js"), "untracked\n");
        var report = analyze();
        assertEquals("all", report.path("mode").asText());
        assertEquals(2, report.path("selected").size());
        assertEquals("yeni dosya-ş.js", report.path("unknownFiles").get(0).asText());
        assertTrue(report.path("baseRevision").asText().matches("[a-f0-9]{40,64}"));
    }

    @Test void comparesCommittedChangeToBaseRevisionAndRejectsInvalidBase() throws Exception {
        Files.writeString(root.resolve("app/Login.js"), "committed change\n");
        ImpactMojo.git(root, "add", ".");
        ImpactMojo.git(root, "-c", "user.name=TestTR", "-c", "user.email=testtr@example.invalid", "commit", "-m", "change");
        var mojo = mojo();
        set(mojo, "base", "HEAD~1");
        mojo.execute();
        var result = new ObjectMapper().readTree(root.resolve("target/impact.json").toFile());
        assertEquals(1, result.path("selected").size());
        set(mojo, "base", "--invalid-option");
        assertThrows(org.apache.maven.plugin.MojoExecutionException.class, mojo::execute);
    }
}
