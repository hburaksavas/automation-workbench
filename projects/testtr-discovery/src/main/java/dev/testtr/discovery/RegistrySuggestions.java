package dev.testtr.discovery;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Produces reviewable candidates; never treats observation as a business assertion. */
public final class RegistrySuggestions {
    private static final Set<String> TYPES = Set.of("input", "button", "text", "select", "checkbox");

    public ObjectNode suggest(List<Path> reports, Path registryPath, JsonNode mapping, String baseUrl) throws Exception {
        if (reports.isEmpty()) throw new IllegalArgumentException("At least one discovery report is required");
        ObjectNode registry = RegistryStore.load(registryPath);
        if (!mapping.isObject()) throw new IllegalArgumentException("Page map must be an object: URL -> page name");
        ObjectNode proposal = JsonFiles.JSON.createObjectNode();
        proposal.put("schemaVersion", 1).put("generatedAt", Instant.now().toString()).put("registryHash", JsonFiles.hash(registry));
        ArrayNode sources = proposal.putArray("sources");
        // One evidence entry per report, not per page/tab. Duplicate report bytes do not imply stability.
        Map<String, Map<String, JsonNode>> observations = new TreeMap<>();
        for (Path path : reports) {
            JsonNode report = JsonFiles.read(path);
            if (!report.path("objects").isArray()) throw new IllegalArgumentException("Discovery report requires objects array");
            String source = JsonFiles.hash(Files.readAllBytes(path));
            if (!sources.findValuesAsText("sha256").contains(source)) sources.addObject().put("sha256", source);
            for (JsonNode object : report.path("objects")) {
                String key = object.path("url").asText() + "\n" + object.path("name").asText();
                // Preserve alternatives to expose collisions, including duplicate names across frames.
                String variant = source + "\n" + object.path("frame").asText() + "\n" + object.path("css").asText() + "\n" + object.path("type").asText();
                observations.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(variant, object);
            }
        }
        ArrayNode candidates = proposal.putArray("candidates");
        Set<String> targets = new HashSet<>();
        for (var entry : observations.entrySet()) {
            List<JsonNode> seen = new ArrayList<>(entry.getValue().values());
            seen.sort(Comparator.comparing(o -> !o.path("interacted").asBoolean()));
            JsonNode first = seen.get(0);
            ObjectNode candidate = candidates.addObject(); ArrayNode reasons = candidate.putArray("reasons");
            String url = first.path("url").asText(), name = first.path("name").asText();
            String css = first.path("css").asText(), type = first.path("type").asText();
            candidate.put("id", JsonFiles.hash(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 20));
            candidate.put("url", url).put("name", name).put("interacted", seen.stream().anyMatch(o -> o.path("interacted").asBoolean()));
            ObjectNode after = candidate.putObject("after"); after.put("type", type).put("css", css);
            candidate.set("observations", JsonFiles.JSON.valueToTree(seen));
            List<String> pages = new ArrayList<>();
            registry.path("pages").fields().forEachRemaining(p -> {
                if (sameUrl(url, p.getValue().path("url").asText(), baseUrl)) pages.add(p.getKey());
            });
            String page = mapping.path(url).asText("");
            if (page.isBlank()) {
                if (pages.size() == 1) page = pages.get(0);
                else reasons.add(pages.isEmpty() ? "Sayfa eşleşmedi; URL -> sayfa adı eşlemesi gerekli." : "URL birden fazla Registry sayfasına eşleşiyor.");
            }
            candidate.put("pageName", page);
            JsonNode existingPage = registry.path("pages").path(page);
            String pageUrl = existingPage.isMissingNode() ? url : existingPage.path("url").asText();
            candidate.put("pageUrl", pageUrl);
            if (!page.isBlank() && !existingPage.isMissingNode() && !sameUrl(url, pageUrl, baseUrl)) reasons.add("Sayfa eşlemesi mevcut Registry adresiyle çelişiyor.");
            if (name.isBlank() || css.isBlank()) reasons.add("Obje adı veya CSS eksik.");
            if (!TYPES.contains(type)) reasons.add("TestTR tarafından desteklenmeyen obje türü: " + type);
            if (seen.stream().anyMatch(o -> !o.path("mainFrame").asBoolean())) reasons.add("Iframe veya bilinmeyen frame kapsamı; doğrudan Registry'ye uygulanamaz.");
            if (seen.stream().anyMatch(o -> o.path("matches").asInt() != 1)) reasons.add("Locator gözlemde tekil değil.");
            if (seen.stream().anyMatch(o -> !"attribute".equals(o.path("quality").asText()))) reasons.add("Yapısal locator elle inceleme gerektiriyor.");
            if (seen.stream().anyMatch(o -> !css.equals(o.path("css").asText()) || !type.equals(o.path("type").asText()))) reasons.add("Aynı ad için farklı locator veya tür gözlemlendi.");
            // Multiple objects with one name in the same report remain ambiguous even with identical CSS.
            Set<String> sourceIds = new HashSet<>(); boolean collision = false;
            for (String variant : entry.getValue().keySet()) if (!sourceIds.add(variant.substring(0, variant.indexOf('\n')))) collision = true;
            if (collision) reasons.add("Aynı gözlemde bu adla birden fazla obje var.");
            candidate.put("observedReportCount", sourceIds.size());
            candidate.put("stability", sourceIds.size() > 1 ? "repeated-observation" : "single-observation");
            JsonNode before = existingPage.path("objects").path(name);
            if (!before.isMissingNode()) candidate.set("before", before.deepCopy());
            String status = "new";
            if (!before.isMissingNode()) {
                if (!type.equals(before.path("type").asText())) reasons.add("Mevcut kayıtla obje türü çelişiyor.");
                status = after.equals(before) ? "unchanged" : "locator-change";
            } else {
                var others = existingPage.path("objects").fields();
                while (others.hasNext()) {
                    var other = others.next();
                    if (css.equals(other.getValue().path("css").asText())) reasons.add("Locator başka bir Registry adına bağlı: " + other.getKey());
                }
            }
            if (!page.isBlank() && !targets.add(page + "\n" + name)) reasons.add("Birden fazla URL aynı Registry objesine aday üretiyor.");
            candidate.put("status", reasons.isEmpty() ? status : "review");
            candidate.put("changeKind", status);
            candidate.put("validation", "not-run");
        }
        // Mark both sides of cross-URL collisions.
        Map<String, List<JsonNode>> byTarget = new HashMap<>();
        for (JsonNode c : candidates) if (!c.path("pageName").asText().isBlank()) byTarget.computeIfAbsent(c.path("pageName").asText()+"\n"+c.path("name").asText(), k -> new ArrayList<>()).add(c);
        for (List<JsonNode> group : byTarget.values()) if (group.size() > 1) for (JsonNode c : group) {
            ((ObjectNode)c).put("status", "review"); ((ArrayNode)c.path("reasons")).add("Aynı Registry hedefi için birden fazla aday var.");
        }
        List<JsonNode> ordered = new ArrayList<>(); candidates.forEach(ordered::add);
        ordered.sort(Comparator.comparing((JsonNode c) -> !c.path("interacted").asBoolean()).thenComparing(c -> c.path("id").asText()));
        candidates.removeAll(); candidates.addAll(ordered);
        return proposal;
    }

