package com.open.spring.mvc.assignments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.FileHandler;
import com.open.spring.mvc.aiconnections.AiConnectionService;
import com.open.spring.mvc.aiconnections.AiGradingRouter;
import com.open.spring.mvc.aiconnections.AiProviderClient;
import com.sun.net.httpserver.HttpServer;

/**
 * Weighted hacks ("Hack #1 (Weight: 60%)"), graded against a stubbed Gemini.
 */
class AssignmentAiGradingHackWeightsTest {
    // pageContent as /game/essentials/variables-challenge sends it (HTML stripped, whitespace collapsed).
    private static final String WEIGHTED_PAGE = "JavaScript Variables: Challenge Hack #1 (Weight: 60%) Add-Lives "
            + "Challenge Instructions: Add lives to player Reflect: Did adding lives impact the enemy? %%js let gameSpeed = 10; "
            + "HTML DOM: Challenge Hack #2 (Weight: 40%) Add-Button Challenge Instructions: Add a 4th button. "
            + "Hack #1 is about objects.";

    private HttpServer github;
    private final AtomicReference<String> geminiReply = new AtomicReference<>();
    private final AtomicReference<String> geminiPrompt = new AtomicReference<>();

    @BeforeEach
    void startGeminiStub() throws Exception {
        github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        github.createContext("/gemini", exchange -> {
            geminiPrompt.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = new ObjectMapper().writeValueAsBytes(Map.of("candidates",
                    List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", geminiReply.get())))))));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        github.start();
    }

    @AfterEach
    void stopGeminiStub() {
        github.stop(0);
    }

    @Test
    void weightedHacksAreParsedFromPageContentWithTheirInstructions() {
        List<AssignmentRubricService.HackWeight> hacks = AssignmentRubricService.parseHackWeights(WEIGHTED_PAGE);

        assertEquals(2, hacks.size());
        assertEquals("Hack #1", hacks.get(0).hack());
        assertEquals(60.0, hacks.get(0).weight());
        assertTrue(hacks.get(0).instructions().startsWith("Add-Lives Challenge Instructions"));
        assertFalse(hacks.get(0).instructions().contains("Add a 4th button"));
        assertEquals(40.0, hacks.get(1).weight());
        assertTrue(hacks.get(1).instructions().contains("Add a 4th button"));
    }

    @Test
    void aPageWithFewerThanTwoWeightedHacksHasNoWeights() {
        assertNull(AssignmentRubricService.hackWeightsJson("Hack #1 (Weight: 100%) do it. Hack #2 do more."));
        assertNull(AssignmentRubricService.hackWeightsJson(null));
        assertEquals(2, AssignmentRubricService.readHackWeights(
                AssignmentRubricService.hackWeightsJson(WEIGHTED_PAGE)).size());
    }

    @Test
    void weightedHacksDecideTheGradeInsteadOfTheOverallScore() throws Exception {
        // Overall 0.85 is ignored: 0.6 * 0.9 + 0.4 * 0.4 = 0.70.
        geminiReply.set("""
                {"score": 0.85, "quality": 3, "feedback": "Lives work well. The 4th button is missing.",
                 "hacks": [{"hack": "Hack #2", "score": 0.4}, {"hack": "Hack #1", "score": 0.9}]}""");

        AssignmentAiGradingService.GradeResult result = weightedService().grade(weightedSubmission());

        assertEquals("graded", result.status());
        assertEquals(0.7, result.grade());
        assertEquals("Hack #1 (60%): 0.9/1, Hack #2 (40%): 0.4/1. Lives work well. The 4th button is missing.",
                result.feedback());
        assertTrue(geminiPrompt.get().contains("Hack #1 (weight 60%): Add-Lives Challenge Instructions"));
    }

    @Test
    void aMissingHackScoreFailsTheGradeSoItIsRetried() throws Exception {
        geminiReply.set("""
                {"score": 0.85, "quality": 3, "feedback": "Fine.", "hacks": [{"hack": "Hack #1", "score": 0.9}]}""");

        AssignmentAiGradingService.GradeResult result = weightedService().grade(weightedSubmission());

        assertEquals("failed", result.status());
    }

    private AssignmentAiGradingService weightedService() {
        ObjectMapper mapper = new ObjectMapper();
        AiProviderClient client = new AiProviderClient(mapper,
                "http://127.0.0.1:" + github.getAddress().getPort() + "/gemini", "",
                "http://127.0.0.1:" + github.getAddress().getPort() + "/groq/chat/completions");
        AiGradingRouter router = new AiGradingRouter(mock(AiConnectionService.class), client, "test-key", "");
        return new AssignmentAiGradingService(new ObjectMapper(), mock(FileHandler.class),
                router, "http://unused", "", "");
    }

    private AssignmentSubmission weightedSubmission() {
        Assignment assignment = new Assignment();
        assignment.setPoints(1.0);
        assignment.setHackWeights(AssignmentRubricService.hackWeightsJson(WEIGHTED_PAGE));
        AssignmentSubmission submission = new AssignmentSubmission();
        submission.setAssignment(assignment);
        submission.setContent(Map.of("type", "text", "text", "let lives = 3; // hack 1 only"));
        return submission;
    }
}
