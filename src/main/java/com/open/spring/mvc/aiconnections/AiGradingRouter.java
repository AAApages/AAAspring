package com.open.spring.mvc.aiconnections;

import java.util.HashSet;
import java.util.Set;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.open.spring.mvc.groups.Submitter;
import com.open.spring.mvc.person.Person;

@Service
public class AiGradingRouter {
    private static final Logger logger = LoggerFactory.getLogger(AiGradingRouter.class);
    private final AiConnectionService connections;
    private final AiProviderClient client;
    private final String systemGeminiKey;
    private final String systemGroqKey;

    private final AiHouseModelClient house;

    @Autowired
    public AiGradingRouter(AiConnectionService connections, AiProviderClient client, AiHouseModelClient house,
            @Value("${gemini.api.key:}") String systemGeminiKey,
            @Value("${groq.api.key:${GROQ_API_KEY:}}") String systemGroqKey) {
        this.connections = connections; this.client = client; this.house = house;
        this.systemGeminiKey = systemGeminiKey; this.systemGroqKey = systemGroqKey;
    }
    /** Without a house model client: the overnight path always reports the house model unavailable. */
    public AiGradingRouter(AiConnectionService connections, AiProviderClient client,
            String systemGeminiKey, String systemGroqKey) {
        this(connections, client, null, systemGeminiKey, systemGroqKey);
    }
    /**
     * Night grading: asks only the self-hosted model. It never falls back to Gemini or Groq, so a
     * night run costs nothing; when the house model is unavailable the caller leaves the work queued.
     */
    public String generateOvernight(String prompt) {
        if (house == null) throw new AiProviderFailure(true, "No house model is configured.");
        long started = System.nanoTime();
        logger.info("AI provider request started submission={} source=house", MDC.get("aiSubmissionId"));
        AiHouseModelClient.Reply reply = house.generate(prompt);
        logger.info("AI provider response received submission={} source=house model={} elapsedMs={}; grade validation follows",
            MDC.get("aiSubmissionId"), reply.model(), (System.nanoTime() - started) / 1_000_000);
        return reply.text();
    }
    public String generate(String prompt, Submitter submitter) {
        return generate(prompt, submitter, 0);
    }
    public String generate(String prompt, Submitter submitter, int hackCount) {
        Set<String> attempted = new HashSet<>();
        if (submitter instanceof Person person && person.getId() != null) {
            for (AiProvider provider : AiProvider.values()) {
                if (!client.isConfigured(provider)) {
                    logger.info("AI provider skipped submission={} source=personal provider={} reason=model_unconfigured",
                        MDC.get("aiSubmissionId"), provider);
                    continue;
                }
                String key = connections.credential(person.getId(), provider).orElse(null);
                String output = attempt(provider, key, prompt, "personal", attempted, hackCount);
                if (output != null) return output;
            }
        }
        String output = attempt(AiProvider.gemini, systemGeminiKey, prompt, "system", attempted, hackCount);
        if (output != null) return output;
        output = attempt(AiProvider.groq, systemGroqKey, prompt, "system", attempted, hackCount);
        if (output != null) return output;
        logger.warn("AI grading exhausted credentials submission={}; submission remains available for review.", MDC.get("aiSubmissionId"));
        throw new AiProviderFailure(true,
            "No configured AI credential could complete grading. Your submission remains saved for review.");
    }
    private String attempt(AiProvider provider, String key, String prompt, String source, Set<String> attempted, int hackCount) {
        String submissionId = MDC.get("aiSubmissionId");
        GroqOutputBudget budget = GroqOutputBudget.forHackCount(hackCount);
        String skip = !client.isConfigured(provider) ? "model_unconfigured"
            : key == null || key.isBlank() ? "key_missing"
            : provider == AiProvider.groq && !budget.fits() ? "output_budget_exceeded"
            : !attempted.add(provider.name() + ":" + key) ? "duplicate_credential" : null;
        if (skip != null) {
            logger.info("AI provider skipped submission={} source={} provider={} reason={}", submissionId, source, provider, skip);
            return null;
        }
        long started = System.nanoTime();
        if (provider == AiProvider.groq)
            logger.info("AI provider budget submission={} source={} provider={} tokens={} compact={}",
                submissionId, source, provider, budget.maxCompletionTokens(), budget.compact());
        logger.info("AI provider request started submission={} source={} provider={}", submissionId, source, provider);
        try {
            String output = client.generate(provider, key, prompt, hackCount);
            logger.info("AI provider response received submission={} source={} provider={} elapsedMs={}; grade validation follows",
                submissionId, source, provider, (System.nanoTime() - started) / 1_000_000);
            return output;
        } catch (AiProviderFailure e) {
            logger.warn("AI provider attempt failed submission={} source={} provider={} httpStatus={} fallback={} elapsedMs={}",
                submissionId, source, provider, e.getHttpStatus(), e.isOperational(), (System.nanoTime() - started) / 1_000_000);
            if (!e.isOperational()) throw e;
            return null;
        }
    }
}
