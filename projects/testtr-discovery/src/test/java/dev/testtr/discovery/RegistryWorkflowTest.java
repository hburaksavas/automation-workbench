package dev.testtr.discovery;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistryWorkflowTest {
    @TempDir Path temp;
    @Test void cliDiscoversValidatesAndAppliesOnlyAcceptedCandidate() throws Exception {
        Path page = temp.resolve("page.html");
        Files.writeString(page,"<meta charset=utf-8><button id='save'>Kaydet</button><label for='no'>Müşteri No</label><input id='no'>");
        Path registry = temp.resolve("registry.json");
        var document = JsonFiles.JSON.createObjectNode(); var customer = document.putObject("pages").putObject("Müşteri");
        customer.put("url", page.toUri().toString()); customer.putObject("objects").putObject("Kaydet").put("type","button").put("css","#old");
        JsonFiles.write(registry, document); String original = Files.readString(registry);
        Path discovery = temp.resolve("discovery.json"), proposal = temp.resolve("proposal.json"), validated = temp.resolve("validated.json"), snapshot = temp.resolve("snapshot.json");
        Main.main(new String[]{"discover","--url",page.toUri().toString(),"--headless","true","--duration","1","--output",discovery.toString()});
        Main.main(new String[]{"suggest-registry","--discovery",discovery.toString(),"--registry",registry.toString(),"--output",proposal.toString()});
        Main.main(new String[]{"validate-registry","--proposal",proposal.toString(),"--output",validated.toString()});
        JsonNode report = JsonFiles.read(validated);
        String id = "";
        for (JsonNode c : report.path("candidates")) {
            assertEquals("passed", c.path("validation").asText(), c.toPrettyString());
            assertEquals(2, c.path("validationEvidence").size());
            if ("Kaydet".equals(c.path("name").asText())) id = c.path("id").asText();
        }
        assertFalse(id.isBlank());
        Main.main(new String[]{"apply-registry","--proposal",validated.toString(),"--registry",registry.toString(),"--accept",id,"--output",snapshot.toString()});
        JsonNode objects = JsonFiles.read(snapshot).path("pages").path("Müşteri").path("objects");
        assertTrue(objects.path("Kaydet").path("css").asText().contains("save")); assertFalse(objects.has("Müşteri No"));
        assertEquals(original, Files.readString(registry));
        // A later UI change must invalidate prior observations when revalidated.
        Files.writeString(page,"<button id='save'>Başka işlem</button><input id='no'>");
        Main.main(new String[]{"validate-registry","--proposal",proposal.toString(),"--output",temp.resolve("failed.json").toString()});
        for (JsonNode c : JsonFiles.read(temp.resolve("failed.json")).path("candidates")) assertEquals("failed",c.path("validation").asText());
    }
}
