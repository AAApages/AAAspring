package com.open.spring.mvc.aiconnections;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.groups.Groups;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiGradingRouterTest {
    private AiConnectionService connections;
    private AiProviderClient client;
    private AiGradingRouter router;
    private Person author;
    @BeforeEach void setup() {
        connections = mock(AiConnectionService.class); client = mock(AiProviderClient.class);
        author = mock(Person.class); when(author.getId()).thenReturn(42L);
        when(client.isConfigured(any())).thenReturn(true);
        when(connections.credential(42, AiProvider.gemini)).thenReturn(Optional.of("personal-gemini"));
        when(connections.credential(42, AiProvider.groq)).thenReturn(Optional.of("personal-groq"));
        router = new AiGradingRouter(connections, client, "system-gemini", "system-groq");
    }
    @Test void personalGeminiSucceedsWithoutCallingOtherProviders() {
        when(client.generate(AiProvider.gemini, "personal-gemini", "prompt", 0)).thenReturn("result");
        assertEquals("result", router.generate("prompt", author));
        verify(client, never()).generate(eq(AiProvider.groq), any(), any(), eq(0));
        verify(connections, never()).credential(42, AiProvider.groq);
    }
    @Test void operationalFailuresFollowExactFourCredentialOrder() {
        when(client.generate(any(), any(), any(), eq(0))).thenThrow(new AiProviderFailure(true, "Unavailable"));
        doReturn("result").when(client).generate(AiProvider.groq, "system-groq", "prompt", 0);
        assertEquals("result", router.generate("prompt", author));
        var order = inOrder(client);
        order.verify(client).generate(AiProvider.gemini, "personal-gemini", "prompt", 0);
        order.verify(client).generate(AiProvider.groq, "personal-groq", "prompt", 0);
        order.verify(client).generate(AiProvider.gemini, "system-gemini", "prompt", 0);
        order.verify(client).generate(AiProvider.groq, "system-groq", "prompt", 0);
    }
    @Test void missingPersonalGeminiUsesPersonalGroqBeforeSystemGemini() {
        when(connections.credential(42, AiProvider.gemini)).thenReturn(Optional.empty());
        when(client.generate(AiProvider.groq, "personal-groq", "prompt", 0)).thenReturn("result");
        assertEquals("result", router.generate("prompt", author));
        verify(client, never()).generate(eq(AiProvider.gemini), any(), any(), eq(0));
    }
    @Test void refusalsAndInvalidOutputDoNotTriggerFallback() {
        when(client.generate(AiProvider.gemini, "personal-gemini", "prompt", 0))
            .thenThrow(new AiProviderFailure(false, "Declined"));
        assertFalse(assertThrows(AiProviderFailure.class, () -> router.generate("prompt", author)).isOperational());
        verify(client, never()).generate(eq(AiProvider.groq), any(), any(), eq(0));
    }
    @Test void groupsUseSystemKeysOnly() {
        when(client.generate(AiProvider.gemini, "system-gemini", "prompt", 0)).thenReturn("result");
        assertEquals("result", router.generate("prompt", mock(Groups.class)));
        verifyNoInteractions(connections);
    }
    @Test void identicalPersonalAndSystemKeysAreAttemptedOnlyOnce() {
        router = new AiGradingRouter(connections, client, "personal-gemini", "personal-groq");
        when(client.generate(any(), any(), any(), eq(0))).thenThrow(new AiProviderFailure(true, "Unavailable"));
        assertThrows(AiProviderFailure.class, () -> router.generate("prompt", author));
        verify(client, times(1)).generate(AiProvider.gemini, "personal-gemini", "prompt", 0);
        verify(client, times(1)).generate(AiProvider.groq, "personal-groq", "prompt", 0);
    }
    @Test void noPersonalStorageStillAllowsExistingSystemGrading() {
        when(connections.credential(anyLong(), any())).thenReturn(Optional.empty());
        when(client.generate(AiProvider.gemini, "system-gemini", "prompt", 0)).thenReturn("result");
        assertEquals("result", router.generate("prompt", author));
    }
    @Test void noKeysFailsWithSanitizedSavedSubmissionMessage() {
        when(connections.credential(anyLong(), any())).thenReturn(Optional.empty());
        router = new AiGradingRouter(connections, client, "", "");
        assertTrue(assertThrows(AiProviderFailure.class, () -> router.generate("prompt", author))
            .getMessage().contains("remains saved"));
        verify(client, never()).generate(any(), any(), any(), eq(0));
    }
    @Test void overflowingResultSkipsBothGroqCredentialsAndRetainsFallbackOrder() {
        when(client.generate(eq(AiProvider.gemini), any(), any(), eq(18)))
            .thenThrow(new AiProviderFailure(true, "Unavailable"));
        assertThrows(AiProviderFailure.class, () -> router.generate("prompt", author, 18));
        verify(client, never()).generate(eq(AiProvider.groq), any(), any(), anyInt());
        var order = inOrder(client);
        order.verify(client).generate(AiProvider.gemini, "personal-gemini", "prompt", 18);
        order.verify(client).generate(AiProvider.gemini, "system-gemini", "prompt", 18);
    }
    @Test void truncatedPersonalGroqContinuesToSystemGeminiWithoutRetry() {
        when(client.generate(any(), any(), any(), eq(7))).thenThrow(new AiProviderFailure(true, "Incomplete"));
        doReturn("result").when(client).generate(AiProvider.gemini, "system-gemini", "prompt", 7);
        assertEquals("result", router.generate("prompt", author, 7));
        var order = inOrder(client);
        order.verify(client).generate(AiProvider.gemini, "personal-gemini", "prompt", 7);
        order.verify(client).generate(AiProvider.groq, "personal-groq", "prompt", 7);
        order.verify(client).generate(AiProvider.gemini, "system-gemini", "prompt", 7);
        verify(client, times(3)).generate(any(), any(), any(), eq(7));
    }

    /** Night grading: the self-hosted model path, its client, and its settings. */
    @Nested
    class HouseModel {
        private SingleConnectionDataSource source;
        private AiHouseSettingsRepository settings;
        private HttpClient http;
        private HttpResponse<String> response;
        private AiHouseModelClient house;

        @SuppressWarnings("unchecked")
        @BeforeEach void setupHouse() throws Exception {
            source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
            settings = settings("", "", "");
            http = mock(HttpClient.class); response = mock(HttpResponse.class);
            when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
            when(response.statusCode()).thenReturn(200);
            house = new AiHouseModelClient(settings, new ObjectMapper(), http);
        }
        @AfterEach void closeHouse() { source.destroy(); }

        private AiHouseSettingsRepository settings(String url, String models, String key) {
            var repository = new AiHouseSettingsRepository(new JdbcTemplate(source), source,
                new AiKeyCipher(Base64.getEncoder().encodeToString(new byte[32])),
                "true", "22:00", "06:00", "America/Los_Angeles", "", url, models, "900", key);
            repository.run(null);
            return repository;
        }
        private AiHouseSettingsRepository.Changes changes(String preset, String url, String primary, String backup,
                String key, boolean clearKey) {
            return new AiHouseSettingsRepository.Changes(true, "22:00", "06:00", "America/Los_Angeles", preset,
                url, primary, backup, 600, key, clearKey);
        }
        private void configure() {
            settings.save(changes("CUSTOM", "http://gpu.test:11434/v1/", "qwen3:32b", "gemma4:27b", null, false), "toby");
        }
        private String chat(String content) {
            return "{\"choices\":[{\"message\":{\"content\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(content)
                + "},\"finish_reason\":\"stop\"}]}";
        }

        @Test void overnightNeverCallsTheCloudEvenWhenTheHouseModelFails() {
            var houseClient = mock(AiHouseModelClient.class);
            when(houseClient.generate("prompt")).thenThrow(new AiProviderFailure(true, "down"));
            var overnight = new AiGradingRouter(connections, client, houseClient, "system-gemini", "system-groq");
            assertThrows(AiProviderFailure.class, () -> overnight.generateOvernight("prompt"));
            verifyNoInteractions(client, connections);
        }
        @Test void overnightReturnsTheHouseAnswerAndDaytimeNeverAsksTheHouse() {
            var houseClient = mock(AiHouseModelClient.class);
            when(houseClient.generate("prompt")).thenReturn(new AiHouseModelClient.Reply("qwen", "{\"score\":1}"));
            when(client.generate(AiProvider.gemini, "personal-gemini", "prompt", 0)).thenReturn("cloud");
            var both = new AiGradingRouter(connections, client, houseClient, "system-gemini", "system-groq");
            assertEquals("{\"score\":1}", both.generateOvernight("prompt"));
            assertEquals("cloud", both.generate("prompt", author));
            verify(houseClient, times(1)).generate(any());
        }
        @Test void routerWithoutHouseClientReportsUnavailable() {
            assertTrue(assertThrows(AiProviderFailure.class, () -> router.generateOvernight("prompt")).isOperational());
        }
        @Test void notConfiguredByDefaultSoNothingIsCalled() throws Exception {
            assertFalse(house.isConfigured());
            assertEquals(AiHouseSettingsRepository.Preset.NONE, settings.current().preset());
            assertThrows(AiProviderFailure.class, () -> house.generate("prompt"));
            verify(http, never()).send(any(), any());
        }
        @Test void envDefaultsConfigureACustomServer() {
            var fromEnv = settings("http://gpu.test/v1", "qwen3:32b,gemma4:27b", "env-key");
            var current = fromEnv.current();
            assertEquals(AiHouseSettingsRepository.Preset.CUSTOM, current.preset());
            assertEquals(java.util.List.of("qwen3:32b", "gemma4:27b"), current.models());
            assertTrue(current.apiKeySet());
            assertEquals("env-key", fromEnv.apiKey());
        }
        @Test void sendsOneUserMessageWithJsonModeAndStripsThinking() throws Exception {
            configure();
            when(response.body()).thenReturn(chat("<think>maybe {\"score\": 0}</think>\n{\"score\": 0.9}"));
            var reply = house.generate("grade this");
            assertEquals("qwen3:32b", reply.model());
            assertEquals("{\"score\": 0.9}", reply.text());
            var captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http).send(captor.capture(), any());
            assertEquals("http://gpu.test:11434/v1/chat/completions", captor.getValue().uri().toString());
            assertEquals(600, captor.getValue().timeout().orElseThrow().toSeconds());
            assertTrue(captor.getValue().headers().firstValue("Authorization").isEmpty());
        }
        @Test void plainTextRequestsDoNotAskForJson() throws Exception {
            configure();
            when(response.body()).thenReturn(chat("Score 0.9 - Strong"));
            house.generate("write a rubric", false);
            var captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http).send(captor.capture(), any());
            var published = new java.util.concurrent.atomic.AtomicReference<String>();
            captor.getValue().bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                public void onNext(java.nio.ByteBuffer b) {
                    published.set(java.nio.charset.StandardCharsets.UTF_8.decode(b).toString());
                }
                public void onError(Throwable t) { }
                public void onComplete() { }
            });
            assertFalse(published.get().contains("response_format"));
        }
        @Test void unsupportedJsonModeIsRetriedWithoutIt() throws Exception {
            configure();
            when(response.statusCode()).thenReturn(400, 200);
            when(response.body()).thenReturn(chat("{\"score\":1}")); // An error body is never read.
            assertEquals("{\"score\":1}", house.generate("prompt").text());
            verify(http, times(2)).send(any(), any());
        }
        @Test void backupModelAnswersWhenThePrimaryFails() throws Exception {
            configure();
            when(response.statusCode()).thenReturn(500, 200);
            when(response.body()).thenReturn(chat("{\"score\":1}"));
            assertEquals("gemma4:27b", house.generate("prompt").model());
        }
        @Test void timeoutIsOperationalAndTriesTheBackup() throws Exception {
            configure();
            when(http.send(any(HttpRequest.class), any())).thenThrow(new HttpTimeoutException("slow"));
            assertTrue(assertThrows(AiProviderFailure.class, () -> house.generate("prompt")).isOperational());
            verify(http, times(2)).send(any(), any());
            assertTrue(house.isAvailable()); // A slow model is not a dead server.
        }
        @Test void unreachableServerOpensTheBreakerUntilReset() throws Exception {
            configure();
            when(http.send(any(HttpRequest.class), any())).thenThrow(new java.net.ConnectException("refused"));
            assertThrows(AiProviderFailure.class, () -> house.generate("prompt"));
            assertFalse(house.isAvailable());
            assertThrows(AiProviderFailure.class, () -> house.generate("prompt"));
            verify(http, times(1)).send(any(), any());
            house.setClock(Clock.offset(Clock.systemUTC(), AiHouseModelClient.BREAKER.plusSeconds(1)));
            assertTrue(house.isAvailable());
            house.setClock(Clock.systemUTC());
            house.resetBreaker();
            assertTrue(house.isAvailable());
        }
        @Test void listModelsReadsOpenAiAndOllamaShapes() throws Exception {
            when(response.body()).thenReturn("{\"data\":[{\"id\":\"qwen3:32b\"},{\"id\":\"gemma4:27b\"}]}",
                "{\"models\":[{\"name\":\"llama\"}]}");
            assertEquals(java.util.List.of("qwen3:32b", "gemma4:27b"), house.listModels("http://gpu.test/v1/", "k").models());
            assertEquals(java.util.List.of("llama"), house.listModels("http://gpu.test/v1", null).models());
            var captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http, times(2)).send(captor.capture(), any());
            assertEquals("http://gpu.test/v1/models", captor.getAllValues().get(0).uri().toString());
            assertEquals("Bearer k", captor.getAllValues().get(0).headers().firstValue("Authorization").orElseThrow());
            assertThrows(AiProviderFailure.class, () -> house.listModels("file:///etc/passwd", null));
        }
        @Test void savedSettingsWinOverDefaultsAndResetRestoresThem() {
            configure();
            var current = settings.current();
            assertEquals("http://gpu.test:11434/v1", current.baseUrl());
            assertEquals("saved", current.sources().get("base_url"));
            assertEquals("toby", current.updatedBy());
            settings.reset("toby");
            assertEquals(AiHouseSettingsRepository.Preset.NONE, settings.current().preset());
        }
        @Test void presetUrlIsUsedUnlessCustom() {
            settings.save(changes("OLLAMA", "http://ignored.test/v1", "qwen3:32b", "", null, false), "toby");
            assertEquals("http://localhost:11434/v1", settings.current().baseUrl());
        }
        @Test void apiKeyIsStoredEncryptedAndNeverInTheSettings() throws Exception {
            settings.save(changes("CUSTOM", "http://gpu.test/v1", "qwen", "", "secret-key", false), "toby");
            String stored = new JdbcTemplate(source).queryForObject(
                "SELECT setting_value FROM ai_house_settings WHERE name = 'api_key'", String.class);
            assertFalse(stored.contains("secret-key"));
            assertEquals("secret-key", settings.apiKey());
            assertTrue(settings.current().apiKeySet());
            assertFalse(new ObjectMapper().writeValueAsString(settings.current()).contains("secret-key"));
            settings.save(changes("CUSTOM", "http://gpu.test/v1", "qwen", "", "", false), "toby");
            assertEquals("secret-key", settings.apiKey()); // Blank keeps it.
            settings.save(changes("CUSTOM", "http://gpu.test/v1", "qwen", "", null, true), "toby");
            assertEquals("", settings.apiKey());
        }
        @Test void keyIsRefusedWithoutAnEncryptionKey() {
            var noCipher = new AiHouseSettingsRepository(new JdbcTemplate(source), source, new AiKeyCipher(""),
                "true", "22:00", "06:00", "UTC", "", "", "", "900", "");
            noCipher.run(null);
            var e = assertThrows(AiConnectionException.class,
                () -> noCipher.save(changes("CUSTOM", "http://gpu.test/v1", "qwen", "", "secret", false), "toby"));
            assertEquals(409, e.getStatus());
        }
        @Test void validationRejectsBadInput() {
            assertNotNull(AiHouseSettingsRepository.validate(changes("CUSTOM", "ftp://x", "qwen", "", null, false)));
            assertNotNull(AiHouseSettingsRepository.validate(changes("CUSTOM", "http://x/v1", "", "", null, false)));
            assertNotNull(AiHouseSettingsRepository.validate(changes("BOGUS", "", "", "", null, false)));
            assertNotNull(AiHouseSettingsRepository.validate(new AiHouseSettingsRepository.Changes(
                true, "25:00", "06:00", "UTC", "NONE", "", "", "", 900, null, false)));
            assertNotNull(AiHouseSettingsRepository.validate(new AiHouseSettingsRepository.Changes(
                true, "22:00", "06:00", "Mars/Base", "NONE", "", "", "", 900, null, false)));
            assertNotNull(AiHouseSettingsRepository.validate(new AiHouseSettingsRepository.Changes(
                true, "22:00", "06:00", "UTC", "NONE", "", "", "", 5, null, false)));
            assertNull(AiHouseSettingsRepository.validate(changes("NONE", "", "", "", null, false)));
            assertThrows(AiConnectionException.class,
                () -> settings.save(changes("CUSTOM", "ftp://x", "qwen", "", null, false), "toby"));
        }
        @Test void windowCrossesMidnightAndCanBeDisabled() {
            var current = settings.current(); // 22:00-06:00 Los Angeles
            assertTrue(AiHouseSettingsRepository.isInWindow(current, at("2026-11-18T23:30:00-08:00")));
            assertTrue(AiHouseSettingsRepository.isInWindow(current, at("2026-11-19T05:59:00-08:00")));
            assertFalse(AiHouseSettingsRepository.isInWindow(current, at("2026-11-19T06:00:00-08:00")));
            assertFalse(AiHouseSettingsRepository.isInWindow(current, at("2026-11-18T12:00:00-08:00")));
            assertTrue(AiHouseSettingsRepository.isInWindow(current, at("2026-11-18T22:00:00-08:00")));
            settings.setClock(Clock.fixed(at("2026-11-18T23:30:00-08:00"), java.time.ZoneOffset.UTC));
            assertTrue(settings.isNightWindowOpen());
            settings.save(new AiHouseSettingsRepository.Changes(false, "22:00", "06:00", "America/Los_Angeles",
                "NONE", "", "", "", 900, null, false), "toby");
            assertFalse(settings.isNightWindowOpen());
        }
        @Test void sameDayWindowWorksToo() {
            settings.save(new AiHouseSettingsRepository.Changes(true, "01:00", "05:00", "UTC",
                "NONE", "", "", "", 900, null, false), "toby");
            assertTrue(AiHouseSettingsRepository.isInWindow(settings.current(), at("2026-11-18T03:00:00Z")));
            assertFalse(AiHouseSettingsRepository.isInWindow(settings.current(), at("2026-11-18T06:00:00Z")));
        }
        private java.time.Instant at(String time) {
            return java.time.OffsetDateTime.parse(time).toInstant();
        }
    }
}
