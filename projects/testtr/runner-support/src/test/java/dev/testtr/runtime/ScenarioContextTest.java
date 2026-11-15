package dev.testtr.runtime;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioContextTest {
    @Test void typedEvidenceSurvivesSecretRedactionAndNestedCommonCalls() {
        var context = new ScenarioContext(key -> null, key -> "private-password");
        context.secret("password", "Parola");
        var first = context.failure("ortak.testtr:8", new UiFailure(FailureCode.LOCATOR_AMBIGUOUS, "private-password"));
        var wrapped = context.failure("giris.testtr:5", first);
        assertEquals(FailureCode.LOCATOR_AMBIGUOUS, ((ScenarioFailure) wrapped).code());
        assertTrue(wrapped.getMessage().endsWith("[TESTTR_DIAGNOSTIC:LOCATOR_AMBIGUOUS]"));
        assertNull(wrapped.getCause());
        var output = new StringWriter(); wrapped.printStackTrace(new PrintWriter(output));
        assertFalse(output.toString().contains("private-password"));
    }

    @Test void missingRuntimeDataIsTypedButGenericFailuresStayUnknown() {
        var context = new ScenarioContext(key -> null, key -> null);
        var missing = assertThrows(MissingTestValueException.class, () -> context.setting("missing", "value"));
        assertEquals(FailureCode.DATA_MISSING, ((ScenarioFailure) context.failure("test.testtr:2", missing)).code());
        var generic = context.failure("test.testtr:3", new RuntimeException("element not found; connection refused; timeout"));
        assertEquals(FailureCode.UNKNOWN, ((ScenarioFailure) generic).code());
    }

    @Test void redactedDiagnosticsKeepCodeAndStripUnsafeCause() {
        String previous = System.getProperty("testtr.redactDiagnostics");
        try {
            System.setProperty("testtr.redactDiagnostics", "true");
            var error = new ScenarioContext().failure("test.testtr:4", new UiFailure(FailureCode.ELEMENT_TYPE_MISMATCH, "sensitive-label"));
            assertEquals(FailureCode.ELEMENT_TYPE_MISMATCH, ((ScenarioFailure) error).code());
            assertNull(error.getCause()); assertFalse(error.getMessage().contains("sensitive-label"));
        } finally {
            if (previous == null) System.clearProperty("testtr.redactDiagnostics"); else System.setProperty("testtr.redactDiagnostics", previous);
        }
    }

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
