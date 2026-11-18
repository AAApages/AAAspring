package com.open.spring.mvc.aiconnections;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Calls the self-hosted models (Qwen, Gemma) through the OpenAI-compatible chat API that Ollama,
 * vLLM and llama.cpp all serve. Used only by night grading, so its timeout is long. Settings come
 * from {@link AiHouseSettingsRepository} on every call. When the server can't be reached, the house
 * model is treated as down for ten minutes so a dead GPU box costs one connect timeout, not one
 * per submission. Failures carry application messages only, never a provider body or key.
 */
@Service
public class AiHouseModelClient {
    private static final Logger logger = LoggerFactory.getLogger(AiHouseModelClient.class);
    static final Duration BREAKER = Duration.ofMinutes(10);
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");

    /** A model's answer and which model gave it. */
    public record Reply(String model, String text) {
    }

    /** The models a server lists, for the settings dropdowns. */
    public record ModelList(List<String> models, long elapsedMs) {
    }

    private final AiHouseSettingsRepository settings;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private Clock clock = Clock.systemUTC();
    private volatile Instant downUntil = Instant.MIN;

    @Autowired
    public AiHouseModelClient(AiHouseSettingsRepository settings, ObjectMapper mapper) {
        // Redirects are not followed (the default), so an admin-entered URL can't bounce elsewhere.
        this(settings, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    AiHouseModelClient(AiHouseSettingsRepository settings, ObjectMapper mapper, HttpClient http) {
        this.settings = settings; this.mapper = mapper; this.http = http;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** A server URL and a primary model are set, and the preset isn't None. */
    public boolean isConfigured() {
        return settings.current().modelConfigured();
    }

    /** Configured, and not marked down after a failed connection. */
    public boolean isAvailable() {
        return isConfigured() && !clock.instant().isBefore(downUntil);
    }

    /** Tries the server again right away (after an admin saves settings or tests the model). */
    public void resetBreaker() {
        downUntil = Instant.MIN;
    }

    /** When the house model will be tried again after a failed connection, or null. */
    public Instant downUntil() {
        return clock.instant().isBefore(downUntil) ? downUntil : null;
    }

    /** Asks for a JSON answer (grading). See {@link #generate(String, boolean)}. */
    public Reply generate(String prompt) {
        return generate(prompt, true);
    }

    /**
     * Asks the primary model, then the backup. {@code json} requests JSON output (grading); plain
     * text (rubrics) must not, or the server forces JSON. Throws {@link AiProviderFailure} when
     * neither model answers.
     */
    public Reply generate(String prompt, boolean json) {
        AiHouseSettingsRepository.HouseSettings current = settings.current();
        if (!current.modelConfigured()) {
            throw new AiProviderFailure(true, "No house model is configured.");
        }
        if (clock.instant().isBefore(downUntil)) {
            throw new AiProviderFailure(true, "The house model server is unreachable; it will be retried later.");
        }
        String key = settings.apiKey();
        AiProviderFailure last = null;
        for (String model : current.models()) {
            long started = System.nanoTime();
            try {
                String text = chat(current, key, model, prompt, json);
                logger.info("House model answered model={} elapsedMs={}", model, (System.nanoTime() - started) / 1_000_000);
                return new Reply(model, text);
            } catch (Unreachable e) {
                downUntil = clock.instant().plus(BREAKER);
                logger.warn("House model server unreachable; skipping it until {}", downUntil);
                throw e;
            } catch (AiProviderFailure e) {
                logger.warn("House model failed model={} httpStatus={} elapsedMs={}", model, e.getHttpStatus(),
                        (System.nanoTime() - started) / 1_000_000);
                last = e;
            }
        }
        throw last;
    }

    /** Lists the models a server offers ({@code GET {url}/models}). For the admin's Test connection. */
    public ModelList listModels(String baseUrl, String apiKey) {
        if (!AiHouseSettingsRepository.isHttpUrl(baseUrl)) {
            throw new AiProviderFailure(false, "The server URL must start with http:// or https://.");
        }
        String key = apiKey == null || apiKey.isBlank() ? settings.apiKey() : apiKey.trim();
        long started = System.nanoTime();
        JsonNode body = send(trimSlash(baseUrl) + "/models", key, null, LIST_TIMEOUT);
        List<String> models = new ArrayList<>();
        body.path("data").forEach(model -> addModel(models, model.path("id").asText("")));
        body.path("models").forEach(model -> addModel(models, model.path("name").asText(model.path("model").asText(""))));
        return new ModelList(models, (System.nanoTime() - started) / 1_000_000);
    }

    private static void addModel(List<String> models, String id) {
        if (!id.isBlank() && !models.contains(id)) {
            models.add(id);
        }
    }

    String chat(AiHouseSettingsRepository.HouseSettings current, String key, String model, String prompt,
            boolean jsonMode) {
        // Only a user message: some Gemma chat templates reject a system role.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("temperature", 0.2);
        body.put("stream", false);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        JsonNode response;
        try {
            response = send(current.baseUrl() + "/chat/completions", key, body,
                    Duration.ofSeconds(current.timeoutSeconds()));
        } catch (AiProviderFailure e) {
            if (jsonMode && e.getHttpStatus() == 400) {
                // Some servers or models don't support response_format; the prompt already asks for JSON.
                return chat(current, key, model, prompt, false);
            }
            throw e;
        }
        JsonNode choice = response.path("choices").path(0);
        if ("content_filter".equals(choice.path("finish_reason").asText())) {
            throw new AiProviderFailure(false, "The house model declined to answer.");
        }
        // reasoning_content (vLLM, Ollama) is ignored; only the answer is used.
        String text = stripThinking(choice.path("message").path("content").asText(""));
        if (text.isBlank()) {
            throw new AiProviderFailure(true, "The house model returned an empty answer.");
        }
        return text;
    }

    /** Removes Qwen-style thinking, which can contain braces that would confuse the JSON parser. */
    static String stripThinking(String text) {
        String result = THINK_BLOCK.matcher(text == null ? "" : text).replaceAll("");
        int unclosed = result.toLowerCase().lastIndexOf("</think>");
        if (unclosed >= 0) {
            result = result.substring(unclosed + "</think>".length());
        }
        return result.trim();
    }

    private JsonNode send(String url, String key, Map<String, Object> body, Duration timeout) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("Accept", "application/json");
            if (key != null && !key.isBlank()) {
                request.header("Authorization", "Bearer " + key);
            }
            if (body == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                boolean operational = status == 401 || status == 403 || status == 404 || status == 408
                        || status == 429 || status >= 500;
                throw new AiProviderFailure(operational, "House model request failed (HTTP " + status + ").", status);
            }
            JsonNode result = mapper.readTree(response.body());
            if (result == null || !result.isObject()) {
                throw new AiProviderFailure(true, "The house model returned an invalid response.");
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderFailure(false, "House model request was interrupted.");
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new Unreachable();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AiProviderFailure(true, "The house model returned an invalid response.");
        } catch (IOException e) {
            if (e.getCause() instanceof ConnectException) {
                throw new Unreachable();
            }
            throw new AiProviderFailure(true, "The house model timed out or the connection dropped.");
        } catch (IllegalArgumentException e) {
            throw new AiProviderFailure(false, "The house model server URL is invalid.");
        }
    }

    private static String trimSlash(String url) {
        String value = url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** The server could not be reached at all (as opposed to a slow or failed answer). */
    static final class Unreachable extends AiProviderFailure {
        Unreachable() {
            super(true, "The house model server could not be reached.");
        }
    }
}
