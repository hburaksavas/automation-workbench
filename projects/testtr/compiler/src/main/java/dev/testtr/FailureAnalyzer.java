package dev.testtr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Deterministic observation classification. Never changes expected values, Registry or tests. */
public final class FailureAnalyzer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CODE = Pattern.compile("\\[TESTTR_DIAGNOSTIC:([A-Z_]+)]$");
    private static final Set<String> ENVIRONMENT = Set.of("org.openqa.selenium.SessionNotCreatedException",
            "org.openqa.selenium.remote.NoSuchDriverException", "java.lang.UnsupportedClassVersionError", "java.lang.UnsatisfiedLinkError");
    private static final Set<String> AMBIGUOUS = Set.of("ELEMENT_MISSING", "ELEMENT_TYPE_MISMATCH", "ACTION_NOT_READY", "OPTION_MISSING", "OPTION_AMBIGUOUS", "OPTION_DISABLED");

    public List<Path> reports(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("JUnit report directory does not exist");
        try (var files = Files.list(directory)) {
            var reports = files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).sorted().toList();
            if (reports.size() > 1000) throw new IllegalArgumentException("JUnit report count exceeds 1000");
            return reports;
        }
    }
    public ObjectNode analyze(List<Path> reports) throws Exception {
        if (reports.size() > 1000) throw new IllegalArgumentException("JUnit report count exceeds 1000");
        var result = JSON.createObjectNode().put("schemaVersion", 1);
        var suites = result.putArray("suites");
        var summary = result.putObject("analysisSummary");
        var categories = summary.putObject("categories");
        int total = 0, failed = 0, flaky = 0, uncertain = 0, retryFailures = 0;
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        for (Path file : reports) {
            if (Files.size(file) > 4_000_000) throw new IllegalArgumentException("JUnit report exceeds 4MB");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            Element root = builder.parse(file.toFile()).getDocumentElement();
            if (!Set.of("testsuite", "testsuites").contains(root.getTagName())) throw new IllegalArgumentException("Expected JUnit testsuite or testsuites");
            List<Element> elements = suites(root);
            for (Element suite : elements) {
                var item = suites.addObject();
                for (String key : List.of("name", "tests", "failures", "errors", "skipped", "time")) item.put(key, suite.getAttribute(key));
                var cases = item.putArray("testcases");
                for (Element test : children(suite, "testcase")) {
                    List<Element> failures = children(test, "failure"), errors = children(test, "error");
                    String status = !failures.isEmpty() ? "failed" : !errors.isEmpty() ? "error" : !children(test, "skipped").isEmpty() ? "skipped" : "passed";
                    boolean changedOutcome = status.equals("passed") && (!children(test, "flakyFailure").isEmpty() || !children(test, "flakyError").isEmpty());
                    var entry = cases.addObject().put("name", test.getAttribute("name")).put("className", test.getAttribute("classname")).put("status", status);
                    var primary = new ArrayList<Element>(failures); primary.addAll(errors);
                    ObjectNode analysis = classify(primary, status, changedOutcome);
                    entry.set("analysis", analysis);
                    var retries = entry.putArray("retryFailures");
                    for (var child = test.getFirstChild(); child != null; child = child.getNextSibling()) {
                        if (child instanceof Element retry && Set.of("flakyFailure", "flakyError", "rerunFailure", "rerunError").contains(retry.getTagName())) {
                            var attempt = retries.addObject().put("kind", retry.getTagName());
                            attempt.set("analysis", classify(List.of(retry), "failed", false));
                            retryFailures++;
                        }
                    }
                    String category = analysis.path("category").asText();
                    total++;
                    if (Set.of("failed", "error").contains(status)) failed++;
                    if (changedOutcome) flaky++;
                    if (category.equals("uncertain")) uncertain++;
                    if (!category.equals("none")) categories.put(category, categories.path(category).asInt() + 1);
                }
            }
        }
        summary.put("testCount", total).put("failedCount", failed).put("flakyCount", flaky).put("uncertainCount", uncertain)
                .put("retryFailureCount", retryFailures);
        result.put("evidencePolicy", "Typed observations only; root cause is unconfirmed. No automatic repair or cross-run flakiness inference.");
        return result;
    }
    private ObjectNode classify(List<Element> failures, String status, boolean changedOutcome) {
        String code = "NO_TYPED_EVIDENCE", category = "uncertain";
        List<String> possible = List.of("application-behavior", "locator", "test-data", "environment");
        if (changedOutcome) {
            code = "RERUN_OUTCOME_CHANGED"; category = "unstable-run"; possible = List.of("application-behavior", "test-data", "environment");
        } else if (failures.isEmpty()) {
            code = "NO_FAILURE"; category = "none"; possible = List.of();
        } else if (failures.size() > 1) code = "MULTIPLE_PRIMARY_FAILURES";
        else {
            Element failure = failures.get(0);
            String type = failure.getAttribute("type");
            if (type.equals("dev.testtr.runtime.ScenarioFailure")) {
                var match = CODE.matcher(failure.getAttribute("message"));
                if (match.find()) code = match.group(1);
            } else if (type.equals("dev.testtr.runtime.MissingTestValueException")) code = "DATA_MISSING";
            else if (ENVIRONMENT.contains(type)) code = "ENVIRONMENT_FAILURE";
            switch (code) {
                case "ASSERTION_MISMATCH", "URL_MISMATCH" -> { category = "application-behavior"; possible = List.of("application-behavior", "test-data", "environment"); }
                case "LOCATOR_AMBIGUOUS" -> { category = "locator"; possible = List.of("locator", "application-behavior"); }
                case "DATA_MISSING" -> { category = "test-data"; possible = List.of("test-data"); }
                case "ENVIRONMENT_FAILURE" -> { category = "environment"; possible = List.of("environment"); }
                default -> { if (!AMBIGUOUS.contains(code)) code = "NO_TYPED_EVIDENCE"; }
            }
        }
        var node = JSON.createObjectNode().put("category", category)
                .put("certainty", category.equals("none") ? "not-applicable" : category.equals("uncertain") ? "insufficient-evidence" : "observed")
                .put("rootCause", "unconfirmed").put("automaticMaintenanceAllowed", false);
        node.putArray("evidenceCodes").add(code);
        node.set("possibleCategories", JSON.valueToTree(possible));
        node.put("note", category.equals("application-behavior") ? "Observed behavior differs from the test expectation; requirement correctness and root cause require review."
                : category.equals("locator") ? "Locator matched multiple elements; DOM changes may also explain this observation."
                : category.equals("unstable-run") ? "This JUnit testcase passed after a failed rerun attempt; the cause is unknown."
                : category.equals("none") ? "No failure classification is needed."
                : category.equals("uncertain") ? "Evidence cannot distinguish the cause; do not infer locator damage from a missing element or a generic timeout."
                : "Typed failure identifies an observed category; no maintenance action is authorized.");
        return node;
    }
    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) result.add(element);
        }
        return result;
    }
    private static List<Element> suites(Element root) {
        List<Element> result = new ArrayList<>();
        Deque<Element> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Element element = pending.pop();
            if (element.getTagName().equals("testsuite")) result.add(element);
            // Traverse only suite containers, never failure details or captured output.
            for (var child = element.getLastChild(); child != null; child = child.getPreviousSibling()) {
                if (child instanceof Element nested && Set.of("testsuite", "testsuites").contains(nested.getTagName())) pending.push(nested);
            }
        }
        return result;
    }
}
