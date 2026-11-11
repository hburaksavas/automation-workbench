package dev.testtr.maven;

import dev.testtr.ImpactAnalyzer;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImpactSelectionTest {
    private ImpactAnalyzer.Report report(String... ids) {
        return new ImpactAnalyzer.Report(1, ids.length == 0 ? "none" : "selected", List.of(), List.of(), List.of(),
                java.util.Arrays.stream(ids).map(id -> new ImpactAnalyzer.Selection(id, "a.testtr", "A", List.of(), List.of(), List.of())).toList(), 0);
    }

    @Test void selectsExactGeneratedClassesAndFailsIfNoClassMatches() {
        var user = new Properties();
        var project = new Properties();
        String id = "Scenario_" + "a".repeat(64) + "Test";
        ImpactSelection.apply(report(id), user, project);
        assertEquals("dev.testtr.generated." + id, user.getProperty("test"));
        assertEquals(user.getProperty("test"), project.getProperty("test"));
        assertEquals("true", user.getProperty("surefire.failIfNoSpecifiedTests"));
        assertNull(user.getProperty("skipTests"));
    }

    @Test void emptySelectionExplicitlySkipsInsteadOfRunningAllTests() {
        var user = new Properties();
        ImpactSelection.apply(report(), user, new Properties());
        assertEquals("true", user.getProperty("skipTests"));
        assertNull(user.getProperty("test"));
    }

    @Test void conflictingUserSettingsAndInvalidIdentifiersFailBeforeMutation() {
        for (String key : List.of("test", "skipTests", "maven.test.skip")) {
            var user = new Properties();
            user.setProperty(key, key.equals("test") ? "ExistingTest" : "true");
            assertThrows(IllegalArgumentException.class, () -> ImpactSelection.apply(report(), user, new Properties()));
        }
        var user = new Properties();
        assertThrows(IllegalArgumentException.class, () -> ImpactSelection.apply(report("*"), user, new Properties()));
        assertTrue(user.isEmpty());
    }
}
