package dev.testtr.maven;

import dev.testtr.ImpactAnalyzer;
import java.util.Properties;
import java.util.stream.Collectors;

final class ImpactSelection {
    private ImpactSelection() {}

    static void check(Properties user, Properties project) {
        for (String key : new String[]{"test", "skipTests", "maven.test.skip"}) {
            String value = user.getProperty(key, project.getProperty(key, ""));
            if ((key.equals("test") && !value.isBlank()) || (!key.equals("test") && Boolean.parseBoolean(value)))
                throw new IllegalArgumentException("Etki seçimi ile birlikte " + key + " kullanılamaz.");
        }
    }

    static void apply(ImpactAnalyzer.Report report, Properties user, Properties project) {
        check(user, project);
        for (var selection : report.selected()) {
            if (!selection.scenarioId().matches("Scenario_[a-f0-9]{64}Test"))
                throw new IllegalArgumentException("Geçersiz senaryo sınıf kimliği.");
        }
        if (report.selected().isEmpty()) {
            user.setProperty("skipTests", "true");
            project.setProperty("skipTests", "true");
        } else {
            String tests = report.selected().stream().map(s -> "dev.testtr.generated." + s.scenarioId())
                    .distinct().collect(Collectors.joining(","));
            user.setProperty("test", tests);
            project.setProperty("test", tests);
            user.setProperty("surefire.failIfNoSpecifiedTests", "true");
            project.setProperty("surefire.failIfNoSpecifiedTests", "true");
        }
    }
}
