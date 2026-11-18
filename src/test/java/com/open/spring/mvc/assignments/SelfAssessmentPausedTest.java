package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.S3uploads.FileHandler;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Self-assessment is paused: submissions without the five self-assessment fields
 * must be accepted, and any values that are sent must still be saved.
 */
class SelfAssessmentPausedTest {

    @Mock
    private AssignmentSubmissionJPA submissionRepo;

    @Mock
    private AssignmentJpaRepository assignmentRepo;

    @Mock
    private PersonJpaRepository personRepo;

    @Mock
    private AssignmentAiGradingService aiGradingService;

    @Mock
    private FileHandler fileHandler;

    @Mock
    private SubmissionGradeEventJpaRepository eventRepo;

    private AssignmentSubmissionAPIController controller;
    private AssignmentSubmissionFileApiController fileController;

    private Person student;
    private UserDetails studentDetails;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        controller = new AssignmentSubmissionAPIController();
        ReflectionTestUtils.setField(controller, "submissionRepo", submissionRepo);
        ReflectionTestUtils.setField(controller, "assignmentRepo", assignmentRepo);
        ReflectionTestUtils.setField(controller, "personRepo", personRepo);
        SubmissionGradingService gradingService = new SubmissionGradingService(submissionRepo, assignmentRepo, eventRepo,
                new AssignmentAuthorizationService());
        SubmissionAiGrader aiGrader = new SubmissionAiGrader(aiGradingService, gradingService, Mockito.mock(GradeReviewService.class));
        when(assignmentRepo.lockIfOpen(any())).thenReturn(1); // every assignment here is open
        ReflectionTestUtils.setField(controller, "gradingService", gradingService);
        ReflectionTestUtils.setField(controller, "aiGrader", aiGrader);
        ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());

        AssignmentSubmissionHistoryService historyService = new AssignmentSubmissionHistoryService(submissionRepo, gradingService,
                org.mockito.Mockito.mock(com.open.spring.mvc.lessonfeedback.LessonFeedbackService.class));
        ReflectionTestUtils.setField(controller, "historyService", historyService);
        AssignmentSubmissionUploadService uploadService = new AssignmentSubmissionUploadService(
                fileHandler, personRepo, assignmentRepo, historyService, aiGrader);
        fileController = new AssignmentSubmissionFileApiController(uploadService);

        student = student(1L, "toby");
        studentDetails = User.withUsername("toby").password("ignored").authorities("ROLE_STUDENT").build();
        when(personRepo.findById(1L)).thenReturn(Optional.of(student));
        when(personRepo.findByUid("toby")).thenReturn(student);

        when(submissionRepo.save(any(AssignmentSubmission.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(aiGradingService.grade(any(AssignmentSubmission.class)))
                .thenReturn(AssignmentAiGradingService.GradeResult.notGradeable("not gradeable in tests"));
        when(fileHandler.uploadFile(anyString(), anyString(), anyString())).thenReturn("stored.ipynb");
    }

    private Assignment linkAssignment() {
        Assignment linkAssignment = new Assignment("link-hw", "desc", "", 1.0, "10/25/2026", "link");
        linkAssignment.setId(20L);
        when(assignmentRepo.findById(20L)).thenReturn(Optional.of(linkAssignment));
        return linkAssignment;
    }

    private AssignmentSubmissionAPIController.SubmitAssignmentDto linkDto() {
        Map<String, Object> content = new HashMap<>();
        content.put("type", "link");
        content.put("url", "https://example.com/my-work");
        AssignmentSubmissionAPIController.SubmitAssignmentDto dto = new AssignmentSubmissionAPIController.SubmitAssignmentDto();
        dto.submitterId = 1L;
        dto.isGroup = false;
        dto.content = content;
        dto.comment = "done";
        dto.isLate = false;
        return dto;
    }

    private AssignmentSubmission savedSubmission() {
        ArgumentCaptor<AssignmentSubmission> captor = ArgumentCaptor.forClass(AssignmentSubmission.class);
        Mockito.verify(submissionRepo, Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().get(0);
    }

    @Test
    void jsonSubmissionWithNullSelfAssessmentIsCreated() {
        linkAssignment();
        // Pages sends the five fields as null; a fresh DTO leaves them null too.
        ResponseEntity<?> response = controller.submitAssignment(20L, linkDto(), studentDetails);

        assertEquals(201, response.getStatusCode().value());
        AssignmentSubmission saved = savedSubmission();
        assertNull(saved.getTechnicalExcellence());
        assertNull(saved.getCommunication());
        assertNull(saved.getWorkHabits());
        assertNull(saved.getAiOrchestration());
        assertNull(saved.getSelfAssessmentReflection());
    }

    @Test
    void jsonSubmissionStillSavesSelfAssessmentWhenSent() {
        linkAssignment();
        AssignmentSubmissionAPIController.SubmitAssignmentDto dto = linkDto();
        dto.technicalExcellence = 4;
        dto.communication = 3;
        dto.workHabits = 5;
        dto.aiOrchestration = 2;
        dto.selfAssessmentReflection = "went well";

        ResponseEntity<?> response = controller.submitAssignment(20L, dto, studentDetails);

        assertEquals(201, response.getStatusCode().value());
        AssignmentSubmission saved = savedSubmission();
        assertEquals(4, saved.getTechnicalExcellence());
        assertEquals(3, saved.getCommunication());
        assertEquals(5, saved.getWorkHabits());
        assertEquals(2, saved.getAiOrchestration());
        assertEquals("went well", saved.getSelfAssessmentReflection());
    }

    @Test
    void jsonSubmissionAsAnotherStudentIsStillForbidden() {
        linkAssignment();
        Person other = student(2L, "mallory");
        when(personRepo.findByUid("mallory")).thenReturn(other);
        UserDetails otherDetails = User.withUsername("mallory").password("ignored").authorities("ROLE_STUDENT").build();

        // Pausing self-assessment must not skip the author check that runs before it.
        ResponseEntity<?> response = controller.submitAssignment(20L, linkDto(), otherDetails);

        assertEquals(403, response.getStatusCode().value());
        Mockito.verify(submissionRepo, Mockito.never()).save(any(AssignmentSubmission.class));
    }

    @Test
    void fileUploadWithoutSelfAssessmentFieldsSucceeds() {
        Assignment fileAssignment = assignment(30L, "notebook-hw");
        when(assignmentRepo.findById(30L)).thenReturn(Optional.of(fileAssignment));
        MockMultipartFile file = new MockMultipartFile(
                "file", "hw.ipynb", "application/x-ipynb+json", "{\"cells\":[]}".getBytes());

        // Multipart uploads omit the fields entirely, so Spring binds them as null.
        ResponseEntity<?> response = fileController.uploadAssignmentSubmission(
                "notebook-hw", 30L, 1L, "toby", file, "notes",
                null, null, null, null, null, studentDetails);

        assertEquals(200, response.getStatusCode().value());
        AssignmentSubmission saved = savedSubmission();
        assertNull(saved.getTechnicalExcellence());
        assertNull(saved.getSelfAssessmentReflection());
    }
}
