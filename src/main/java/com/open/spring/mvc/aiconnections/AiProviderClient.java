package com.open.spring.mvc.aiconnections;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiProviderClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String geminiUrl;
    private final String groqModel;
    private final String geminiModelsUrl;
    private final String groqModelsUrl;
    private final String groqChatUrl;

    @Autowired
    public AiProviderClient(ObjectMapper mapper,
            @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent}") String geminiUrl,
            @Value("${groq.grading-model:${GROQ_GRADING_MODEL:llama-3.1-8b-instant}}") String groqModel,
            @Value("${groq.api.url:${GROQ_SERVER:https://api.groq.com/openai/v1/chat/completions}}") String groqChatUrl) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), geminiUrl,
            groqModel, "https://generativelanguage.googleapis.com/v1beta/models",
            URI.create(groqChatUrl).resolve("../models").toString(), groqChatUrl);
    }
    AiProviderClient(ObjectMapper mapper, HttpClient http, String geminiUrl, String groqModel,
            String geminiModelsUrl, String groqBaseUrl) {
        this(mapper, http, geminiUrl, groqModel, geminiModelsUrl,
            groqBaseUrl + "/models", groqBaseUrl + "/chat/completions");
    }
    AiProviderClient(ObjectMapper mapper, HttpClient http, String geminiUrl, String groqModel,
            String geminiModelsUrl, String groqModelsUrl, String groqChatUrl) {
        this.mapper = mapper; this.http = http; this.geminiUrl = geminiUrl;
        this.groqModel = groqModel; this.geminiModelsUrl = geminiModelsUrl;
        this.groqModelsUrl = groqModelsUrl; this.groqChatUrl = groqChatUrl;
    }
    public boolean isConfigured(AiProvider provider) {
        return provider == AiProvider.gemini ? geminiUrl != null && !geminiUrl.isBlank()
            : groqModel != null && !groqModel.isBlank();
    }
    public void validate(AiProvider provider, String key) {
        if (!isConfigured(provider)) throw new AiConnectionException(503, "AI_MODEL_UNCONFIGURED",
            "This provider's grading model is not configured. Contact support.");
        try {
            String url = provider == AiProvider.gemini ? geminiModelsUrl : groqModelsUrl;
            JsonNode body = send(provider, key, url, null);
            if (!body.path(provider == AiProvider.gemini ? "models" : "data").isArray()) {
                throw new AiProviderFailure(false, "Provider returned an invalid key-validation response.");
            }
        } catch (AiProviderFailure e) {
            throw new AiConnectionException(422, "AI_KEY_VALIDATION_FAILED",
                "Unable to verify this key. Check the key, permissions, and provider availability; the previous key was not changed.");
        }
    }
    public String generate(AiProvider provider, String key, String prompt) {
        return generate(provider, key, prompt, 0);
    }
    public String generate(AiProvider provider, String key, String prompt, int hackCount) {
        if (!isConfigured(provider)) throw new AiProviderFailure(true, "AI provider model is not configured.");
        GroqOutputBudget budget = GroqOutputBudget.forHackCount(hackCount);
        if (provider == AiProvider.groq && !budget.fits())
            throw new AiProviderFailure(true, "Required grading output exceeds the Groq output budget.");
        List<Map<String, String>> messages = budget.compact()
            ? List.of(Map.of("role", "system", "content",
                "Use compact JSON without indentation. Keep feedback to one brief sentence. Preserve every required field and per-hack score; do not change rubric criteria or omit scores to save space."),
                Map.of("role", "user", "content", prompt))
            : List.of(Map.of("role", "user", "content", prompt));
        Map<String, Object> body = provider == AiProvider.gemini
            ? Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.2))
            : Map.of("model", groqModel, "messages", messages,
                "response_format", Map.of("type", "json_object"), "temperature", 0.2,
                "max_completion_tokens", budget.maxCompletionTokens());
        JsonNode response = send(provider, key,
            provider == AiProvider.gemini ? geminiUrl : groqChatUrl, body);
        if (provider == AiProvider.gemini) {
            JsonNode candidate = response.path("candidates").path(0);
            String reason = candidate.path("finishReason").asText();
            if (response.path("promptFeedback").has("blockReason")
                    || List.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII").contains(reason)) {
                throw new AiProviderFailure(false, "AI grading was declined by the provider's safety policy.");
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode part : candidate.path("content").path("parts")) {
                if (!part.path("thought").asBoolean(false)) text.append(part.path("text").asText(""));
            }
            if (text.toString().isBlank()) throw invalidOutput();
            return text.toString();
        }
        JsonNode choice = response.path("choices").path(0);
        if ("content_filter".equals(choice.path("finish_reason").asText())
                || !choice.path("message").path("refusal").asText("").isBlank()) {
            throw new AiProviderFailure(false, "AI grading was declined by the provider's safety policy.");
        }
        if ("length".equals(choice.path("finish_reason").asText()))
            throw new AiProviderFailure(true, "Groq grading output reached its token limit; incomplete result discarded.");
        String text = choice.path("message").path("content").asText("");
        if (text.isBlank()) throw invalidOutput();
        return text;
    }
    private JsonNode send(AiProvider provider, String key, String url, Map<String, Object> body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header(provider == AiProvider.gemini ? "x-goog-api-key" : "Authorization",
                    provider == AiProvider.gemini ? key : "Bearer " + key);
            if (body == null) request.GET();
            else request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                boolean operational = status == 401 || status == 402 || status == 403 || status == 404 || status == 408
                    || status == 429 || status >= 500;
                try {
                    JsonNode errorBody = mapper.readTree(response.body());
                    JsonNode error = errorBody == null ? mapper.createObjectNode() : errorBody.path("error");
                    if (List.of("content_policy_violation", "content_filter", "safety")
                            .contains(error.path("code").asText().toLowerCase(java.util.Locale.ROOT))) {
                        throw new AiProviderFailure(false, "AI grading was declined by the provider's safety policy.");
                    }
                    // Google can report a revoked/invalid API key as HTTP 400 rather than 401.
                    if (status == 400 && provider == AiProvider.gemini) {
                        for (JsonNode detail : error.path("details")) {
                            if ("API_KEY_INVALID".equals(detail.path("reason").asText())) operational = true;
                        }
                    }
                } catch (IOException ignored) { /* Do not copy provider errors into our exceptions. */ }
                throw new AiProviderFailure(operational, "AI provider request failed (HTTP " + status + ").", status);
            }
            try {
                JsonNode result = mapper.readTree(response.body());
                if (result == null || !result.isObject()) throw invalidOutput();
                return result;
            }
            catch (IOException e) { throw invalidOutput(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderFailure(false, "AI grading was interrupted.");
        } catch (IOException e) {
            throw new AiProviderFailure(true, "AI provider timed out or could not be reached.");
        } catch (IllegalArgumentException e) {
            throw new AiProviderFailure(true, "AI provider configuration or credential is invalid.");
        }
    }
    private AiProviderFailure invalidOutput() {
        return new AiProviderFailure(false, "The AI provider returned an invalid grading response.");
    }
}
