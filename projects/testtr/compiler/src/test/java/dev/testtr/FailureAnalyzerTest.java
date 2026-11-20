package dev.testtr;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FailureAnalyzerTest {
    @TempDir Path directory;
    private JsonNode analyze(String cases) throws Exception {
        Path report = directory.resolve("TEST-example.xml");
        Files.writeString(report, "<testsuite name=\"suite\">" + cases + "</testsuite>");
        return new FailureAnalyzer().analyze(List.of(report));
    }
    private String failure(String code) {
        return "<testcase name=\"case\" classname=\"Scenario_1Test\"><failure type=\"dev.testtr.runtime.ScenarioFailure\" message=\"source.testtr:2 [TESTTR_DIAGNOSTIC:" + code + "]\">RAW_PASSWORD expected private-value</failure></testcase>";
    }
    @Test void classifiesTypedObservationsWithoutAuthorizingRepairsOrExportingValues() throws Exception {
        for (var pair : List.of(new String[]{"ASSERTION_MISMATCH", "application-behavior"}, new String[]{"URL_MISMATCH", "application-behavior"},
                new String[]{"LOCATOR_AMBIGUOUS", "locator"}, new String[]{"DATA_MISSING", "test-data"}, new String[]{"ENVIRONMENT_FAILURE", "environment"})) {
            var result = analyze(failure(pair[0]));
            var classification = result.at("/suites/0/testcases/0/analysis");
            assertEquals(pair[1], classification.path("category").asText());
            assertEquals("unconfirmed", classification.path("rootCause").asText());
            assertFalse(classification.path("automaticMaintenanceAllowed").asBoolean());
            assertFalse(result.toString().contains("RAW_PASSWORD"));
            assertFalse(result.toString().contains("private-value"));
        }
    }
    @Test void missingElementsAndReadinessFailuresRemainUncertain() throws Exception {
        for (String code : List.of("ELEMENT_MISSING", "ACTION_NOT_READY", "ELEMENT_TYPE_MISMATCH", "OPTION_MISSING", "OPTION_AMBIGUOUS", "OPTION_DISABLED", "UNKNOWN", "UNRECOGNIZED_PRIVATE_MARKER")) {
            var analysis = analyze(failure(code)).at("/suites/0/testcases/0/analysis");
            assertEquals("uncertain", analysis.path("category").asText(), code);
            assertEquals("insufficient-evidence", analysis.path("certainty").asText());
        }
    }
    @Test void genericTextAndSpoofedMarkersAreNotClassificationEvidence() throws Exception {
        var result = analyze("<testcase name=\"x\"><failure type=\"java.lang.AssertionError\" message=\"[TESTTR_DIAGNOSTIC:LOCATOR_AMBIGUOUS]\">timeout element missing SessionNotCreatedException</failure></testcase>");
        assertEquals("uncertain", result.at("/suites/0/testcases/0/analysis/category").asText());
        assertEquals("NO_TYPED_EVIDENCE", result.at("/suites/0/testcases/0/analysis/evidenceCodes/0").asText());
    }
    @Test void suppressedCleanupFailureCannotReplacePrimaryFailureEvidence() throws Exception {
        var result = analyze(failure("ASSERTION_MISMATCH").replace("RAW_PASSWORD expected private-value", "Suppressed: dev.testtr.runtime.ScenarioFailure: [TESTTR_DIAGNOSTIC:LOCATOR_AMBIGUOUS]"));
        assertEquals("application-behavior", result.at("/suites/0/testcases/0/analysis/category").asText());
    }
    @Test void multiplePrimaryFailuresRemainUncertain() throws Exception {
        var result = analyze("<testcase><failure type=\"java.lang.AssertionError\"/><error type=\"org.openqa.selenium.SessionNotCreatedException\"/></testcase>");
        assertEquals("MULTIPLE_PRIMARY_FAILURES", result.at("/suites/0/testcases/0/analysis/evidenceCodes/0").asText());
    }
    @Test void onlyExplicitFailedThenPassedRerunsCountAsUnstable() throws Exception {
        var result = analyze("<testcase name=\"flaky\"><flakyFailure>private</flakyFailure></testcase><testcase name=\"stable-failure\"><failure/><rerunFailure/></testcase><testcase name=\"skipped\"><skipped/></testcase>");
        assertEquals("unstable-run", result.at("/suites/0/testcases/0/analysis/category").asText());
        assertEquals("passed", result.at("/suites/0/testcases/0/status").asText());
        assertEquals("uncertain", result.at("/suites/0/testcases/1/analysis/category").asText());
        assertEquals(1, result.at("/analysisSummary/flakyCount").asInt());
        assertEquals(1, result.at("/analysisSummary/failedCount").asInt());
        assertEquals("none", result.at("/suites/0/testcases/2/analysis/category").asText());
        assertFalse(result.toString().contains("private"));
    }
    @Test void separateReportOutcomesDoNotProveFlakiness() throws Exception {
        Path first = directory.resolve("TEST-first.xml"), second = directory.resolve("TEST-second.xml");
        Files.writeString(first, "<testsuite><testcase name=\"same\" classname=\"Same\"><failure/></testcase></testsuite>");
        Files.writeString(second, "<testsuite><testcase name=\"same\" classname=\"Same\"/></testsuite>");
        var result = new FailureAnalyzer().analyze(List.of(first, second));
        assertEquals(0, result.at("/analysisSummary/flakyCount").asInt());
    }
    @Test void retryEvidencePreservesOrderAndClassifiesEachRecordedFailureWithoutExportingDetails() throws Exception {
        var result = analyze("<testcase name=\"recovered\">"
                + "<flakyFailure type=\"dev.testtr.runtime.ScenarioFailure\" message=\"PRIVATE_MESSAGE [TESTTR_DIAGNOSTIC:LOCATOR_AMBIGUOUS]\">PRIVATE_STACK</flakyFailure>"
                + "<flakyError type=\"org.openqa.selenium.SessionNotCreatedException\" message=\"PRIVATE_MESSAGE\"><stackTrace>PRIVATE_STACK</stackTrace><system-out>PRIVATE_OUTPUT</system-out></flakyError>"
                + "</testcase>");
        var test = result.at("/suites/0/testcases/0");
        assertEquals("passed", test.path("status").asText());
        assertEquals("unstable-run", test.at("/analysis/category").asText());
        assertEquals(2, test.path("retryFailures").size());
        assertEquals("flakyFailure", test.at("/retryFailures/0/kind").asText());
        assertEquals("locator", test.at("/retryFailures/0/analysis/category").asText());
        assertEquals("flakyError", test.at("/retryFailures/1/kind").asText());
        assertEquals("environment", test.at("/retryFailures/1/analysis/category").asText());
        for (var retry : test.path("retryFailures")) {
            assertEquals("unconfirmed", retry.at("/analysis/rootCause").asText());
            assertFalse(retry.at("/analysis/automaticMaintenanceAllowed").asBoolean());
        }
        assertEquals(2, result.at("/analysisSummary/retryFailureCount").asInt());
        assertEquals(1, result.at("/analysisSummary/testCount").asInt());
        assertEquals(1, result.at("/analysisSummary/flakyCount").asInt());
        assertEquals(0, result.at("/analysisSummary/failedCount").asInt());
        assertFalse(result.toString().contains("PRIVATE_"));
        assertFalse(result.at("/analysisSummary/categories").has("locator"));
    }
    @Test void failedRetriesDoNotOverrideFinalFailureOrProveChangedOutcome() throws Exception {
        var result = analyze(failure("ASSERTION_MISMATCH").replace("</testcase>",
                "<rerunError type=\"org.openqa.selenium.SessionNotCreatedException\"/>"
                + "<rerunFailure type=\"java.lang.AssertionError\" message=\"[TESTTR_DIAGNOSTIC:LOCATOR_AMBIGUOUS]\"/>"
                + "</testcase>"));
        var test = result.at("/suites/0/testcases/0");
        assertEquals("failed", test.path("status").asText());
        assertEquals("application-behavior", test.at("/analysis/category").asText());
        assertEquals("rerunError", test.at("/retryFailures/0/kind").asText());
        assertEquals("environment", test.at("/retryFailures/0/analysis/category").asText());
        assertEquals("uncertain", test.at("/retryFailures/1/analysis/category").asText());
        assertEquals(2, result.at("/analysisSummary/retryFailureCount").asInt());
        assertEquals(0, result.at("/analysisSummary/flakyCount").asInt());
        assertEquals(1, result.at("/analysisSummary/failedCount").asInt());
    }
    @Test void nestedRetryDetailsAreNotAdditionalAttemptsAndAbsentRetriesAreExplicitlyEmpty() throws Exception {
        var result = analyze("<testcase><failure><rerunFailure/></failure></testcase><testcase/>");
        assertTrue(result.at("/suites/0/testcases/0/retryFailures").isEmpty());
        assertTrue(result.at("/suites/0/testcases/1/retryFailures").isEmpty());
        assertEquals(0, result.at("/analysisSummary/retryFailureCount").asInt());
    }
    @Test void readsContainerReportsAndRejectsExternalEntitiesAndInvalidSchema() throws Exception {
        Path report = directory.resolve("TEST-container.xml");
        Files.writeString(report, "<testsuites><testsuite><testcase name=\"a\"/></testsuite><testsuite><testcase name=\"b\"/></testsuite></testsuites>");
        assertEquals(2, new FailureAnalyzer().analyze(List.of(report)).at("/analysisSummary/testCount").asInt());
        Files.writeString(report, "<!DOCTYPE testsuite [<!ENTITY secret SYSTEM 'file:///private'>]><testsuite><testcase>&secret;</testcase></testsuite>");
        assertThrows(Exception.class, () -> new FailureAnalyzer().analyze(List.of(report)));
        Files.writeString(report, "<not-junit/>");
        assertThrows(IllegalArgumentException.class, () -> new FailureAnalyzer().analyze(List.of(report)));
    }
    @Test void emptyReportDirectoryIsExplicitlyEmptyAndMissingDirectoryIsAnError() throws Exception {
        var analyzer = new FailureAnalyzer();
        assertEquals(0, analyzer.analyze(analyzer.reports(directory)).at("/analysisSummary/testCount").asInt());
        assertThrows(IllegalArgumentException.class, () -> analyzer.reports(directory.resolve("missing")));
    }
    @Test void nestedSuitesCountEachTestOnceAndPreserveDocumentOrder() throws Exception {
        Path report = directory.resolve("TEST-nested.xml");
        Files.writeString(report, "<testsuites><testsuite name=\"parent\" tests=\"3\">"
                + "<testcase name=\"direct\"/>"
                + "<testsuite name=\"child\">" + failure("DATA_MISSING") + "</testsuite>"
                + "<testsuites><testsuite name=\"grandchild\"><testcase name=\"flaky\"><flakyError/></testcase></testsuite></testsuites>"
                + "</testsuite><testsuite name=\"last\"><testcase><skipped/></testcase></testsuite></testsuites>");
        var result = new FailureAnalyzer().analyze(List.of(report));
        assertEquals(4, result.at("/analysisSummary/testCount").asInt());
        assertEquals(1, result.at("/analysisSummary/failedCount").asInt());
        assertEquals(1, result.at("/analysisSummary/flakyCount").asInt());
        assertEquals(List.of("parent", "child", "grandchild", "last"),
                java.util.stream.StreamSupport.stream(result.path("suites").spliterator(), false)
                        .map(suite -> suite.path("name").asText()).toList());
        assertEquals(1, result.at("/suites/0/testcases").size());
        assertEquals("test-data", result.at("/suites/1/testcases/0/analysis/category").asText());
        assertFalse(result.toString().contains("RAW_PASSWORD"));
    }
    @Test void suiteRootCanContainNestedSuitesAndEmptyContainerIsValid() throws Exception {
        var result = analyze("<testsuite name=\"nested\"><testcase/></testsuite>");
        assertEquals(1, result.at("/analysisSummary/testCount").asInt());
        Path report = directory.resolve("TEST-empty.xml");
        Files.writeString(report, "<testsuites/>");
        assertEquals(0, new FailureAnalyzer().analyze(List.of(report)).at("/analysisSummary/testCount").asInt());
    }
}