    static boolean sameUrl(String observed, String configured, String baseUrl) {
        try {
            URI target = URI.create(configured);
            if (!target.isAbsolute()) {
                if (baseUrl == null || baseUrl.isBlank()) return false;
                target = URI.create(baseUrl).resolve(target);
            }
            return clean(URI.create(observed)).equals(clean(target));
        } catch (IllegalArgumentException e) { return false; }
    }
    private static URI clean(URI uri) {
        // Keep query/fragment distinctions on Registry side conservative: discovery strips both.
        return uri.normalize();
    }

    public ObjectNode apply(Path proposalPath, Path registryPath, Set<String> accepted, Path output) throws Exception {
        if (accepted.isEmpty()) throw new IllegalArgumentException("Explicit accepted candidate IDs are required");
        OutputPaths.protect(output, List.of(proposalPath, registryPath));
        ObjectNode registry = RegistryStore.load(registryPath);
        JsonNode proposal = JsonFiles.read(proposalPath);
        if (!JsonFiles.hash(registry).equals(proposal.path("registryHash").asText())) throw new IllegalArgumentException("Registry changed; regenerate the proposal");
        Path destination = output.toAbsolutePath().normalize(), source = registryPath.toRealPath();
        if (destination.equals(source) || (Files.exists(output) && Files.isSameFile(output, source)) || (Files.isDirectory(source) && destination.startsWith(source)))
            throw new IllegalArgumentException("Output must be a separate Registry snapshot outside the source Registry");
        Set<String> remaining = new HashSet<>(accepted);
        for (JsonNode c : proposal.path("candidates")) {
            if (!remaining.remove(c.path("id").asText())) continue;
            if (!Set.of("new", "locator-change").contains(c.path("status").asText()) || !"passed".equals(c.path("validation").asText()))
                throw new IllegalArgumentException("Candidate requires review or browser validation: " + c.path("id").asText());
            String pageName = JsonFiles.required(c, "pageName"), name = JsonFiles.required(c, "name");
            JsonFiles.required(c, "pageUrl"); JsonFiles.required(c.path("after"), "css");
            if (!TYPES.contains(c.path("after").path("type").asText())) throw new IllegalArgumentException("Unsupported type");
            ObjectNode pages = (ObjectNode)registry.path("pages");
            ObjectNode page = pages.has(pageName) ? (ObjectNode)pages.get(pageName) : pages.putObject(pageName);
            if (!page.has("url")) { page.put("url", c.path("pageUrl").asText()); page.putObject("objects"); }
            ((ObjectNode)page.path("objects")).set(name, c.path("after").deepCopy());
        }
        if (!remaining.isEmpty()) throw new IllegalArgumentException("Unknown candidate IDs: " + remaining);
        // CREATE_NEW protects prior snapshots; the original Registry is never overwritten.
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, JsonFiles.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(registry), StandardOpenOption.CREATE_NEW);
        return registry;
    }

    public void save(Path output, JsonNode proposal) throws Exception {
        JsonFiles.write(output, proposal);
        Path html = OutputPaths.html(output);
        StringBuilder report = new StringBuilder("<!doctype html><html lang=tr><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><meta http-equiv=Content-Security-Policy content=\"default-src 'none'; style-src 'unsafe-inline'\"><title>Registry önerileri</title><style>body{font:15px Segoe UI,sans-serif;margin:32px;color:#17283c}table{width:100%;border-collapse:collapse}td,th{padding:12px;border:1px solid #ccd5df;text-align:left;vertical-align:top}pre{white-space:pre-wrap;overflow-wrap:anywhere}th{background:#edf2f7}</style><h1>Registry önerileri</h1><p>Registry değiştirilmedi. Uygulama için aday kimliklerini açıkça seçin. Tekrarlanan gözlem, bağımsız tarayıcı doğrulamasının yerine geçmez.</p><table><tr><th>Aday / sayfa / obje</th><th>Durum / doğrulama</th><th>Önce</th><th>Öneri</th><th>Gerekçeler</th></tr>");
        for (JsonNode c : proposal.path("candidates")) report.append("<tr><td>").append(escape(c.path("id").asText()+" / "+c.path("pageName").asText()+" / "+c.path("name").asText()))
            .append("</td><td>").append(escape(c.path("status").asText()+" / "+c.path("validation").asText())).append("</td><td><pre>").append(escape(c.path("before").toPrettyString()))
            .append("</pre></td><td><pre>").append(escape(c.path("after").toPrettyString())).append("</pre></td><td>").append(escape(c.path("reasons").toString())).append("</td></tr>");
        JsonFiles.writeText(html, report.append("</table></html>").toString());
    }
    private static String escape(String text) { return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
}
