package com.open.spring.mvc.assignments;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.FileHandler;
import com.open.spring.mvc.aiconnections.AiGradingRouter;

/** Score boundaries and prompts, with provider responses stubbed rather than live AI calls. */
class AssignmentAiGradingPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiGradingRouter router = mock(AiGradingRouter.class);
    private final AssignmentAiGradingService grader = new AssignmentAiGradingService(
            mapper, mock(FileHandler.class), router, "https://api.github.com", "", "");

    @ParameterizedTest
    @ValueSource(doubles = {1, 10, 100})
    void acceptsStrongAndExceptionalScoresOnBothRubricScales(double points) throws Exception {
        for (boolean fallback : List.of(false, true)) {
            for (double fraction : new double[] {0.9, 0.93}) {
                double score = (fallback ? 1 : points) * fraction;
                reply(score, 4, null);

                var result = grader.grade(submission(points, fallback, false));

                assertEquals("graded", result.status());
                assertEquals(points * fraction, result.grade(), 1e-9);
                assertEquals(4, result.qualityScore());
                assertEquals(fallback, result.usedDefaultRubric());
            }
        }
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(router, atLeastOnce()).generate(prompt.capture(), isNull(), eq(0));
        String text = prompt.getValue();
        assertTrue(text.contains(AssignmentRubricService.gradingPolicy(1)));
        assertTrue(text.contains("every core requirement must be met correctly"));
        assertTrue(text.contains("all or nearly all remaining rubric criteria"));
        assertTrue(text.contains("do not invent implied requirements"));
        assertTrue(text.contains("do not deduct repeatedly"));
        assertTrue(text.contains("Extra length alone does not qualify"));
        assertTrue(text.contains("4 for Strong or Exceptional"));
        assertTrue(text.contains("a 5 is reserved for a separate batch-review pass"));
    }

    @ParameterizedTest
    @ValueSource(doubles = {1, 10, 100})
    void rejectsOverallScoresAboveCapOnBothRubricScales(double points) throws Exception {
        for (boolean fallback : List.of(false, true)) {
            reply((fallback ? 1 : points) * 0.94, 4, null);
            assertEquals("failed", grader.grade(submission(points, fallback, false)).status());
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {1, 10, 100})
    void weightedHackCapAndAggregationUseNinetyThreePercent(double points) throws Exception {
        for (boolean fallback : List.of(false, true)) {
            double scale = fallback ? 1 : points;
            var submission = submission(points, fallback, true);
            reply(0, 4, List.of(hack("Hack #1", scale * 0.93), hack("Hack #2", scale * 0.9)));
            var result = grader.grade(submission);
            assertEquals("graded", result.status());
            assertEquals(Math.round(points * 0.918 * 100) / 100.0, result.grade(), 1e-9);

            reply(0, 4, List.of(hack("Hack #1", scale * 0.93), hack("Hack #2", scale * 0.93)));
            assertEquals(points * 0.93, grader.grade(submission).grade(), 1e-9);

            // An over-cap hack is invalid even if its weighted average would be below the cap.
            reply(0, 4, List.of(hack("Hack #1", scale * 0.94), hack("Hack #2", 0)));
            assertEquals("failed", grader.grade(submission).status());
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {1, 10, 100})
    void oldRubricFallsBackToUpdatedDefault(double points) throws Exception {
        var submission = submission(points, false, false);
        submission.getAssignment().setAiRubric("Score " + points * 0.9 + " — Strong / Exceptional\nScore 0 — Missing");
        reply(0.93, 4, null);
        var result = grader.grade(submission);
        assertEquals("graded", result.status());
        assertTrue(result.usedDefaultRubric());
        assertEquals(points * 0.93, result.grade(), 1e-9);
        verify(router).generate(contains(Assignment.DEFAULT_AI_RUBRIC), isNull(), eq(0));
    }

    @ParameterizedTest
    @CsvSource({"1, 0.93, 0.90", "10, 9.3, 9", "100, 93, 90", "0.25, 0.23, 0.22", "0.5, 0.47, 0.45"})
    void readinessUsesRoundedCapWithoutAcceptingOldCeiling(double points, double cap, double oldCap) {
        assertEquals(cap, AssignmentRubricService.aiTopScore(points), 1e-9);
        assertTrue(AssignmentRubricService.isAiRubricReady("Score " + cap + " — Exceptional\nScore 0 — Missing", points));
        assertFalse(AssignmentRubricService.isAiRubricReady("Score " + oldCap + " — Strong\nScore 0 — Missing", points));
    }

    private void reply(double score, int quality, List<Map<String, Object>> hacks) throws Exception {
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("score", score);
        response.put("quality", quality);
        response.put("feedback", "Requirements met. Relevant extension demonstrated.");
        if (hacks != null) response.put("hacks", hacks);
        when(router.generate(anyString(), isNull(), anyInt())).thenReturn(mapper.writeValueAsString(response));
    }

    private Map<String, Object> hack(String name, double score) {
        return Map.of("hack", name, "score", score);
    }

    private AssignmentSubmission submission(double points, boolean fallback, boolean weighted) {
        Assignment assignment = new Assignment();
        assignment.setPoints(points);
        if (!fallback) {
            assignment.setAiRubric("Score " + AssignmentRubricService.aiTopScore(points)
                    + " — Exceptional\nScore " + points * 0.9 + " — Strong\nScore 0 — Missing");
        }
        if (weighted) {
            assignment.setHackWeights("[{\"hack\":\"Hack #1\",\"weight\":60,\"instructions\":\"Add lives\"},"
                    + "{\"hack\":\"Hack #2\",\"weight\":40,\"instructions\":\"Add a button\"}]");
        }
        AssignmentSubmission submission = new AssignmentSubmission();
        submission.setAssignment(assignment);
        submission.setContent(Map.of("type", "text", "text", "Student work"));
        return submission;
    }
}
