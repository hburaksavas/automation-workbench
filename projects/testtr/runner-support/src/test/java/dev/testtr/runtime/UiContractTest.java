package dev.testtr.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UiContractTest {
    private Map<String, Object> element(String kind) {
        var state = new HashMap<String, Object>();
        state.put("count", 1);
        state.put("kind", kind);
        state.put("visible", true);
        state.put("enabled", true);
        state.put("readonly", false);
        state.put("text", "  Türkçe\n\tMetin\u00a0 ");
        state.put("value", " 123 ");
        state.put("checked", false);
        return state;
    }

    private void fails(Runnable assertion) {
        String previous = System.getProperty("testtr.timeoutMillis");
        try {
            System.setProperty("testtr.timeoutMillis", "1");
            assertThrows(AssertionError.class, assertion::run);
        } finally {
            if (previous == null) System.clearProperty("testtr.timeoutMillis");
            else System.setProperty("testtr.timeoutMillis", previous);
        }
    }

    @Test void normalizesWhitespaceButPreservesCaseAndFieldValues() {
        var state = element("input");
        UiContract.expect(() -> state, "text", "Türkçe Metin");
        UiContract.expect(() -> state, "contains", "Metin");
        UiContract.expect(() -> state, "value", " 123 ");
        fails(() -> UiContract.expect(() -> state, "text", "türkçe metin"));
        fails(() -> UiContract.expect(() -> state, "value", "123"));
    }

    @Test void missingElementOnlySatisfiesHiddenAssertion() {
        var missing = Map.of("count", 0);
        UiContract.expect(() -> missing, "hidden", "");
        fails(() -> UiContract.expect(() -> missing, "unchecked", ""));
        fails(() -> UiContract.expect(() -> missing, "disabled", ""));
    }

    @Test void duplicateElementsFailEvenWhenHidden() {
        var duplicate = Map.of("count", 2);
        assertTrue(assertThrows(AssertionError.class, () -> UiContract.expect(() -> duplicate, "hidden", ""))
                .getMessage().contains("benzersiz"));
    }

    @Test void pollsUntilStateMatches() {
        AtomicInteger count = new AtomicInteger();
        var ready = element("button");
        UiContract.expect(() -> count.incrementAndGet() < 3 ? Map.of("count", 0) : ready, "enabled", "");
        assertEquals(3, count.get());
    }

    @Test void validatesDomKindAndEditableState() {
        var state = element("input");
        assertThrows(AssertionError.class, () -> UiContract.ready(() -> state, "click"));
        state.put("readonly", true);
        fails(() -> UiContract.ready(() -> state, "fill"));
        UiContract.ready(() -> state, "tab");
    }

    @Test void resolvesUrlsAndComparesQueryFragmentAndTrailingSlash() {
        assertEquals("https://example.org/app/index.html?q=1#tab", UiContract.resolveUrl("https://example.org/app/", "index.html?q=1#tab"));
        assertEquals("https://other.org/a", UiContract.resolveUrl("", "https://other.org/a"));
        assertTrue(UiContract.sameUrl("https://EXAMPLE.org:443/a", "https://example.org/a"));
        assertTrue(UiContract.sameUrl("file:///D:/test/index.html", "file:/D:/test/index.html"));
        assertFalse(UiContract.sameUrl("https://example.org/a/", "https://example.org/a"));
        assertFalse(UiContract.sameUrl("https://example.org/a?x=1", "https://example.org/a?x=2"));
        assertFalse(UiContract.sameUrl("https://example.org/a#one", "https://example.org/a#two"));
        assertFalse(UiContract.sameUrl("about:blank", "about:other"));
        assertThrows(IllegalArgumentException.class, () -> UiContract.resolveUrl("", "relative"));
    }

    @Test void requiresUniqueEnabledOption() {
        var state = element("select");
        state.put("options", List.of(Map.of("label", " Türk Lirası ", "index", 2, "disabled", false)));
        assertEquals(2, UiContract.selectionIndex(() -> state, "Türk Lirası"));
        state.put("options", List.of(Map.of("label", "Avro", "index", 0), Map.of("label", "Avro", "index", 1)));
        assertThrows(AssertionError.class, () -> UiContract.selectionIndex(() -> state, "Avro"));
    }
}
