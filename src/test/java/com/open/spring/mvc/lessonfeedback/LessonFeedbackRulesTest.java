package com.open.spring.mvc.lessonfeedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.Feedback;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.LessonInput;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.SubmissionInput;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.Summary;

/** Saved ratings stay fixed, submissions need complete feedback, and summaries keep comments unnamed. */
class LessonFeedbackRulesTest {
    private final LessonFeedbackRules rules = new LessonFeedbackRules();

    private static Feedback complete(int rating, String pros, String grows) {
        return new Feedback(rating, rating, rating, rating, pros, grows);
    }

    private static HttpStatus statusOf(Runnable action) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action::run);
        return HttpStatus.valueOf(error.getStatusCode().value());
    }

    @Test
    void lessonStepSavesPartialRatingsAndTrimmedText() {
        Feedback saved = rules.mergeLessonPhase(null, new LessonInput(4, null, 3, "  Clear examples ", " "));

        assertEquals(new Feedback(4, null, 3, null, "Clear examples", null), saved);
        assertEquals(Set.of("lesson", "popcorn"), rules.lockedFields(saved));
    }

    @Test
    void savedRatingCannotBeChangedButCanBeResent() {
        Feedback saved = new Feedback(4, 2, null, null, "pros", "grows");

        assertEquals(HttpStatus.CONFLICT, statusOf(() -> rules.mergeLessonPhase(saved, new LessonInput(5, null, null, null, null))));
        assertEquals(HttpStatus.CONFLICT, statusOf(() -> rules.mergeSubmissionPhase(saved, new SubmissionInput(null, 3, 4, 4, null, null))));
        assertEquals(saved, rules.mergeLessonPhase(saved, new LessonInput(4, 2, null, null, null)));
    }

    @Test
    void submissionFillsEmptyRatingsAddsHomeworkAndEditsText() {
        Feedback saved = new Feedback(4, null, 3, null, "Old pros", "Old grows");

        Feedback merged = rules.mergeSubmissionPhase(saved, new SubmissionInput(4, 5, null, 2, "New pros", null));

        assertEquals(new Feedback(4, 5, 3, 2, "New pros", "Old grows"), merged);
        assertEquals(Set.of("lesson", "engage", "popcorn", "homework"), rules.lockedFields(merged));
    }

    @Test
    void submissionWithSkippedLessonStepMustBeCompleted() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> rules.mergeSubmissionPhase(null, new SubmissionInput(null, null, null, 3, "pros", null)));

        assertEquals(HttpStatus.BAD_REQUEST.value(), error.getStatusCode().value());
        assertTrue(error.getReason().contains("lesson, engage, popcorn, grows"));
        assertEquals(complete(3, "pros", "grows"),
                rules.mergeSubmissionPhase(null, new SubmissionInput(3, 3, 3, 3, "pros", "grows")));
    }

    @Test
    void clearingTextAtSubmissionLeavesFeedbackIncomplete() {
        Feedback saved = complete(4, "pros", "grows");

        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> rules.mergeSubmissionPhase(saved, new SubmissionInput(null, null, null, null, "   ", null))));
    }

    @Test
    void rejectsOutOfRangeRatingsAndLongText() {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> rules.mergeLessonPhase(null, new LessonInput(0, null, null, null, null))));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> rules.mergeLessonPhase(null, new LessonInput(null, 6, null, null, null))));
        String tooLong = "a".repeat(LessonFeedbackRules.MAX_TEXT_LENGTH + 1);
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> rules.mergeLessonPhase(null, new LessonInput(null, null, null, tooLong, null))));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> rules.mergeSubmissionPhase(null, new SubmissionInput(3, 3, 3, -1, "pros", "grows"))));
    }

    @Test
    void summaryBeforeAnyResponseHasZeroCountsAndNoAverages() {
        Summary summary = rules.summarize(List.of(new Feedback(5, 5, 5, null, "unsubmitted", "unsubmitted")), new Random(1));

        assertEquals(0, summary.responseCount());
        assertNull(summary.averageLesson());
        assertEquals(List.of(0, 0, 0, 0, 0), summary.counts().get("homework"));
        assertTrue(summary.pros().isEmpty());
        assertTrue(summary.grows().isEmpty());
    }

    @Test
    void summaryShowsTheFirstResponseAndIgnoresUnfinishedFeedback() {
        Summary summary = rules.summarize(List.of(
                complete(4, "Clear", "Slower"),
                new Feedback(1, 1, 1, null, "unfinished", "unfinished")), new Random(1));

        assertEquals(1, summary.responseCount());
        assertEquals(4.0, summary.averageLesson());
        assertEquals(List.of(0, 0, 0, 1, 0), summary.counts().get("lesson"));
        assertEquals(List.of("Clear"), summary.pros());
    }

    @Test
    void summaryCountsEachRatingFromOneToFive() {
        Summary summary = rules.summarize(List.of(
                new Feedback(1, 2, 3, 5, "a", "b"),
                new Feedback(1, 2, 4, 5, "c", "d"),
                new Feedback(5, 2, 3, 5, "e", "f")), new Random(1));

        assertEquals(List.of("lesson", "engage", "popcorn", "homework"), List.copyOf(summary.counts().keySet()));
        assertEquals(List.of(2, 0, 0, 0, 1), summary.counts().get("lesson"));
        assertEquals(List.of(0, 3, 0, 0, 0), summary.counts().get("engage"));
        assertEquals(List.of(0, 0, 2, 1, 0), summary.counts().get("popcorn"));
        assertEquals(List.of(0, 0, 0, 0, 3), summary.counts().get("homework"));
    }

    @Test
    void summaryAveragesAndShufflesCommentsSeparately() {
        List<Feedback> records = new ArrayList<>();
        List<String> submittedPros = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            records.add(new Feedback(i % 2 == 0 ? 4 : 5, 3, 2, 1, "pros " + i, "grows " + i));
            submittedPros.add("pros " + i);
        }

        Summary summary = rules.summarize(records, new Random(42));

        assertEquals(8, summary.responseCount());
        assertEquals(4.5, summary.averageLesson());
        assertEquals(3.0, summary.averageEngage());
        assertEquals(2.0, summary.averagePopcorn());
        assertEquals(1.0, summary.averageHomework());
        assertEquals(Set.copyOf(submittedPros), Set.copyOf(summary.pros()));
        assertFalse(submittedPros.equals(summary.pros()), "pros must not keep submission order");
        List<Integer> prosOrder = summary.pros().stream().map(text -> text.substring(5)).map(Integer::valueOf).toList();
        List<Integer> growsOrder = summary.grows().stream().map(text -> text.substring(6)).map(Integer::valueOf).toList();
        assertFalse(prosOrder.equals(growsOrder), "pros and grows must not be paired by position");
    }
}
