package com.open.spring.mvc.aiconnections;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class AiProviderConfigurationTest {
    private MockEnvironment environment(Map<String, Object> overrides) throws IOException {
        var environment = new MockEnvironment();
        environment.getPropertySources().addLast(
            new ResourcePropertySource("classpath:application.properties"));
        environment.getPropertySources().addFirst(new MapPropertySource("deployment", overrides));
        return environment;
    }

    private AiProviderClient client(MockEnvironment environment) {
        return new AiProviderClient(new ObjectMapper(),
            environment.getRequiredProperty("gemini.api.url"),
            environment.getRequiredProperty("groq.grading-model"),
            environment.getRequiredProperty("groq.api.url"));
    }

    @Test void defaultsConfigureBothModelsButDoNotInventCredentialsOrEncryptionKey() throws IOException {
        var environment = environment(Map.of());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent",
            environment.getProperty("gemini.api.url"));
        assertEquals("https://api.groq.com/openai/v1/chat/completions",
            environment.getProperty("groq.api.url"));
        assertEquals("", environment.getProperty("groq.api.key"));
        assertEquals("llama-3.1-8b-instant", environment.getProperty("groq.grading-model"));
        assertEquals("", environment.getProperty("ai.key.encryption-key"));
        assertFalse(new AiKeyCipher(environment.getProperty("ai.key.encryption-key")).isAvailable());
        assertTrue(client(environment).isConfigured(AiProvider.gemini));
        assertTrue(client(environment).isConfigured(AiProvider.groq));
    }

    @Test void explicitlyBlankModelDisablesGroqWithoutDisablingGemini() throws IOException {
        var environment = environment(Map.of("GROQ_GRADING_MODEL", ""));
        assertEquals("", environment.getProperty("groq.grading-model"));
        assertFalse(client(environment).isConfigured(AiProvider.groq));
        assertTrue(client(environment).isConfigured(AiProvider.gemini));
    }

    @Test void uppercaseDeploymentSettingsEnablePersonalStorageAndConfigureBothGroqEndpoints() throws IOException {
        String encryptionKey = Base64.getEncoder().encodeToString(new byte[32]);
        var environment = environment(Map.of(
            "AI_KEY_ENCRYPTION_KEY", encryptionKey,
            "GROQ_GRADING_MODEL", "test-model",
            "GROQ_API_KEY", "fake-system-key",
            "GROQ_SERVER", "https://example.test/custom/v1/chat/completions",
            "GEMINI_API_URL", "https://example.test/gemini:generateContent"));
        assertEquals("fake-system-key", environment.getProperty("groq.api.key"));
        assertEquals("test-model", environment.getProperty("groq.grading-model"));
        assertEquals("https://example.test/gemini:generateContent", environment.getProperty("gemini.api.url"));
        var cipher = new AiKeyCipher(environment.getProperty("ai.key.encryption-key"));
        assertTrue(cipher.isAvailable());
        assertEquals("fake-personal-key",
            cipher.decrypt(1, AiProvider.groq, cipher.encrypt(1, AiProvider.groq, "fake-personal-key")));
        var client = client(environment);
        assertTrue(client.isConfigured(AiProvider.groq));
        assertEquals("test-model", ReflectionTestUtils.getField(client, "groqModel"));
        assertEquals("https://example.test/custom/v1/chat/completions",
            ReflectionTestUtils.getField(client, "groqChatUrl"));
        assertEquals("https://example.test/custom/v1/models",
            ReflectionTestUtils.getField(client, "groqModelsUrl"));
    }

    @Test void directSpringPropertyOverridesRemainSupported() throws IOException {
        var environment = environment(Map.of(
            "GROQ_GRADING_MODEL", "uppercase-model",
            "groq.grading-model", "direct-model",
            "GROQ_SERVER", "https://unused.test/v1/chat/completions",
            "groq.api.url", "https://example.test/v1/chat/completions",
            "groq.api.key", "fake-direct-key"));
        assertEquals("direct-model", environment.getProperty("groq.grading-model"));
        assertEquals("fake-direct-key", environment.getProperty("groq.api.key"));
        assertEquals("https://example.test/v1/chat/completions",
            ReflectionTestUtils.getField(client(environment), "groqChatUrl"));
    }
}
