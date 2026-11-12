package dev.testtr.discovery;

import com.fasterxml.jackson.databind.*;
import com.microsoft.playwright.*;
import java.nio.file.*;
import java.util.*;

public final class DiscoveryRunner {
    public void run(Map<String,String> options, java.util.concurrent.atomic.AtomicBoolean cancelled) throws Exception {
        if (!options.containsKey("url")) throw new IllegalArgumentException("--url is required");
        if (options.containsKey("connect") && options.containsKey("cdp")) throw new IllegalArgumentException("Choose --connect or --cdp");
        long seconds = Long.parseLong(options.getOrDefault("duration","0"));
        if (seconds < 0 || seconds > 86400) throw new IllegalArgumentException("duration must be 0..86400 seconds");
        if (options.containsKey("headless") && !Set.of("true","false").contains(options.get("headless"))) throw new IllegalArgumentException("headless must be true or false");
        try (Playwright pw = Playwright.create()) {
            Browser browser = open(pw, options);
            try {
            // Own only this context, including when attached to a remote browser.
            try (BrowserContext context = browser.newContext()) {
                Recorder recorder = new Recorder(Path.of(options.getOrDefault("output","target/discovery.json")));
                new DiscoverySession(context, recorder);
                Page page = context.newPage();
                page.setDefaultTimeout(10_000); page.setDefaultNavigationTimeout(15_000);
                page.onConsoleMessage(message -> { if (message.text().startsWith("Discovery recording failed")) System.err.println(message.text()); });
                page.navigate(options.get("url"));
                page.evaluate("() => window.__testtrScan()");
                if (options.containsKey("flow")) runFlow(page, Path.of(options.get("flow")), cancelled);
                System.err.println("Discovery active; close its browser window to finish. Output: " + options.getOrDefault("output","target/discovery.json"));
                System.err.println("HTML report: " + recorder.htmlOutput());
                long deadline = seconds == 0 ? Long.MAX_VALUE : System.nanoTime() + seconds * 1_000_000_000L;
                if (options.containsKey("flow") && seconds == 0) deadline = System.nanoTime() + 500_000_000L;
                while (!cancelled.get() && browser.isConnected() && !context.pages().isEmpty() && System.nanoTime() < deadline) {
                    Page active = context.pages().get(0);
                    try { active.waitForTimeout(100); }
                    catch (PlaywrightException e) { if (browser.isConnected() && !active.isClosed()) throw e; }
                }
                recorder.save();
            }
            } finally { if (!options.containsKey("connect") && !options.containsKey("cdp")) browser.close(); }
        }
    }
    static Browser open(Playwright pw, Map<String,String> options) {
            BrowserType type = switch(options.getOrDefault("browser","chromium")) {
                case "chromium" -> pw.chromium(); case "firefox" -> pw.firefox(); case "webkit" -> pw.webkit();
                default -> throw new IllegalArgumentException("browser: chromium, firefox or webkit");
            };
            Browser browser;
            if (options.containsKey("cdp")) {
                if (!type.name().equals("chromium")) throw new IllegalArgumentException("CDP requires Chromium");
                browser = type.connectOverCDP(options.get("cdp"), new BrowserType.ConnectOverCDPOptions().setTimeout(15_000));
            } else if (options.containsKey("connect")) browser = type.connect(options.get("connect"), new BrowserType.ConnectOptions().setTimeout(15_000));
            else {
                BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setTimeout(15_000).setHeadless(Boolean.parseBoolean(options.getOrDefault("headless","false")));
                if (options.containsKey("channel")) launch.setChannel(options.get("channel"));
                browser = type.launch(launch);
            }
            return browser;
    }
    static void runFlow(Page page, Path file) throws Exception {
        runFlow(page, file, new java.util.concurrent.atomic.AtomicBoolean());
    }
    static void runFlow(Page page, Path file, java.util.concurrent.atomic.AtomicBoolean cancelled) throws Exception {
        JsonNode steps = new ObjectMapper().readTree(file.toFile());
        if (!steps.isArray()) throw new IllegalArgumentException("Flow must be a JSON array");
        for (JsonNode step : steps) {
            if (cancelled.get()) throw new java.util.concurrent.CancellationException();
            String css = step.path("css").asText();
            switch (step.path("action").asText()) {
                case "click" -> page.locator(css).click();
                case "waitFor" -> page.locator(css).waitFor();
                case "fill", "select" -> {
                    String env = step.path("env").asText(); String value = System.getenv(env);
                    if (value == null) throw new IllegalArgumentException("Missing environment parameter: " + env);
                    if (step.path("action").asText().equals("fill")) page.locator(css).fill(value); else page.locator(css).selectOption(value);
                }
                case "check" -> page.locator(css).setChecked(step.path("checked").asBoolean(true));
                case "scan" -> page.evaluate("window.__testtrScan()");
                default -> throw new IllegalArgumentException("Unknown flow action: " + step.path("action").asText());
            }
        }
    }
}
