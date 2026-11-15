package dev.testtr.runtime;

/** Stable, value-free diagnostic vocabulary; no selectors, expected values or secrets. */
public enum FailureCode {
    UNKNOWN, ASSERTION_MISMATCH, URL_MISMATCH, ELEMENT_MISSING, LOCATOR_AMBIGUOUS,
    ELEMENT_TYPE_MISMATCH, ACTION_NOT_READY, OPTION_MISSING, OPTION_AMBIGUOUS,
    OPTION_DISABLED, DATA_MISSING, ENVIRONMENT_FAILURE;

    static FailureCode from(Throwable failure) {
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && visited.size() < 32 && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof ScenarioFailure scenario) return scenario.code();
            if (cause instanceof UiFailure ui) return ui.code();
            if (cause instanceof MissingTestValueException) return DATA_MISSING;
            if (java.util.Set.of("org.openqa.selenium.SessionNotCreatedException", "org.openqa.selenium.remote.NoSuchDriverException",
                    "java.lang.UnsupportedClassVersionError", "java.lang.UnsatisfiedLinkError").contains(cause.getClass().getName())) return ENVIRONMENT_FAILURE;
        }
        return UNKNOWN;
    }
}
