package dev.testtr;

import dev.testtr.runtime.ScenarioContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

public final class FixtureHelpers {
    private static Path file(ScenarioContext context) {
        return Path.of("target", "testtr-data", context.runId() + ".txt");
    }

    public static String createCustomer(ScenarioContext context, Map<String, String> inputs) {
        Path file = file(context);
        try {
            Files.createDirectories(file.getParent());
            String customer = inputs.get("Müşteri türü") + "-" + context.runId();
            Files.writeString(file, customer);
            return customer;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public static void cleanup(ScenarioContext context, Map<String, String> inputs) {
        try {
            Files.deleteIfExists(file(context));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
