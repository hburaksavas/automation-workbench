package dev.testtr.discovery;

import com.fasterxml.jackson.databind.*;
import com.microsoft.playwright.*;
import java.nio.file.*;
import java.util.*;

public final class Main {
    public static void main(String[] args) throws Exception {
        Map<String,String> options = new LinkedHashMap<>();
        Set<String> allowed = Set.of("url","output","browser","channel","connect","cdp","duration","headless","flow");
        for (int i=0; i<args.length; i++) {
            if (args[i].equals("--help")) { usage(); return; }
            if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2)) || i+1 == args.length) throw new IllegalArgumentException("Unknown or incomplete option: " + args[i]);
            options.put(args[i].substring(2), args[++i]);
        }
        if (!options.containsKey("url")) { usage(); throw new IllegalArgumentException("--url is required"); }
        if (options.containsKey("connect") && options.containsKey("cdp")) throw new IllegalArgumentException("Choose --connect or --cdp");
        long seconds = Long.parseLong(options.getOrDefault("duration","0"));
        if (seconds < 0) throw new IllegalArgumentException("duration must be >= 0");
        try (Playwright pw = Playwright.create()) {
            BrowserType type = switch(options.getOrDefault("browser","chromium")) {
                case "chromium" -> pw.chromium(); case "firefox" -> pw.firefox(); case "webkit" -> pw.webkit();
                default -> throw new IllegalArgumentException("browser: chromium, firefox or webkit");
            };
            Browser browser;
            if (options.containsKey("cdp")) {
                if (!type.name().equals("chromium")) throw new IllegalArgumentException("CDP requires Chromium");
                browser = type.connectOverCDP(options.get("cdp"));
            } else if (options.containsKey("connect")) browser = type.connect(options.get("connect"));
            else {
                BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(Boolean.parseBoolean(options.getOrDefault("headless","false")));
                if (options.containsKey("channel")) launch.setChannel(options.get("channel"));
                browser = type.launch(launch);
            }
            // Own only this context, including when attached to a remote browser.
            try (BrowserContext context = browser.newContext()) {
                Recorder recorder = new Recorder(Path.of(options.getOrDefault("output","target/discovery.json")));
                new DiscoverySession(context, recorder);
                Page page = context.newPage();
                page.onConsoleMessage(message -> { if (message.text().startsWith("Discovery recording failed")) System.err.println(message.text()); });
                page.navigate(options.get("url"));
                page.evaluate("() => window.__testtrScan()");
                if (options.containsKey("flow")) runFlow(page, Path.of(options.get("flow")));
                System.out.println("Discovery active; close its browser window to finish. Output: " + options.getOrDefault("output","target/discovery.json"));
                System.out.println("HTML report: " + recorder.htmlOutput());
                long deadline = seconds == 0 ? Long.MAX_VALUE : System.nanoTime() + seconds * 1_000_000_000L;
                if (options.containsKey("flow") && seconds == 0) deadline = System.nanoTime() + 500_000_000L;
                while (browser.isConnected() && !context.pages().isEmpty() && System.nanoTime() < deadline) {
                    Page active = context.pages().get(0);
                    try { active.waitForTimeout(100); }
                    catch (PlaywrightException e) { if (browser.isConnected() && !active.isClosed()) throw e; }
                }
                recorder.save();
            }
            if (!options.containsKey("connect") && !options.containsKey("cdp")) browser.close();
        }
    }
    static void runFlow(Page page, Path file) throws Exception {
        JsonNode steps = new ObjectMapper().readTree(file.toFile());
        if (!steps.isArray()) throw new IllegalArgumentException("Flow must be a JSON array");
        for (JsonNode step : steps) {
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
    private static void usage() {
        System.out.println("java -jar testtr-discovery.jar --url URL [--output FILE] [--browser chromium|firefox|webkit] [--channel chrome|msedge] [--connect WS_ENDPOINT | --cdp HTTP_ENDPOINT] [--headless true|false] [--duration SECONDS] [--flow FILE.json]");
    }
}
