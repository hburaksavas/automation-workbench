package dev.testtr.discovery;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Receives DOM observations; never receives input values. */
public final class Recorder {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, JsonNode> objects = new LinkedHashMap<>();
    private final List<JsonNode> events = new ArrayList<>();
    private final Path output;
    public Recorder(Path output) { this.output = output.toAbsolutePath(); }

    public Path htmlOutput() {
        String name = output.getFileName().toString();
        String base = name.toLowerCase(Locale.ROOT).endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        return output.resolveSibling(base + ".html").equals(output) ? output.resolveSibling(name + ".html") : output.resolveSibling(base + ".html");
    }

    public void accept(String pageId, String frameId, String url, Object payload) {
        JsonNode data = mapper.valueToTree(payload);
        String location;
        try { URI uri = URI.create(url); location = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString(); }
        catch (Exception e) { location = "unknown"; }
        if ("scan".equals(data.path("kind").asText())) {
            for (JsonNode object : data.path("objects")) remember(pageId, frameId, location, object, false);
        } else if ("interaction".equals(data.path("kind").asText())) {
            JsonNode object = data.path("object");
            remember(pageId, frameId, location, object, true);
            ObjectNode event = mapper.createObjectNode();
            event.put("sequence", events.size() + 1).put("time", Instant.now().toString()).put("page", pageId).put("frame", frameId).put("url", location);
            event.put("action", data.path("action").asText()); event.set("object", object);
            if (!"click".equals(data.path("action").asText())) event.put("parameterRequired", true);
            events.add(event);
        }
        save();
    }
    private void remember(String page, String frame, String url, JsonNode object, boolean interacted) {
        String key = page + "\n" + frame + "\n" + url + "\n" + object.path("css").asText() + "\n" + object.path("name").asText();
        ObjectNode record = object.deepCopy();
        record.put("page", page).put("frame", frame).put("url", url);
        record.put("interacted", interacted || (objects.containsKey(key) && objects.get(key).path("interacted").asBoolean()));
        record.put("lastSeen", Instant.now().toString()); objects.put(key, record);
    }
    public void save() {
        ObjectNode report = mapper.createObjectNode();
        report.put("schemaVersion", 1).put("generatedAt", Instant.now().toString());
        report.set("objects", mapper.valueToTree(objects.values())); report.set("interactions", mapper.valueToTree(events));
        try {
            Files.createDirectories(output.getParent());
            writeAtomically(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            writeAtomically(htmlOutput(), HtmlReport.render(report));
        } catch (IOException e) { throw new IllegalStateException("Cannot save discovery report: " + output, e); }
    }

    private static void writeAtomically(Path destination, String content) throws IOException {
        Path temp = Files.createTempFile(destination.getParent(), "discovery-", ".tmp");
        try {
            Files.writeString(temp, content);
            try { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
