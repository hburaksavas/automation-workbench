package dev.testtr.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.testtr.DraftGenerator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class ResponsesDraftModel implements DraftGenerator.Model {
    private final ObjectMapper json = new ObjectMapper();
    private final String model;
    private final String apiKey;
    private final int maxTokens;
    private final URI endpoint;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    ResponsesDraftModel(String model, String apiKey, int maxTokens) {
        this(model, apiKey, maxTokens, URI.create("https://api.openai.com/v1/responses"));
    }

    ResponsesDraftModel(String model, String apiKey, int maxTokens, URI endpoint) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("testtr.aiModel seçilmelidir.");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("OPENAI_API_KEY ortam değişkeni gereklidir.");
        if (maxTokens < 256 || maxTokens > 16000) throw new IllegalArgumentException("testtr.aiMaxOutputTokens 256 ile 16000 arasında olmalıdır.");
        this.model = model;
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
        this.endpoint = endpoint;
    }

    @Override public JsonNode respond(String prompt) throws Exception {
        var body = json.createObjectNode();
        body.put("model", model);
        body.put("store", false);
        body.put("max_output_tokens", maxTokens);
        body.put("input", prompt);
        body.set("text", json.readTree(SCHEMA));
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("AI isteği kesildi.");
        } catch (IOException exception) {
            throw new IOException("AI servisine erişilemedi.");
        }
        if (response.statusCode() != 200) throw new IOException("AI isteği başarısız. HTTP " + response.statusCode());
        JsonNode root;
        try { root = json.readTree(response.body()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IOException("AI servisi geçersiz JSON döndürdü."); }
        if (root == null || !root.path("status").asText().equals("completed"))
            throw new IOException("AI yanıtı tamamlanmadı; taslak kaydedilmedi.");
        StringBuilder text = new StringBuilder();
        for (JsonNode item : root.path("output")) {
            if (!item.path("type").asText().equals("message")) continue;
            for (JsonNode content : item.path("content")) {
                if (content.path("type").asText().equals("refusal")) throw new IOException("Model taslak üretimini reddetti.");
                if (content.path("type").asText().equals("output_text")) text.append(content.path("text").asText());
            }
        }
        if (text.isEmpty()) throw new IOException("AI yanıtında taslak bulunamadı.");
        try { return json.readTree(text.toString()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IOException("AI taslak yanıtı geçersiz JSON içeriyor."); }
    }

    private static final String SCHEMA = """
            {"format":{"type":"json_schema","name":"testtr_drafts","strict":true,"schema":{
              "type":"object","additionalProperties":false,"required":["drafts","review"],"properties":{
                "drafts":{"type":"array","items":{"type":"object","additionalProperties":false,
                  "required":["requirement","version","source","rationale"],"properties":{
                    "requirement":{"type":"string"},"version":{"type":"string"},
                    "source":{"type":"string"},"rationale":{"type":"string"}}}},
                "review":{"type":"array","items":{"type":"object","additionalProperties":false,
                  "required":["requirement","reason"],"properties":{
                    "requirement":{"type":"string"},"reason":{"type":"string"}}}}
              }}}}
            """;
}
