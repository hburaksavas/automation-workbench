package dev.testtr.runtime;

/** Typed observation failure; existing AssertionError handling remains compatible. */
public class UiFailure extends AssertionError {
    private final FailureCode code;
    public UiFailure(FailureCode code, String message) { super(message); this.code = code; }
    public FailureCode code() { return code; }
}
