package dev.testtr.runtime;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioContextTest {
    @Test void readsSettingsAndSecretsOnlyAtRuntime() {
        var context = new ScenarioContext(key -> "configured-user", key -> "private-password");
        context.setting("user", "Kullanıcı");
        context.secret("password", "Parola");
        assertEquals("configured-user", context.value("Kullanıcı"));
        assertEquals("private-password", context.value("Parola"));
    }

    @Test void sanitizesTheEntireFailureChainAfterReadingASecret() {
        var context = new ScenarioContext(key -> null, key -> "private-password");
        context.secret("password", "Parola");
        var driverFailure = new IllegalStateException("value=private-password",
                new RuntimeException("nested private-password"));
        driverFailure.addSuppressed(new RuntimeException("suppressed private-password"));
        var safe = context.failure("giris.testtr:4", driverFailure);
        var output = new StringWriter();
        safe.printStackTrace(new PrintWriter(output));
        assertFalse(output.toString().contains("private-password"));
        assertTrue(output.toString().contains("giris.testtr:4"));
        assertNull(safe.getCause());
    }

    @Test void isolatesRunsAndRejectsUninitializedVariables() {
        var first = new ScenarioContext();
        var second = new ScenarioContext();
        first.put("Müşteri", "123", false);
        assertNotEquals(first.runId(), second.runId());
        assertThrows(IllegalStateException.class, () -> second.value("Müşteri"));
        assertThrows(IllegalStateException.class, () -> new ScenarioContext(key -> null, key -> null).secret("missing", "Parola"));
    }
}
