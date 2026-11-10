package dev.testtr;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class ExistingUnitTest {
    @Test void existingJavaTestsRemainDiscoverable() {
        assertEquals("Türkçe", new String("Türkçe".toCharArray()));
    }
}
