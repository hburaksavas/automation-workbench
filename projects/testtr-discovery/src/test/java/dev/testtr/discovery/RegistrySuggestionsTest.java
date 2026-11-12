package dev.testtr.discovery;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrySuggestionsTest {
    @TempDir Path temp;
    final RegistrySuggestions service = new RegistrySuggestions();
    Path registry() throws Exception {
        Path file = temp.resolve("registry.json");
        Files.writeString(file, """
            {"pages":{"Müşteriler":{"url":"https://app.test/customers","objects":{
              "Kaydet":{"type":"button","css":"#old"},"Mevcut":{"type":"input","css":"#present"}
            }}}}
            """); return file;
    }
    ObjectNode object(String name, String css) {
        ObjectNode o = JsonFiles.JSON.createObjectNode();
        return o.put("name",name).put("css",css).put("type","button").put("quality","attribute").put("matches",1)
            .put("url","https://app.test/customers").put("frame","frame-1").put("mainFrame",true).put("interacted",true);
    }
    Path report(String filename, ObjectNode... objects) throws Exception {
        ObjectNode report = JsonFiles.JSON.createObjectNode(); ArrayNode array = report.putArray("objects");
        for (ObjectNode o : objects) array.add(o);
        Path path = temp.resolve(filename); JsonFiles.write(path, report); return path;
    }
    JsonNode byName(JsonNode proposal, String name) {
        for (JsonNode c : proposal.path("candidates")) if (name.equals(c.path("name").asText())) return c;
        throw new AssertionError(name);
    }
    @Test void classifiesDiffAndDoesNotOverwriteRegistry() throws Exception {
        Path registry = registry(); String original = Files.readString(registry);
        ObjectNode unchanged = object("Mevcut", "#present").put("type","input");
        Path report = report("discovery.json", object("Kaydet","#save"), object("Yeni","#new"), unchanged);
        JsonNode proposal = service.suggest(List.of(report), registry, JsonFiles.JSON.createObjectNode(), null);
        assertEquals("locator-change", byName(proposal,"Kaydet").path("status").asText());
        assertEquals("new", byName(proposal,"Yeni").path("status").asText());
        assertEquals("unchanged", byName(proposal,"Mevcut").path("status").asText());
        Path output = temp.resolve("proposal.json"); service.save(output, proposal);
        assertTrue(Files.readString(temp.resolve("proposal.html")).contains("#old"));
        String id = byName(proposal,"Kaydet").path("id").asText();
        assertThrows(IllegalArgumentException.class, () -> service.apply(output, registry, Set.of(id), temp.resolve("new.json")));
        assertEquals(original, Files.readString(registry));
    }
    @Test void rejectsAmbiguityFramesStructuralAndUnsupportedControls() throws Exception {
        Path report = report("ambiguous.json", object("Aynı","#a"), object("Aynı","#b"),
            object("Frame","#inside").put("mainFrame",false), object("Radio","#radio").put("type","radio"),
            object("Yapısal","button:nth-child(1)").put("quality","structural"));
        JsonNode proposal = service.suggest(List.of(report), registry(), JsonFiles.JSON.createObjectNode(), null);
        for (JsonNode c : proposal.path("candidates")) assertEquals("review", c.path("status").asText(), c.toString());
    }
    @Test void duplicateReportIsNotIndependentEvidenceAndNameCollisionIsNotHidden() throws Exception {
        Path report = report("one.json", object("Kaydet","#save"));
        JsonNode proposal = service.suggest(List.of(report,report), registry(), JsonFiles.JSON.createObjectNode(), null);
        assertEquals(1, byName(proposal,"Kaydet").path("observedReportCount").asInt());
        Path second = report("two.json", object("Kaydet","#changed"));
        proposal = service.suggest(List.of(report,second), registry(), JsonFiles.JSON.createObjectNode(), null);
        assertEquals("review", byName(proposal,"Kaydet").path("status").asText());
    }
    @Test void requiresPageMappingAndUsesExplicitBaseForRelativeUrls() throws Exception {
        Path registry = registry(); ObjectNode document = (ObjectNode)JsonFiles.read(registry);
        ((ObjectNode)document.path("pages").path("Müşteriler")).put("url","customers"); JsonFiles.write(registry, document);
        Path report = report("report.json",object("Yeni","#new"));
        assertEquals("review", service.suggest(List.of(report), registry, JsonFiles.JSON.createObjectNode(), null).path("candidates").get(0).path("status").asText());
        assertEquals("new", service.suggest(List.of(report), registry, JsonFiles.JSON.createObjectNode(), "https://app.test/").path("candidates").get(0).path("status").asText());
        assertFalse(RegistrySuggestions.sameUrl("https://other.test/customers","customers","https://app.test/"));
    }
    @Test void layeredRegistryAndStaleProposalProtection() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("registry"));
        Files.copy(registry(), directory.resolve("base.json"));
        Files.writeString(directory.resolve("z.override.json"), "{\"pages\":{\"Müşteriler\":{\"objects\":{\"Kaydet\":{\"css\":\"#save\"}}}}}");
        Path report = report("report.json",object("Kaydet","#save"));
        assertEquals("unchanged", service.suggest(List.of(report),directory,JsonFiles.JSON.createObjectNode(),null).path("candidates").get(0).path("status").asText());
        ObjectNode proposal = service.suggest(List.of(report("new.json",object("Yeni","#new"))), directory, JsonFiles.JSON.createObjectNode(), null);
        ObjectNode c = (ObjectNode)proposal.path("candidates").get(0); c.put("validation","passed");
        Path file = temp.resolve("proposal.json"); JsonFiles.write(file,proposal);
        String id = c.path("id").asText();
        assertThrows(IllegalArgumentException.class, () -> service.apply(file,directory,Set.of(id),directory.resolve("new.json")));
        Path snapshot = temp.resolve("snapshot.json"); service.apply(file,directory,Set.of(id),snapshot);
        assertTrue(JsonFiles.read(snapshot).path("pages").path("Müşteriler").path("objects").has("Yeni"));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> service.apply(file,directory,Set.of(id),snapshot));
        Files.writeString(directory.resolve("z.override.json"), "{\"pages\":{\"Müşteriler\":{\"url\":\"https://changed.test\"}}}");
        assertThrows(IllegalArgumentException.class, () -> service.apply(file,directory,Set.of(id),temp.resolve("stale.json")));
    }
    @Test void cliProtectsRegistryAndReportInputsIncludingHtmlSidecar() throws Exception {
        Path registry = registry(), report = report("discovery.json",object("Yeni","#new"));
        String original = Files.readString(registry);
        assertThrows(IllegalArgumentException.class, () -> Main.main(new String[]{"suggest-registry","--discovery",report.toString(),"--registry",registry.toString(),"--output",registry.toString()}));
        assertEquals(original,Files.readString(registry));
        Path html = temp.resolve("proposal.html"); Files.writeString(html,"input");
        assertThrows(IllegalArgumentException.class, () -> OutputPaths.protect(temp.resolve("proposal.json"),List.of(html)));
    }
    @Test void htmlEscapesUntrustedDomText() throws Exception {
        JsonNode proposal = service.suggest(List.of(report("report.json",object("<script>alert(1)</script>","#x"))),registry(),JsonFiles.JSON.createObjectNode(),null);
        service.save(temp.resolve("x.json"),proposal);
        String html = Files.readString(temp.resolve("x.html"));
        assertFalse(html.contains("<script>")); assertTrue(html.contains("&lt;script&gt;"));
    }
}
