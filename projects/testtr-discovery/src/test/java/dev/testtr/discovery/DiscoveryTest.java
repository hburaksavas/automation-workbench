package dev.testtr.discovery;

import com.fasterxml.jackson.databind.*;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryTest {
    @TempDir Path temp;
    @Test void cdpRunLeavesExistingBrowserAndTabsOpen() throws Exception {
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        Path html = temp.resolve("input-page.html");
        Files.writeString(html, "<button data-testid='remote'>Remote</button>");
        Path report = temp.resolve("remote.json");
        try (Playwright pw = Playwright.create(); Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setArgs(java.util.List.of("--remote-debugging-port=" + port)));
             BrowserContext existing = browser.newContext()) {
            Page original = existing.newPage();
            Main.main(new String[]{"--url", html.toUri().toString(), "--cdp", "http://127.0.0.1:" + port, "--duration", "1", "--output", report.toString()});
            assertTrue(browser.isConnected()); assertFalse(original.isClosed());
            assertEquals(1, browser.contexts().size());
            assertTrue(new ObjectMapper().readTree(report.toFile()).path("objects").findValuesAsText("name").contains("Remote"), Files.readString(report));
        }
    }
    @Test void recordsDynamicScreensInteractionsFramesAndNavigationWithoutValues() throws Exception {
        Path output = temp.resolve("report.json");
        try (Playwright pw = Playwright.create(); Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             BrowserContext context = browser.newContext()) {
            new DiscoverySession(context, new Recorder(output));
            Page page = context.newPage();
            context.route("http://discovery.test/**", route -> route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody("""
                <label for="no">Müşteri No</label><input id="no" data-testid="customer-number">
                <input type="password" id="secret"><button data-testid="open" onclick="document.querySelector('#modal').hidden=false">Aç</button>
                <section id="modal" hidden><button data-testid="save">Kaydet</button></section>
                <button class="duplicate">Aynı</button><button class="duplicate">Aynı</button>
                <iframe srcdoc="<button id='inside'>İç düğme</button>"></iframe>
                """)));
            page.navigate("http://discovery.test/start?token=PRIVATE_TOKEN"); page.waitForTimeout(400);
            page.locator("#no").fill("PRIVATE_CUSTOMER_VALUE");
            page.locator("#secret").fill("PRIVATE_PASSWORD");
            page.getByTestId("open").click(); page.getByTestId("save").click(); page.waitForTimeout(400);
            JsonNode report = new ObjectMapper().readTree(output.toFile());
            assertTrue(report.path("objects").findValuesAsText("name").contains("Müşteri No"));
            assertTrue(report.path("interactions").size() >= 3);
            assertTrue(report.path("objects").findValuesAsText("name").contains("İç düğme"));
            assertTrue(report.path("objects").findValuesAsText("quality").contains("structural"));
            boolean visibleSave = false, interactedNumber = false;
            for (JsonNode obj : report.path("objects")) {
                if (obj.path("name").asText().equals("Kaydet")) visibleSave |= obj.path("visible").asBoolean();
                if (obj.path("name").asText().equals("Müşteri No")) interactedNumber |= obj.path("interacted").asBoolean();
                assertEquals(1, obj.path("matches").asInt());
            }
            assertTrue(visibleSave); assertTrue(interactedNumber);
            String raw = Files.readString(output);
            assertFalse(raw.contains("PRIVATE_CUSTOMER_VALUE")); assertFalse(raw.contains("PRIVATE_PASSWORD")); assertFalse(raw.contains("PRIVATE_TOKEN"));
            for (JsonNode event : report.path("interactions")) assertNotEquals("secret", event.path("object").path("name").asText());
            page.navigate("http://discovery.test/next"); page.waitForTimeout(400);
            report = new ObjectMapper().readTree(output.toFile());
            assertTrue(report.path("objects").findValuesAsText("url").contains("http://discovery.test/next"));
            Page popup = context.newPage(); popup.navigate("http://discovery.test/popup"); popup.waitForTimeout(400);
            report = new ObjectMapper().readTree(output.toFile());
            assertTrue(report.path("objects").findValuesAsText("page").contains("page-2"));
            // Future automation uses exactly the same observation pipeline.
            Path flow = temp.resolve("flow.json"); Files.writeString(flow,"[{\"action\":\"click\",\"css\":\"[data-testid=open]\"},{\"action\":\"waitFor\",\"css\":\"[data-testid=save]\"},{\"action\":\"scan\"}]");
            Main.runFlow(popup, flow);
        }
    }
}

