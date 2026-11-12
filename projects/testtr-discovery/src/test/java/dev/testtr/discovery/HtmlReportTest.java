package dev.testtr.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HtmlReportTest {
    @TempDir Path temp;
    @Test void exportsReadableOfflineReportAndEscapesObservedMarkup() throws Exception {
        Recorder recorder = new Recorder(temp.resolve("session.json"));
        recorder.save();
        assertTrue(Files.readString(recorder.htmlOutput()).contains("Henüz obje keşfedilmedi"));
        var number = Map.of("name","Müşteri No", "css","[data-testid=\"customer-number\"]", "type","input", "quality","attribute", "matches",1,"visible",true,"enabled",true);
        var malicious = Map.of("name","<img src=x onerror=alert(1)>", "css","button:nth-of-type(2)", "type","button", "quality","structural", "matches",1,"visible",false,"enabled",false);
        recorder.accept("page-1","frame-1","https://example.test/customers",Map.of("kind","scan","objects",List.of(number,malicious)));
        recorder.accept("page-1","frame-1","https://example.test/customers",Map.of("kind","interaction","action","fill","object",number));
        recorder.accept("page-1","frame-2","https://example.test/details",Map.of("kind","scan","objects",List.of(Map.of("name","Kaydet","css","#save","type","button","quality","attribute","matches",1,"visible",true,"enabled",true))));
        String html = Files.readString(recorder.htmlOutput());
        assertTrue(html.contains("&lt;img")); assertFalse(html.contains("<img"));
        assertEquals(3, new ObjectMapper().readTree(temp.resolve("session.json").toFile()).path("objects").size());
        try (Playwright pw = Playwright.create(); Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1440,1100));
            page.navigate(recorder.htmlOutput().toUri().toString());
            assertEquals("TestTR • Obje keşif raporu", page.title());
            assertEquals(List.of("3","1","1","1"), page.locator(".stat strong").allTextContents());
            assertEquals(3, page.locator("table").count());
            assertEquals(0, page.locator("img,script").count());
            assertTrue(page.locator("body").innerText().contains("Gerekli · değer kaydedilmedi"));
            assertEquals("[data-testid=\"customer-number\"]", page.locator("code").first().innerText());
            Path preview = Path.of("target/report-preview.html");
            Files.writeString(preview,html);
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/report-preview.png")).setFullPage(true));
            page.setViewportSize(390,844);
            assertTrue((Boolean)page.evaluate("document.documentElement.scrollWidth <= innerWidth"));
        }
    }
    @Test void keepsJsonAndHtmlPathsDistinct() {
        assertEquals(temp.resolve("report.html").toAbsolutePath(), new Recorder(temp.resolve("report.json")).htmlOutput());
        assertEquals(temp.resolve("report.html.html").toAbsolutePath(), new Recorder(temp.resolve("report.html")).htmlOutput());
    }
}
