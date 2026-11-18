package com.open.spring.mvc.aiconnections;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiProviderClientTest {
    private HttpClient http;
    private HttpResponse<String> response;
    private AiProviderClient client;
    @SuppressWarnings("unchecked")
    @BeforeEach void setup() throws Exception {
        http = mock(HttpClient.class); response = mock(HttpResponse.class);
        client = new AiProviderClient(new ObjectMapper(), http, "https://example.test/generate", "test-model",
            "https://example.test/models", "https://example.test/groq");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
    }
    @Test void geminiUsesHeaderNotQueryStringAndOneBoundedRequest() throws Exception {
        when(response.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"result\"}]}}]}");
        assertEquals("result", client.generate(AiProvider.gemini, "fake-secret", "prompt"));
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertFalse(request.uri().toString().contains("fake-secret"));
        assertEquals("fake-secret", request.headers().firstValue("x-goog-api-key").orElseThrow());
        assertEquals(15, request.timeout().orElseThrow().toSeconds());
    }
    @Test void groqUsesChatResponseAndBearerAuthentication() throws Exception {
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"result\"},\"finish_reason\":\"stop\"}]}");
        assertEquals("result", client.generate(AiProvider.groq, "fake-secret", "prompt"));
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any());
        assertEquals("Bearer fake-secret", captor.getValue().headers().firstValue("Authorization").orElseThrow());
    }
    @Test void groqUsesConfiguredEndpointsForValidationAndPersonalAndSystemRequests() throws Exception {
        var configured = new AiProviderClient(new ObjectMapper(), http, "https://example.test/generate", "test-model",
            "https://example.test/models", "https://example.test/custom/models",
            "https://example.test/custom/chat/completions");
        when(response.body()).thenReturn("{\"data\":[]}",
            "{\"choices\":[{\"message\":{\"content\":\"personal result\"},\"finish_reason\":\"stop\"}]}",
            "{\"choices\":[{\"message\":{\"content\":\"system result\"},\"finish_reason\":\"stop\"}]}");
        configured.validate(AiProvider.groq, "fake-personal-key");
        assertEquals("personal result", configured.generate(AiProvider.groq, "fake-personal-key", "prompt"));
        assertEquals("system result", configured.generate(AiProvider.groq, "fake-system-key", "prompt"));
        var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(3)).send(requests.capture(), any());
        assertEquals("https://example.test/custom/models", requests.getAllValues().get(0).uri().toString());
        assertEquals("GET", requests.getAllValues().get(0).method());
        for (HttpRequest request : requests.getAllValues().subList(1, 3)) {
            assertEquals("https://example.test/custom/chat/completions", request.uri().toString());
            assertEquals("POST", request.method());
        }
    }
    @ParameterizedTest @ValueSource(ints = {401, 402, 403, 404, 408, 429, 500, 502, 503, 504})
    void operationalStatusCanFallbackWithoutLeakingProviderBody(int status) {
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn("provider body with fake-secret");
        var error = assertThrows(AiProviderFailure.class, () -> client.generate(AiProvider.groq, "fake-secret", "prompt"));
        assertTrue(error.isOperational()); assertFalse(error.toString().contains("fake-secret"));
    }
    @Test void depletedPersonalGeminiCreditsFallBackToPersonalGroq() throws Exception {
        var connections = mock(AiConnectionService.class);
        var author = mock(com.open.spring.mvc.person.Person.class);
        when(author.getId()).thenReturn(42L);
        when(connections.credential(42, AiProvider.gemini)).thenReturn(java.util.Optional.of("personal-gemini"));
        when(connections.credential(42, AiProvider.groq)).thenReturn(java.util.Optional.of("personal-groq"));
        when(response.statusCode()).thenReturn(402, 200);
        when(response.body()).thenReturn(
            "{\"error\":{\"message\":\"Prepay credits depleted\"}}",
            "{\"choices\":[{\"message\":{\"content\":\"result\"},\"finish_reason\":\"stop\"}]}");
        var router = new AiGradingRouter(connections, client, "system-gemini", "system-groq");

        assertEquals("result", router.generate("prompt", author));

        var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(requests.capture(), any());
        assertEquals("https://example.test/generate", requests.getAllValues().get(0).uri().toString());
        assertEquals("https://example.test/groq/chat/completions", requests.getAllValues().get(1).uri().toString());
        assertEquals("Bearer personal-groq", requests.getAllValues().get(1).headers().firstValue("Authorization").orElseThrow());
    }
    @Test void googleInvalidKeyHttp400IsOperationalButBadRequestIsNot() {
        when(response.statusCode()).thenReturn(400);
        when(response.body()).thenReturn("{\"error\":{\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}");
        assertTrue(assertThrows(AiProviderFailure.class, () -> client.generate(AiProvider.gemini, "key", "prompt")).isOperational());
        when(response.body()).thenReturn("{\"error\":{\"message\":\"bad request\"}}");
        assertFalse(assertThrows(AiProviderFailure.class, () -> client.generate(AiProvider.gemini, "key", "prompt")).isOperational());
    }
    @Test void timeoutFallsBackAndInterruptStopsWithoutLeakingCause() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new HttpTimeoutException("fake-secret"));
        assertTrue(assertThrows(AiProviderFailure.class, () -> client.generate(AiProvider.gemini, "key", "prompt")).isOperational());
        doThrow(new InterruptedException("fake-secret")).when(http)
            .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        try {
            assertFalse(assertThrows(AiProviderFailure.class, () -> client.generate(AiProvider.gemini, "key", "prompt")).isOperational());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @Test void safetyRefusalsAndMalformedBodiesCannotFallback() {
        for (String body : new String[] {"{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}", "broken", "null", "{}"}) {
            when(response.body()).thenReturn(body);
            assertFalse(assertThrows(AiProviderFailure.class, () -> client.generate(AiProvider.gemini, "key", "prompt")).isOperational());
        }
        when(response.body()).thenReturn("{\"choices\":[{\"finish_reason\":\"content_filter\"}]}");
        assertFalse(assertThrows(AiProviderFailure.class, () -> client.generate(AiProvider.groq, "key", "prompt")).isOperational());
        when(response.statusCode()).thenReturn(403);
        when(response.body()).thenReturn("{\"error\":{\"code\":\"content_policy_violation\"}}");
        assertFalse(assertThrows(AiProviderFailure.class, () -> client.generate(AiProvider.groq, "key", "prompt")).isOperational());
    }
    @Test void keyValidationOnlyGetsMetadataAndDoesNotGenerateStudentContent() throws Exception {
        when(response.body()).thenReturn("{\"models\":[]}");
        client.validate(AiProvider.gemini, "key");
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any());
        assertEquals("GET", captor.getValue().method()); assertTrue(captor.getValue().bodyPublisher().isEmpty());
        when(response.statusCode()).thenReturn(401);
        assertEquals(422, assertThrows(AiConnectionException.class, () -> client.validate(AiProvider.gemini, "key")).getStatus());
    }
    @Test void blankGroqModelDisablesGroqRatherThanBreakingGemini() {
        var unconfigured = new AiProviderClient(new ObjectMapper(), http, "https://example.test/generate", "",
            "https://example.test/models", "https://example.test/groq");
        assertFalse(unconfigured.isConfigured(AiProvider.groq)); assertTrue(unconfigured.isConfigured(AiProvider.gemini));
    }
    @Test void groqBudgetsGrowThenCompressWithoutChangingTheGradingPrompt() throws Exception {
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"result\"},\"finish_reason\":\"stop\"}]}");
        client.generate(AiProvider.groq, "key", "unchanged prompt", 2);
        client.generate(AiProvider.groq, "key", "unchanged prompt", 7);
        var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(requests.capture(), any());
        var normal = requestJson(requests.getAllValues().get(0));
        var compact = requestJson(requests.getAllValues().get(1));
        assertEquals(512, normal.path("max_completion_tokens").asInt());
        assertEquals(480, compact.path("max_completion_tokens").asInt());
        assertEquals(1, normal.path("messages").size());
        assertEquals("unchanged prompt", compact.path("messages").path(1).path("content").asText());
        assertTrue(compact.path("messages").path(0).path("content").asText().contains("Preserve every required field"));
        assertFalse(compact.has("reasoning_effort"));
    }
    @Test void overflowNeverCallsGroqAndGeminiRequestRemainsUnchanged() throws Exception {
        assertTrue(assertThrows(AiProviderFailure.class,
            () -> client.generate(AiProvider.groq, "key", "prompt", 18)).isOperational());
        verifyNoInteractions(http);
        when(response.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"result\"}]}}]}");
        client.generate(AiProvider.gemini, "key", "prompt", 18);
        var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(requests.capture(), any());
        var body = requestJson(requests.getValue());
        assertFalse(body.has("max_completion_tokens"));
        assertFalse(body.has("messages"));
        assertEquals("prompt", body.path("contents").path(0).path("parts").path(0).path("text").asText());
    }
    @Test void explicitTruncationFallsBackEvenWithParseableJsonButSafetyStillStops() {
        when(response.body()).thenReturn("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}");
        assertTrue(assertThrows(AiProviderFailure.class, () -> client.generate(AiProvider.groq, "key", "prompt")).isOperational());
        when(response.body()).thenReturn("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"refusal\":\"Declined\",\"content\":\"{}\"}}]}");
        assertFalse(assertThrows(AiProviderFailure.class, () -> client.generate(AiProvider.groq, "key", "prompt")).isOperational());
    }
    private com.fasterxml.jackson.databind.JsonNode requestJson(HttpRequest request) throws Exception {
        var subscriber = HttpResponse.BodySubscribers.ofString(java.nio.charset.StandardCharsets.UTF_8);
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscriber.onSubscribe(subscription); }
            public void onNext(java.nio.ByteBuffer buffer) { subscriber.onNext(java.util.List.of(buffer)); }
            public void onError(Throwable error) { subscriber.onError(error); }
            public void onComplete() { subscriber.onComplete(); }
        });
        return new ObjectMapper().readTree(subscriber.getBody().toCompletableFuture().get());
    }
}
