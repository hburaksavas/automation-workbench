package dev.testtr.runtime;

/** Retains a machine-readable code even when the original failure must be redacted. */
public final class ScenarioFailure extends AssertionError {
    private final FailureCode code;
    ScenarioFailure(String message, FailureCode code, Throwable cause) {
        super(message + " [TESTTR_DIAGNOSTIC:" + code.name() + "]");
        this.code = code;
        if (cause != null) initCause(cause);
    }
    public FailureCode code() { return code; }
}
