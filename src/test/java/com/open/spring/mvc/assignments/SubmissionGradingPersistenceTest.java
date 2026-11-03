package com.open.spring.mvc.assignments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackService;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.person.PersonRole;
import com.open.spring.mvc.person.PersonRoleJpaRepository;
import com.open.spring.mvc.synergy.SynergyGrade;
import com.open.spring.mvc.synergy.SynergyGradeJpaRepository;

/**
 * Grading writes, closure and reviews against a real SQLite database, including concurrent threads.
 * Uses the seeded test accounts hop (student), niko (teacher) and lex (assignment creator). Runs
 * outside a test transaction so each write commits.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SubmissionGradingService.class, SubmissionAiGrader.class, AssignmentAuthorizationService.class,
        AssignmentClosureService.class, GradeReviewService.class, FinalGradeTransfer.class,
        AssignmentSubmissionHistoryService.class, LessonFeedbackService.class, LessonFeedbackRules.class,
        SubmissionGradingPersistenceTest.Json.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SubmissionGradingPersistenceTest {

    private static final Path DATABASE = createDatabaseFile();

    @DynamicPropertySource
    static void sqlite(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE + "?journal_mode=WAL");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    private static Path createDatabaseFile() {
        try {
            return Files.createTempFile("grading-test", ".db");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void deleteDatabase() throws IOException {
        Files.deleteIfExists(DATABASE);
        Files.deleteIfExists(Path.of(DATABASE + "-wal"));
        Files.deleteIfExists(Path.of(DATABASE + "-shm"));
    }

    @Autowired private AssignmentSubmissionJPA submissionRepo;
    @Autowired private AssignmentJpaRepository assignmentRepo;
    @Autowired private SubmissionGradeEventJpaRepository eventRepo;
    @Autowired private SubmissionGradingService gradingService;
    @Autowired private SubmissionAiGrader aiGrader;
    @Autowired private AssignmentClosureService closureService;
    @Autowired private GradeReviewService reviewService;
    @Autowired private AssignmentSubmissionHistoryService historyService;
    @Autowired private GradeReviewJpaRepository reviewRepo;
    @Autowired private SynergyGradeJpaRepository gradebookRepo;
    @Autowired private PersonJpaRepository personRepo;
    @Autowired private PersonRoleJpaRepository roleRepo;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private AssignmentAiGradingService aiGradingService;

    /** The JPA test slice doesn't include Jackson. */
    @TestConfiguration
    static class Json {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    private Person hop;
    private Person niko;
    private Person lex;
    private Assignment assignment;
    private AssignmentSubmission submission;

    @BeforeEach
    void setUp() {
        reviewRepo.deleteAll();
        eventRepo.deleteAll();
        gradebookRepo.deleteAll();
        submissionRepo.deleteAll();
        assignmentRepo.deleteAll();
        hop = account("hop", "Hop Student", "ROLE_STUDENT");
        niko = account("niko", "Niko Teacher", "ROLE_TEACHER");
        lex = account("lex", "Lex Creator", "ROLE_STUDENT");
        Assignment pilot = new Assignment("Pilot", "homework", "Build a thing", 1.0, "10/25/2026", "link");
        pilot.getCreators().add(lex);
        assignment = assignmentRepo.save(pilot);
        submission = submit(hop, "https://example.com/v1");
    }

    /** Finds or creates a test account. */
    private Person account(String uid, String name, String role) {
        Person existing = personRepo.findByUid(uid);
        if (existing != null) {
            return existing;
        }
        PersonRole personRole = roleRepo.findByName(role);
        if (personRole == null) {
            personRole = roleRepo.save(new PersonRole(role));
        }
        return personRepo.save(new Person(uid + "@example.com", uid, "password", "sid-" + uid, name, null, false, personRole));
    }

    private AssignmentSubmission submit(Person student, String url) {
        AssignmentSubmission created = new AssignmentSubmission(assignment, student, content(url), "work", false);
        assertEquals(GradingOutcome.REVISION_CREATED, gradingService.submit(created, student).outcome());
        return created;
    }

    private static Map<String, Object> content(String url) {
        return new HashMap<>(Map.of("type", "link", "url", url, "notes", ""));
    }

    private static AssignmentAiGradingService.GradeResult aiGrade(double grade, String feedback) {
        return AssignmentAiGradingService.GradeResult.graded(grade, 3, feedback, false);
    }

    private SubmissionGradingState stored() {
        return submissionRepo.findGradingState(submission.getId()).orElseThrow();
    }

    private AssignmentSubmission fresh() {
        AssignmentSubmission loaded = submissionRepo.findById(submission.getId()).orElseThrow();
        loaded.setAssignment(assignment);
        return loaded;
    }

    private List<SubmissionGradeEventEntity.Type> history() {
        return gradingService.history(submission.getId()).stream().map(SubmissionGradeEventEntity::getType).toList();
    }

    /** Makes the AI block mid-call until {@code release} opens. */
    private void aiBlocksUntil(CountDownLatch started, CountDownLatch release, AssignmentAiGradingService.GradeResult result)
            throws Exception {
        when(aiGradingService.grade(any())).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS), "test never released the AI");
            return result;
        });
    }

    // ---- ordinary saves ----

    @Test
    void anOrdinarySaveOfAStaleCopyCannotChangeGradesOrContent() {
        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "AI feedback"));
        AssignmentSubmission staleCopy = fresh();
        gradingService.applyManualGrade(submission.getId(), niko, 1, 0.9, "Teacher feedback");

        staleCopy.setGrade(0.1);
        staleCopy.setFeedback("stale");
        staleCopy.setAiGrade(0.1);
        staleCopy.setManualOverride(false);
        staleCopy.setManualGrade(null);
        staleCopy.setNeedsAiRegrade(true);
        staleCopy.setRevision(99);
        staleCopy.setContent(content("https://example.com/sneaky"));
        staleCopy.setComment("edited comment");
        submissionRepo.save(staleCopy);

        SubmissionGradingState state = stored();
        assertEquals(0.9, state.effectiveGrade());
        assertEquals("Teacher feedback", state.effectiveFeedback());
        assertEquals(0.6, state.aiGrade());
        assertTrue(state.manualOverride());
        assertFalse(state.needsAiRegrade());
        assertEquals(1, state.revision());
        AssignmentSubmission reloaded = fresh();
        assertEquals(content("https://example.com/v1"), reloaded.getContent());
        assertEquals("edited comment", reloaded.getComment());
    }

    @Test
    void anOrdinarySaveBeforeAConditionalUpdateDoesNotBlockOrUndoIt() {
        AssignmentSubmission copy = fresh();
        copy.setComment("saved first");
        copy.setGrade(0.2);
        submissionRepo.save(copy);

        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "AI feedback"));
        submissionRepo.save(copy); // the same, now stale, copy saved again afterwards

        assertEquals(0.6, stored().effectiveGrade());
        assertEquals(GradeSource.AI, stored().source());
    }

    // ---- concurrency ----

    @Test
    void aTeacherWhoGradesWhileTheAiIsRunningKeepsTheirGrade() throws Exception {
        CountDownLatch aiStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        aiBlocksUntil(aiStarted, release, aiGrade(0.5, "AI feedback"));

        CompletableFuture<GradingResult> aiRequest = CompletableFuture.supplyAsync(() -> aiGrader.grade(fresh()));
        assertTrue(aiStarted.await(10, TimeUnit.SECONDS));
        GradingResult teacher = gradingService.applyManualGrade(submission.getId(), niko, 1, 0.9, "Teacher feedback");
        release.countDown();
        GradingResult ai = aiRequest.get(10, TimeUnit.SECONDS);

        assertEquals(GradingOutcome.MANUAL_GRADE_APPLIED, teacher.outcome());
        assertEquals(GradingOutcome.MANUAL_OVERRIDE_KEPT, ai.outcome());
        SubmissionGradingState state = stored();
        assertEquals(0.9, state.effectiveGrade());
        assertEquals("Teacher feedback", state.effectiveFeedback());
        assertEquals(0.5, state.aiGrade());
        assertEquals("AI feedback", state.aiFeedback());
        assertEquals(GradeSource.MANUAL, state.source());
        assertEquals(List.of(SubmissionGradeEventEntity.Type.SUBMITTED, SubmissionGradeEventEntity.Type.MANUAL_OVERRIDE_SET,
                SubmissionGradeEventEntity.Type.AI_GRADED), history());
        assertFalse(gradingService.history(submission.getId()).get(2).getEffective());
    }

    @Test
    void aStudentWhoRevisesWhileTheAiIsRunningNeverGetsTheOldGrade() throws Exception {
        CountDownLatch aiStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        aiBlocksUntil(aiStarted, release, aiGrade(0.5, "Feedback on v1"));

        CompletableFuture<GradingResult> aiRequest = CompletableFuture.supplyAsync(() -> aiGrader.grade(fresh()));
        assertTrue(aiStarted.await(10, TimeUnit.SECONDS));
        GradingResult revision = gradingService.revise(fresh(), content("https://example.com/v2"), null);
        release.countDown();

        assertEquals(GradingOutcome.REVISION_CREATED, revision.outcome());
        assertEquals(GradingOutcome.STALE_REVISION, aiRequest.get(10, TimeUnit.SECONDS).outcome());
        SubmissionGradingState state = stored();
        assertEquals(2, state.revision());
        assertNull(state.effectiveGrade());
        assertNull(state.aiGrade());
        assertNull(state.aiFeedback());
        assertEquals(content("https://example.com/v2"), fresh().getContent());
    }

    @Test
    void manyConcurrentAiResultsAndOneTeacherGradeAlwaysEndWithTheTeachersGrade() throws Exception {
        when(aiGradingService.grade(any())).thenReturn(aiGrade(0.3, "AI feedback"));
        List<CompletableFuture<GradingResult>> aiRequests = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            aiRequests.add(CompletableFuture.supplyAsync(() -> aiGrader.grade(fresh())));
        }
        GradingResult teacher = gradingService.applyManualGrade(submission.getId(), niko, 1, 0.95, "Teacher feedback");
        for (CompletableFuture<GradingResult> request : aiRequests) {
            request.get(20, TimeUnit.SECONDS);
        }

        assertEquals(GradingOutcome.MANUAL_GRADE_APPLIED, teacher.outcome());
        assertEquals(0.95, stored().effectiveGrade());
        assertEquals(0.3, stored().aiGrade());
        assertTrue(stored().manualOverride());
    }

    // ---- the agreed workflow ----

    @Test
    void aiGradeIsEffectiveUntilOverriddenAndAgainOnceTheOverrideIsCleared() {
        assertEquals(GradingOutcome.AI_GRADE_APPLIED,
                gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "AI feedback")).outcome());
        assertEquals(GradeSource.AI, stored().source());
        assertEquals("AI feedback", stored().effectiveFeedback());

        gradingService.applyManualGrade(submission.getId(), niko, 1, 0.8, "Teacher feedback");
        assertEquals(0.8, stored().effectiveGrade());
        assertEquals(0.6, stored().aiGrade());

        assertEquals(GradingOutcome.MANUAL_OVERRIDE_KEPT,
                gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.65, "Re-graded")).outcome());
        assertEquals(0.8, stored().effectiveGrade());
        assertEquals(0.65, stored().aiGrade());

        assertEquals(GradingOutcome.MANUAL_OVERRIDE_CLEARED, gradingService.clearManualOverride(submission.getId(), niko, 1).outcome());
        SubmissionGradingState state = stored();
        assertEquals(0.65, state.effectiveGrade());
        assertEquals("Re-graded", state.effectiveFeedback());
        assertNull(state.manualGrade());
        assertFalse(state.manualOverride());
        assertFalse(state.needsAiRegrade());
    }

    @Test
    void aRevisionKeepsTheManualGradeAndTheAiGradesTheNewContentForReference() {
        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "Feedback on v1"));
        gradingService.applyManualGrade(submission.getId(), niko, 1, 0.9, "Teacher feedback on v1");

        assertEquals(GradingOutcome.REVISION_CREATED,
                gradingService.revise(fresh(), content("https://example.com/v2"), hop).outcome());
        SubmissionGradingState afterRevision = stored();
        assertEquals(2, afterRevision.revision());
        assertEquals(GradeSource.MANUAL, afterRevision.source());
        assertEquals(0.9, afterRevision.effectiveGrade());
        assertEquals("Teacher feedback on v1", afterRevision.effectiveFeedback());
        assertNull(afterRevision.aiGrade(), "the AI grade was for the old content");

        assertEquals(GradingOutcome.STALE_REVISION,
                gradingService.applyManualGrade(submission.getId(), niko, 1, 0.1, "late").outcome());
        assertEquals(GradingOutcome.MANUAL_OVERRIDE_KEPT,
                gradingService.recordAiGrade(submission.getId(), 2, aiGrade(0.4, "Feedback on v2")).outcome());
        assertEquals(0.9, stored().effectiveGrade());
        assertEquals(0.4, stored().aiGrade());

        List<SubmissionGradeEventEntity> events = gradingService.history(submission.getId());
        assertEquals(List.of(SubmissionGradeEventEntity.Type.SUBMITTED, SubmissionGradeEventEntity.Type.AI_GRADED,
                SubmissionGradeEventEntity.Type.MANUAL_OVERRIDE_SET, SubmissionGradeEventEntity.Type.REVISED,
                SubmissionGradeEventEntity.Type.MANUAL_OVERRIDE_KEPT_BY_REVISION,
                SubmissionGradeEventEntity.Type.AI_GRADED), history());
        assertEquals(content("https://example.com/v1"), events.get(0).getContent());
        assertEquals(content("https://example.com/v2"), events.get(3).getContent());
        assertEquals(2, events.get(4).getRevision());
        assertEquals(0.9, events.get(4).getGrade());
        assertEquals(hop.getId(), events.get(4).getActorId());
        assertFalse(events.get(5).getEffective());

        assertEquals(GradingOutcome.MANUAL_OVERRIDE_CLEARED,
                gradingService.clearManualOverride(submission.getId(), niko, 2).outcome());
        assertEquals(0.4, stored().effectiveGrade(), "the teacher can still switch to the new revision's AI grade");
    }

    @Test
    void resubmittingFromTheSubmitPageKeepsTheManualGradeOnTheSameSubmission() {
        gradingService.applyManualGrade(submission.getId(), niko, 1, 0.5, "Missing part 2");
        AssignmentSubmission draft = new AssignmentSubmission(assignment, hop, content("https://example.com/v2"),
                "Added part 2", false);

        AssignmentSubmissionHistoryService.SubmitResult resubmitted = historyService.submit(draft, hop);

        assertTrue(resubmitted.replaced());
        assertEquals(submission.getId(), resubmitted.submission().getId());
        assertEquals(GradingOutcome.REVISION_CREATED, resubmitted.result().outcome());
        SubmissionGradingState state = stored();
        assertEquals(2, state.revision());
        assertEquals(GradeSource.MANUAL, state.source());
        assertEquals(0.5, state.effectiveGrade());
        List<AssignmentSubmissionHistoryService.Version> versions =
                historyService.historyOf(fresh()).versions();
        assertEquals("https://example.com/v2", versions.get(0).content().get("url"));
        assertEquals("https://example.com/v1", versions.get(1).content().get("url"));
        assertEquals(0.5, versions.get(1).grade());
    }

    @Test
    void aRevisionWithoutAManualGradeStartsUngraded() {
        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "Feedback on v1"));

        gradingService.revise(fresh(), content("https://example.com/v2"), hop);

        SubmissionGradingState state = stored();
        assertEquals(GradeSource.NONE, state.source());
        assertNull(state.effectiveGrade());
        assertNull(state.gradedAt());
        assertEquals(List.of(SubmissionGradeEventEntity.Type.SUBMITTED, SubmissionGradeEventEntity.Type.AI_GRADED,
                SubmissionGradeEventEntity.Type.REVISED), history());
    }

    @Test
    void editingOnlyTheCommentKeepsTheOverride() {
        gradingService.applyManualGrade(submission.getId(), niko, 1, 0.9, "Teacher feedback");
        AssignmentSubmission edit = fresh();
        edit.setComment("typo fixed");

        assertEquals(GradingOutcome.CONTENT_UNCHANGED, gradingService.revise(edit, content("https://example.com/v1"), null).outcome());
        assertEquals(0.9, stored().effectiveGrade());
        assertEquals("typo fixed", fresh().getComment());
    }

    @Test
    void anOverriddenSubmissionNeverEntersTheAiQueue() {
        gradingService.applyManualGrade(submission.getId(), niko, 1, 0.9, null);

        assertFalse(gradingService.flagForAiRegrade(submission.getId(), 1));
        assertFalse(stored().needsAiRegrade());
    }

    @Test
    void clearingAnOverrideTheAiNeverGradedQueuesIt() {
        gradingService.applyManualGrade(submission.getId(), niko, 1, 0.9, null);

        gradingService.clearManualOverride(submission.getId(), niko, null);

        assertNull(stored().effectiveGrade());
        assertTrue(stored().needsAiRegrade());
    }

    @Test
    void aSummaryNeverChangesTheGrade() {
        gradingService.applyManualGrade(submission.getId(), niko, 1, 0.9, "Teacher feedback");

        gradingService.saveAiSummary(submission.getId(), "Summary", 4);
        gradingService.saveAiSummary(submission.getId(), "Summary 2", null);

        assertEquals(0.9, stored().effectiveGrade());
        assertEquals("Summary 2", stored().aiFeedback());
        assertEquals(4, stored().qualityScore());
    }

    @Test
    void feedbackIsStoredInFull() {
        String longFeedback = "Detailed feedback. ".repeat(300);

        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, longFeedback));

        assertEquals(longFeedback, stored().effectiveFeedback());
        assertEquals(longFeedback, stored().aiFeedback());
    }

    // ---- schema ----

    @Test
    void theTableInitializersRunCleanlyOnTheEntitySchemaAndAreIdempotent() {
        List<Runnable> initializers = List.of(
                new AssignmentTableInitializer(jdbcTemplate)::initialize,
                new AssignmentSubmissionTableInitializer(jdbcTemplate)::initialize,
                new SubmissionGradeEventTableInitializer(jdbcTemplate)::initialize,
                new GradeReviewTableInitializer(jdbcTemplate)::initialize);
        initializers.forEach(Runnable::run);
        initializers.forEach(Runnable::run);

        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "AI feedback"));
        assertEquals(0.6, stored().effectiveGrade());
        assertEquals(2, eventRepo.findBySubmissionIdOrderByIdAsc(submission.getId()).size());
    }

    // ---- lifecycle: student teachers before close, finalized and transferred at close ----

    private double gradebookGrade(Person student) {
        return gradebookRepo.findByAssignmentIdAndStudentId(assignment.getId(), student.getId())
                .map(SynergyGrade::getGrade).orElseThrow();
    }

    @Test
    void aStudentTeacherCanOverrideAndAskForARegradeBeforeCloseButNotCloseOrReview() {
        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "AI feedback"));

        assertEquals(GradingOutcome.MANUAL_GRADE_APPLIED,
                gradingService.applyManualGrade(submission.getId(), lex, 1, 0.7, "Peer review").outcome());
        assertTrue(gradingService.canManage(submission.getId(), lex), "a student teacher may request an AI re-grade");
        assertFalse(gradingService.canManage(submission.getId(), hop), "the submitter can't grade their own work");
        assertEquals(GradingOutcome.NOT_PERMITTED, closureService.close(assignment.getId(), lex).outcome());

        closureService.close(assignment.getId(), niko);
        assertEquals(GradingOutcome.NOT_PERMITTED,
                reviewService.open(submission.getId(), lex, GradeReviewEntity.Reason.REGRADE_REQUEST, null).outcome(),
                "reviews are sensitive and teacher-only");
    }

    @Test
    void closingFinalizesEveryGradeAndCopiesItToTheGradebook() {
        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "AI feedback"));
        gradingService.applyManualGrade(submission.getId(), niko, 1, 0.85, "Teacher feedback");
        AssignmentSubmission ungraded = submit(lex, "https://example.com/lex");
        gradingService.flagForAiRegrade(ungraded.getId(), 1);

        AssignmentClosureService.Result result = closureService.close(assignment.getId(), niko);

        assertEquals(GradingOutcome.ASSIGNMENT_FINALIZED, result.outcome());
        assertEquals(1, result.summary().graded());
        assertEquals(1, result.summary().ungraded());
        assertEquals(1, result.summary().transferred());
        assertEquals(0.85, gradebookGrade(hop));
        assertTrue(gradebookRepo.findByAssignmentIdAndStudentId(assignment.getId(), lex.getId()).isEmpty());
        assertTrue(stored().closed());
        assertFalse(submissionRepo.findGradingState(ungraded.getId()).orElseThrow().needsAiRegrade(),
                "a closed assignment leaves the AI queue");
        assertEquals(SubmissionGradeEventEntity.Type.FINALIZED, history().get(history().size() - 1));
        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED, closureService.close(assignment.getId(), niko).outcome());
    }

    @Test
    void afterCloseNoOrdinaryPathChangesAGrade() {
        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "AI feedback"));
        closureService.close(assignment.getId(), niko);

        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED,
                gradingService.revise(fresh(), content("https://example.com/v2"), hop).outcome());
        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED,
                gradingService.applyManualGrade(submission.getId(), niko, null, 0.9, null).outcome());
        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED,
                gradingService.applyManualGrade(submission.getId(), lex, null, 0.9, null).outcome());
        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED,
                gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.2, "Late AI")).outcome());
        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED, aiGrader.grade(fresh()).outcome());
        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED, gradingService.submit(
                new AssignmentSubmission(assignment, hop, content("https://example.com/late"), "late", true), hop).outcome());

        assertEquals(0.6, stored().effectiveGrade());
        assertEquals(1, stored().revision());
        assertEquals(content("https://example.com/v1"), fresh().getContent());
        assertEquals(1, submissionRepo.findByAssignmentId(assignment.getId()).size());
        assertEquals(0.6, gradebookGrade(hop));
    }

    @Test
    void anAiResultThatArrivesAfterCloseIsDiscarded() throws Exception {
        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "AI feedback"));
        CountDownLatch aiStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        aiBlocksUntil(aiStarted, release, aiGrade(0.1, "Late AI"));

        CompletableFuture<GradingResult> aiRequest = CompletableFuture.supplyAsync(() -> aiGrader.grade(fresh()));
        assertTrue(aiStarted.await(10, TimeUnit.SECONDS));
        assertEquals(GradingOutcome.ASSIGNMENT_FINALIZED, closureService.close(assignment.getId(), niko).outcome());
        release.countDown();

        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED, aiRequest.get(10, TimeUnit.SECONDS).outcome());
        assertEquals(0.6, stored().effectiveGrade());
        assertEquals(0.6, stored().aiGrade());
        assertEquals(0.6, gradebookGrade(hop));
    }

    @Test
    void submissionsRacingACloseAreEitherFinalizedOrRefusedNeverLeftOpen() throws Exception {
        List<CompletableFuture<GradingOutcome>> submits = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String url = "https://example.com/race-" + i;
            submits.add(CompletableFuture.supplyAsync(() -> gradingService.submit(
                    new AssignmentSubmission(assignment, hop, content(url), "race", false), hop).outcome()));
        }
        AssignmentClosureService.Result closed = closureService.close(assignment.getId(), niko);
        for (CompletableFuture<GradingOutcome> submit : submits) {
            GradingOutcome outcome = submit.get(20, TimeUnit.SECONDS);
            assertTrue(outcome == GradingOutcome.REVISION_CREATED || outcome == GradingOutcome.ASSIGNMENT_CLOSED, outcome.name());
        }

        List<AssignmentSubmission> all = submissionRepo.findByAssignmentId(assignment.getId());
        assertEquals(closed.summary().graded() + closed.summary().ungraded(), all.size(),
                "every submission that exists was there when the assignment closed");
        for (AssignmentSubmission each : all) {
            assertTrue(gradingService.history(each.getId()).stream()
                    .anyMatch(event -> event.getType() == SubmissionGradeEventEntity.Type.FINALIZED), "finalized " + each.getId());
        }
    }

    // ---- student petitions ----

    @Test
    void aStudentPetitionsAKeptManualGradeBeforeCloseAndTheTeacherAdjustsIt() {
        gradingService.applyManualGrade(submission.getId(), niko, 1, 0.5, "Missing part 2");
        gradingService.revise(fresh(), content("https://example.com/v2"), hop);

        assertEquals(GradingOutcome.REASON_REQUIRED, reviewService.petition(submission.getId(), hop, " ").outcome());
        assertEquals(GradingOutcome.NOT_PERMITTED,
                reviewService.petition(submission.getId(), lex, "Not my work").outcome());
        GradeReviewService.ReviewResult filed = reviewService.petition(submission.getId(), hop, "  v2 adds part 2  ");
        assertEquals(GradingOutcome.REVIEW_OPENED, filed.outcome());
        GradeReviewEntity petition = filed.review();
        assertEquals(GradeReviewEntity.Reason.PETITION, petition.getReason());
        assertEquals("v2 adds part 2", petition.getPetitionMessage());
        assertEquals(hop.getId(), petition.getOpenedById());
        assertEquals(0.5, petition.getGradeAtOpen());
        assertEquals(GradingOutcome.REVIEW_ALREADY_OPEN,
                reviewService.petition(submission.getId(), hop, "Again").outcome());

        assertEquals(1, reviewService.petitionsOf(submission.getId(), hop).orElseThrow().size());
        assertTrue(reviewService.petitionsOf(submission.getId(), lex).isEmpty());
        assertEquals(List.of(petition.getId()), reviewService.openReviewsOf(assignment.getId(), niko).orElseThrow()
                .stream().map(GradeReviewEntity::getId).toList());
        assertTrue(reviewService.openReviewsOf(assignment.getId(), hop).isEmpty());
        assertEquals(GradingOutcome.NOT_PERMITTED, reviewService.decide(petition.getId(), hop,
                GradeReviewEntity.Decision.ADJUST, "I deserve it", 1.0, null).outcome());

        GradeReviewService.ReviewResult decided = reviewService.decide(petition.getId(), niko,
                GradeReviewEntity.Decision.ADJUST, "Part 2 is there in v2", 0.9, "Regraded v2");

        assertEquals(GradingOutcome.MANUAL_GRADE_APPLIED, decided.outcome());
        assertEquals(0.9, stored().effectiveGrade());
        assertEquals(GradeSource.MANUAL, stored().source());
        assertFalse(reviewRepo.findById(petition.getId()).orElseThrow().isOpen());
        assertTrue(gradebookRepo.findByAssignmentIdAndStudentId(assignment.getId(), hop.getId()).isEmpty(),
                "the gradebook is written at close, not before");
        SubmissionGradeEventEntity last = gradingService.history(submission.getId()).get(history().size() - 1);
        assertEquals(SubmissionGradeEventEntity.Type.MANUAL_OVERRIDE_SET, last.getType());
        assertEquals(petition.getId(), last.getReviewId());
        assertEquals(2, last.getRevision());
        assertTrue(reviewService.openReviewsOf(assignment.getId(), niko).orElseThrow().isEmpty());
    }

    @Test
    void beforeCloseOnlyAManualGradeCanBePetitioned() {
        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "AI feedback"));

        assertEquals(GradingOutcome.NO_MANUAL_OVERRIDE,
                reviewService.petition(submission.getId(), hop, "Please look again").outcome());
        assertTrue(reviewRepo.findBySubmissionIdOrderByIdAsc(submission.getId()).isEmpty());
    }

    @Test
    void afterCloseAnyGradeCanBePetitionedAndTheTeacherCanKeepIt() {
        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "AI feedback"));
        closureService.close(assignment.getId(), niko);

        GradeReviewService.ReviewResult filed = reviewService.petition(submission.getId(), hop, "Part 2 was there");
        assertEquals(GradingOutcome.REVIEW_OPENED, filed.outcome());

        assertEquals(GradingOutcome.REVIEW_NO_CHANGE, reviewService.decide(filed.review().getId(), niko,
                GradeReviewEntity.Decision.NO_CHANGE, "Part 2 doesn't meet the rubric", null, null).outcome());
        assertEquals(0.6, stored().effectiveGrade());
        assertEquals(GradeReviewEntity.Decision.NO_CHANGE,
                reviewService.petitionsOf(submission.getId(), hop).orElseThrow().get(0).getDecision());
    }

    @Test
    void aPetitionStillOpenAtCloseIsDecidedAsAnAuthorizedCorrection() {
        gradingService.applyManualGrade(submission.getId(), niko, 1, 0.5, null);
        Long petitionId = reviewService.petition(submission.getId(), hop, "Rubric says 0.8").review().getId();
        closureService.close(assignment.getId(), niko);

        assertEquals(GradingOutcome.GRADE_CORRECTED, reviewService.decide(petitionId, niko,
                GradeReviewEntity.Decision.ADJUST, "Agreed", 0.8, null).outcome());
        assertEquals(0.8, gradebookGrade(hop));
        assertEquals(SubmissionGradeEventEntity.Type.AUTHORIZED_CORRECTION, history().get(history().size() - 1));
    }

    // ---- teacher review after close ----

    @Test
    void aReviewNeedsAClosedAssignment() {
        assertEquals(GradingOutcome.REVIEW_REQUIRES_CLOSE,
                reviewService.open(submission.getId(), niko, GradeReviewEntity.Reason.PETITION, null).outcome());
    }

    @Test
    void aPetitionApprovedByTheTeacherCorrectsTheFinalGradeWithARecordedRationale() {
        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "AI feedback"));
        closureService.close(assignment.getId(), niko);

        GradeReviewService.ReviewResult opened = reviewService.open(submission.getId(), niko,
                GradeReviewEntity.Reason.PETITION, "Student says part 2 was graded against the wrong rubric");
        assertEquals(GradingOutcome.REVIEW_OPENED, opened.outcome());
        Long reviewId = opened.review().getId();
        assertEquals(GradingOutcome.REVIEW_ALREADY_OPEN,
                reviewService.open(submission.getId(), niko, GradeReviewEntity.Reason.REGRADE_REQUEST, null).outcome());

        assertEquals(GradingOutcome.RATIONALE_REQUIRED,
                reviewService.decide(reviewId, niko, GradeReviewEntity.Decision.ADJUST, " ", 0.9, null).outcome());
        assertEquals(GradingOutcome.INVALID_GRADE,
                reviewService.decide(reviewId, niko, GradeReviewEntity.Decision.ADJUST, "Rubric misapplied", 5.0, null).outcome());
        assertTrue(reviewRepo.findById(reviewId).orElseThrow().isOpen(), "a refused decision leaves the review open");

        GradeReviewService.ReviewResult decided = reviewService.decide(reviewId, niko,
                GradeReviewEntity.Decision.ADJUST, "Rubric misapplied to part 2", 0.9, "Corrected after petition");

        assertEquals(GradingOutcome.GRADE_CORRECTED, decided.outcome());
        assertEquals(0.9, stored().effectiveGrade());
        assertEquals(GradeSource.MANUAL, stored().source());
        assertEquals(0.9, gradebookGrade(hop));
        GradeReviewEntity review = reviewRepo.findById(reviewId).orElseThrow();
        assertEquals(GradeReviewEntity.Decision.ADJUST, review.getDecision());
        assertEquals("Rubric misapplied to part 2", review.getRationale());
        assertEquals(0.6, review.getGradeAtOpen());
        SubmissionGradeEventEntity correction = gradingService.history(submission.getId()).stream()
                .filter(event -> event.getType() == SubmissionGradeEventEntity.Type.AUTHORIZED_CORRECTION).findFirst().orElseThrow();
        assertEquals(reviewId, correction.getReviewId());
        assertEquals(niko.getId(), correction.getActorId());
        assertEquals(GradingOutcome.REVIEW_NOT_OPEN,
                reviewService.decide(reviewId, niko, GradeReviewEntity.Decision.NO_CHANGE, "Again", null, null).outcome());
        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED,
                gradingService.applyManualGrade(submission.getId(), niko, null, 0.1, null).outcome(),
                "the correction didn't reopen anything");
    }

    @Test
    void aSimilarityCheckCollectsEvidenceAndCanEndWithNoChange() {
        AssignmentSubmission copy = submit(lex, "HTTPS://www.example.com/v1/");
        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "AI feedback"));
        closureService.close(assignment.getId(), niko);
        Long reviewId = reviewService.open(submission.getId(), niko, GradeReviewEntity.Reason.SIMILARITY_CHECK, null)
                .review().getId();

        GradeReviewService.ReviewResult similar = reviewService.collectSimilarity(reviewId, niko);
        assertEquals(GradingOutcome.EVIDENCE_RECORDED, similar.outcome());
        assertTrue(similar.review().getSimilarSubmissions().contains("\"submissionId\":" + copy.getId()));
        assertTrue(similar.review().getSimilarSubmissions().contains("SAME_LINK"));
        assertEquals(GradingOutcome.EVIDENCE_RECORDED,
                reviewService.recordEvidence(reviewId, niko, "Spoke with both students; independent work").outcome());

        GradeReviewService.ReviewResult decided = reviewService.decide(reviewId, niko,
                GradeReviewEntity.Decision.NO_CHANGE, "Same starter link from the lesson, not copying", null, null);

        assertEquals(GradingOutcome.REVIEW_NO_CHANGE, decided.outcome());
        assertEquals(0.6, stored().effectiveGrade());
        assertEquals(0.6, gradebookGrade(hop));
        assertEquals(SubmissionGradeEventEntity.Type.REVIEW_NO_CHANGE, history().get(history().size() - 1));
    }

    @Test
    void anAiReEvaluationIsEvidenceOnlyAndNeverChangesTheFinalGrade() throws Exception {
        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "AI feedback"));
        closureService.close(assignment.getId(), niko);
        Long reviewId = reviewService.open(submission.getId(), niko, GradeReviewEntity.Reason.REGRADE_REQUEST, null)
                .review().getId();
        when(aiGradingService.grade(any())).thenReturn(aiGrade(0.8, "Re-evaluated"));

        GradeReviewService.ReviewResult evaluated = aiGrader.evaluateForReview(reviewId, niko);

        assertEquals(GradingOutcome.EVIDENCE_RECORDED, evaluated.outcome());
        assertEquals(0.8, reviewRepo.findById(reviewId).orElseThrow().getAiGrade());
        assertEquals(0.6, stored().effectiveGrade());
        assertEquals(0.6, stored().aiGrade());
        assertEquals(GradingOutcome.NOT_PERMITTED, aiGrader.evaluateForReview(reviewId, lex).outcome());
    }

    @Test
    void twoTeachersDecidingTheSameReviewAtOnceProduceOneDecision() throws Exception {
        gradingService.recordAiGrade(submission.getId(), 1, aiGrade(0.6, "AI feedback"));
        closureService.close(assignment.getId(), niko);
        Long reviewId = reviewService.open(submission.getId(), niko, GradeReviewEntity.Reason.PETITION, null).review().getId();

        CompletableFuture<GradingOutcome> adjust = CompletableFuture.supplyAsync(() -> reviewService.decide(reviewId,
                niko, GradeReviewEntity.Decision.ADJUST, "Approve", 0.9, null).outcome());
        CompletableFuture<GradingOutcome> keep = CompletableFuture.supplyAsync(() -> reviewService.decide(reviewId,
                niko, GradeReviewEntity.Decision.NO_CHANGE, "Deny", null, null).outcome());
        List<GradingOutcome> outcomes = List.of(adjust.get(20, TimeUnit.SECONDS), keep.get(20, TimeUnit.SECONDS));

        assertEquals(1, outcomes.stream().filter(outcome -> outcome == GradingOutcome.REVIEW_NOT_OPEN).count(), outcomes.toString());
        GradeReviewEntity review = reviewRepo.findById(reviewId).orElseThrow();
        double expected = review.getDecision() == GradeReviewEntity.Decision.ADJUST ? 0.9 : 0.6;
        assertEquals(expected, stored().effectiveGrade());
        assertEquals(expected, gradebookGrade(hop));
    }
}
