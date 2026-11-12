package dev.testtr.discovery;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.microsoft.playwright.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reopens each URL in two isolated contexts; an optional flow runs in each context. */
public final class LocatorValidator {
    public ObjectNode validate(Path proposalPath, Map<String,String> options, AtomicBoolean cancelled) throws Exception {
        ObjectNode proposal = (ObjectNode)JsonFiles.read(proposalPath);
        if (!proposal.path("candidates").isArray()) throw new IllegalArgumentException("Proposal requires candidates");
        Map<String,List<ObjectNode>> groups = new LinkedHashMap<>();
        for (JsonNode node : proposal.path("candidates")) {
            ObjectNode c = (ObjectNode)node; c.put("validation", "not-run"); c.putArray("validationEvidence");
            if (!"review".equals(c.path("status").asText())) groups.computeIfAbsent(c.path("url").asText(), k -> new ArrayList<>()).add(c);
        }
        Map<String,String> browserOptions = new HashMap<>(options); browserOptions.putIfAbsent("headless", "true");
        if (browserOptions.containsKey("cdp") && browserOptions.containsKey("connect")) throw new IllegalArgumentException("Choose connect or cdp");
        try (Playwright pw = Playwright.create()) {
            Browser browser = DiscoveryRunner.open(pw, browserOptions);
            try {
                for (var group : groups.entrySet()) for (int pass = 1; pass <= 2; pass++) {
                    if (cancelled.get()) throw new java.util.concurrent.CancellationException();
                    try (BrowserContext context = browser.newContext()) {
                        String script;
                        try (var in = getClass().getResourceAsStream("/discovery.js")) { script = new String(Objects.requireNonNull(in).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
                        context.exposeBinding("__testtrEmit", (source, args) -> null); context.addInitScript(script);
                        Page page = context.newPage(); page.setDefaultTimeout(5_000); page.setDefaultNavigationTimeout(15_000);
                        try {
                            page.navigate(group.getKey());
                            if (options.containsKey("flow")) DiscoveryRunner.runFlow(page, Path.of(options.get("flow")), cancelled);
                            for (ObjectNode c : group.getValue()) {
                                if (cancelled.get()) throw new java.util.concurrent.CancellationException();
                                ObjectNode evidence = ((ArrayNode)c.path("validationEvidence")).addObject();
                                evidence.put("pass", pass).put("time", Instant.now().toString());
                                try { check(page, c, evidence, group.getKey()); evidence.put("passed", true); }
                                catch (PlaywrightException | IllegalArgumentException e) {
                                    evidence.put("passed", false).put("reason", e instanceof PlaywrightException ? "Locator could not be resolved within timeout" : e.getMessage());
                                }
                            }
                        } catch (PlaywrightException e) {
                            for (ObjectNode c : group.getValue()) ((ArrayNode)c.path("validationEvidence")).addObject().put("pass", pass).put("passed", false).put("reason", "Page or flow failed; inspect application and parameters");
                        }
                    }
                }
            } finally { if (!options.containsKey("connect") && !options.containsKey("cdp")) browser.close(); }
        }
        for (JsonNode c : proposal.path("candidates")) {
            JsonNode evidence = c.path("validationEvidence");
            if (evidence.isEmpty()) continue;
            boolean passed = evidence.size() == 2;
            for (JsonNode e : evidence) passed &= e.path("passed").asBoolean();
            ((ObjectNode)c).put("validation", passed ? "passed" : "failed");
        }
        return proposal;
    }
    private void check(Page page, JsonNode c, ObjectNode evidence, String url) {
        if (!RegistrySuggestions.sameUrl(page.url(), url, "")) throw new IllegalArgumentException("Page redirected; target URL differs");
        Locator locator = page.locator(c.path("after").path("css").asText());
        locator.first().waitFor(new Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED));
        int matches = locator.count(); evidence.put("matches", matches);
        if (matches != 1) throw new IllegalArgumentException("Locator is not unique");
        JsonNode actual = JsonFiles.JSON.valueToTree(locator.evaluate("el => window.__testtrDescribe(el)"));
        if (!c.path("name").asText().equals(actual.path("name").asText()) || !c.path("after").path("type").asText().equals(actual.path("type").asText()))
            throw new IllegalArgumentException("Object name or type differs");
        if ("locator-change".equals(c.path("changeKind").asText()) && c.path("before").has("css")) {
            Locator old = page.locator(c.path("before").path("css").asText());
            evidence.put("oldMatches", old.count());
            if (old.count() > 0) {
                boolean same = old.count() == 1 && (boolean)locator.evaluate("(el, css) => document.querySelector(css) === el", c.path("before").path("css").asText());
                if (!same) throw new IllegalArgumentException("Old locator still resolves to another object");
            }
        }
    }
}
