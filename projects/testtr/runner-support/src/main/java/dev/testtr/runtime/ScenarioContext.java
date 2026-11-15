package dev.testtr.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** A fresh instance belongs to exactly one scenario, including its cleanup. */
public final class ScenarioContext {
    private record Value(String text, boolean secret) {}
    private final String runId = UUID.randomUUID().toString();
    private final Map<String, Value> values = new HashMap<>();
    private final Function<String, String> settings;
    private final Function<String, String> secrets;
    private boolean sensitive;

    public ScenarioContext() {
        this(System::getProperty, System::getenv);
    }

    public ScenarioContext(Function<String, String> settings, Function<String, String> secrets) {
        this.settings = settings;
        this.secrets = secrets;
    }

    public String runId() { return runId; }
    public void reportCleanup(String status) {
        System.out.printf("TESTTR_RUN {\"runId\":\"%s\",\"cleanupStatus\":\"%s\"}%n", runId, status);
    }

    public void markSensitive() { sensitive = true; }

    public void setting(String key, String variable) {
        put(variable, require(settings.apply(key), key), false);
    }

    public void secret(String key, String variable) {
        sensitive = true;
        put(variable, require(secrets.apply(key), key), true);
    }

    public void put(String name, String value, boolean secret) {
        if (secret) sensitive = true;
        values.put(name, new Value(require(value, name), secret));
    }

    public String value(String name) {
        Value value = values.get(name);
        if (value == null) throw new MissingTestValueException("Değişken henüz oluşturulmadı: " + name);
        return value.text();
    }

    public AssertionError failure(String location, Throwable cause) {
        FailureCode code = FailureCode.from(cause);
        // Browser errors can contain passwords in selectors, values, or nested causes.
        if (sensitive) return new ScenarioFailure(location + " [Gizli değer kullanılan koşumda hata ayrıntıları gizlendi]", code, null);
        if (Boolean.getBoolean("testtr.redactDiagnostics")) {
            if (cause instanceof UiContract.SafeObservationFailure)
                return new ScenarioFailure(location, code, cause);
            return new ScenarioFailure(location + " [Hata ayrıntıları gizlendi]", code, null);
        }
        return new ScenarioFailure(location, code, cause);
    }

    private String require(String value, String name) {
        if (value == null) throw new MissingTestValueException("Değer bulunamadı: " + name);
        return value;
    }
}
