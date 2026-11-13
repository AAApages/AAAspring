package com.open.spring.mvc.assignments;

import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.S3uploads.FileHandler;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.groups.Submitter;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * REST API Controller for managing assignment submissions.
 * Provides endpoints for CRUD operations on assignment submissions.
 */
@RestController
@RequestMapping("/api/submissions")
public class AssignmentSubmissionAPIController {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private static final String MANAGE_DENIED_MESSAGE =
            "You do not have permission to manage this submission";

    @Autowired
    private AssignmentSubmissionJPA submissionRepo;

    @Autowired
    private AssignmentJpaRepository assignmentRepo;

    @Autowired
    private PersonJpaRepository personRepo;

    @Autowired
    private GroupsJpaRepository groupRepo;

    @Autowired
    private FileHandler fileHandler;

    @Autowired
    private SubmissionGradingService gradingService;

    @Autowired
    private SubmissionAiGrader aiGrader;

    @Autowired
    private AssignmentAiRegradeService aiRegradeService;

    @Autowired
    private AssignmentAuthorizationService assignmentAuthorizationService;

    @Autowired
    private AssignmentSubmissionHistoryService historyService;
    
    /**
     * A DTO class for returning only necessary assignment submission details.
     */
    @Getter
    @Setter
    public static class AssignmentReturnDto {
        public Long id;
        public String name;
        public String type;
        public String description;
        public Double points;
        public String dueDate;
        public String timestamp;

        public AssignmentReturnDto(Assignment assignment) {
            this.id = assignment.getId();
            this.name = assignment.getName();
            this.type = assignment.getType();
            this.description = assignment.getDescription();
            this.points = assignment.getPoints();
            this.dueDate = assignment.getDueDate();
            this.timestamp = assignment.getTimestamp();
        }
    }

    /**
     * Get all submissions for a specific student.
     * 
     * @param studentId the ID of the student whose submissions are to be fetched
     * @return a ResponseEntity containing a list of submissions for the given student ID
     */
    @Transactional
    @GetMapping("/getSubmissions/{studentId}")
    public ResponseEntity<?> getSubmissions(@PathVariable Long studentId) {
        if (personRepo.findById(studentId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Student not found");
        }

        List<AssignmentSubmissionReturnDto> dtos = Stream.concat(
            submissionRepo.findBySubmitterId(studentId).stream(),
            groupRepo.findGroupsByPersonId(studentId).stream()
                .flatMap(group -> submissionRepo.findBySubmitterId(group.getId()).stream())
        )
        .map(AssignmentSubmissionReturnDto::new)
        .toList();

        return ResponseEntity.ok(dtos);
    }

    /**
     * A DTO class with the format for the JSON when submitting assignments.
     */
    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    public static class SubmitAssignmentDto {
        public Long submitterId;
        public Boolean isGroup;
        public Map<String, Object> content;
        public String comment;
        public Boolean isLate;
        public Integer technicalExcellence;
        public Integer communication;
        public Integer workHabits;
        public Integer aiOrchestration;
        public String selfAssessmentReflection;
    }

    /**
     * A POST endpoint to submit an assignment.
     * @param assignmentId The ID of the assignment being submitted.
     * @param studentId The ID of the student submitting the assignment.
     * @param content The content of the student's submission.
     * @return The saved submission, if it successfully submitted.
     */
    @PostMapping("/submit/{assignmentId}")
    public ResponseEntity<?> submitAssignment(
        @PathVariable Long assignmentId,
        @RequestBody SubmitAssignmentDto submissionInfo,
        @AuthenticationPrincipal UserDetails userDetails
    ) {
        Assignment assignment = assignmentRepo.findById(assignmentId).orElse(null);
        
        // TODO: A better way to do this would be to have this be part of some sort of SubmitterService
        Submitter submitter;
        if (Boolean.TRUE.equals(submissionInfo.isGroup)) {
            submitter = groupRepo.findById(submissionInfo.submitterId).orElse(null);
        } else {
            submitter = personRepo.findById(submissionInfo.submitterId).orElse(null);
        }
        
        if (submitter == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Submitter not found");
            return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
        }

        ResponseEntity<?> authorError = validateSubmissionAuthor(userDetails, submitter);
        if (authorError != null) return authorError;

        // Self-assessment is paused (Pages no longer sends it); uncomment this block to require it again.
        // String selfAssessmentError = AssignmentSubmission.validateSelfAssessment(
        //         submissionInfo.technicalExcellence,
        //         submissionInfo.communication,
        //         submissionInfo.workHabits,
        //         submissionInfo.aiOrchestration,
        //         submissionInfo.selfAssessmentReflection);
        // if (selfAssessmentError != null) {
        //     return new ResponseEntity<>(Map.of("error", selfAssessmentError), HttpStatus.BAD_REQUEST);
        // }

        if (assignment != null) {
            if (!hasSubmissionType(submissionInfo.content, assignment)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Submission content type does not match the assignment type"));
            }
            String linkValidationError = validateLinkContent(submissionInfo.content);
            if (linkValidationError != null) {
                return ResponseEntity.badRequest().body(Map.of("error", linkValidationError));
            }
            AssignmentSubmission submission = new AssignmentSubmission(assignment, submitter, submissionInfo.content, submissionInfo.comment, Boolean.TRUE.equals(submissionInfo.isLate));
            submission.setTechnicalExcellence(submissionInfo.technicalExcellence);
            submission.setCommunication(submissionInfo.communication);
            submission.setWorkHabits(submissionInfo.workHabits);
            submission.setAiOrchestration(submissionInfo.aiOrchestration);
            submission.setSelfAssessmentReflection(submissionInfo.selfAssessmentReflection);
            return saveAndGrade(submission, getAuthenticatedPerson(userDetails));
        }
        Map<String, String> error = new HashMap<>();
        error.put("error", "Assignment not found");
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @Getter
    @Setter
    public static class SubmissionRequestDto {
        public Long assignmentId;
        public Long submitterId;
        public Boolean isGroupSubmission;
        public Map<String, Object> content;
        public String comment;
        public Boolean isLate;
        public Integer technicalExcellence;
        public Integer communication;
        public Integer workHabits;
        public Integer aiOrchestration;
        public String selfAssessmentReflection;
    }

    /**
     * Submit an assignment for a student.
     * 
     * @param assignmentId the ID of the assignment being submitted
     * @param studentId    the ID of the student submitting the assignment
     * @param content      the content of the submission
     * @param comment      any comments related to the submission
     * @return a ResponseEntity containing the created submission or an error if the assignment is not found
     */
    @PostMapping("/{assignmentId}")
    public ResponseEntity<?> submitAssignment(
            @PathVariable Long assignmentId,
            @RequestBody SubmissionRequestDto requestData,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        Long resolvedAssignmentId = requestData.assignmentId != null ? requestData.assignmentId : assignmentId;
        Assignment assignment = assignmentRepo.findById(resolvedAssignmentId).orElse(null);

        if (assignment != null) {
            boolean isGroupSubmission = Boolean.TRUE.equals(requestData.isGroupSubmission);

            if (requestData.submitterId == null) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Submitter not found");
                return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
            }

            Submitter submitter;
            if (isGroupSubmission) {
                submitter = groupRepo.findById(requestData.submitterId).orElse(null);
            } else {
                submitter = personRepo.findById(requestData.submitterId).orElse(null);
            }

            if (submitter == null) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Submitter not found");
                return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
            }

            ResponseEntity<?> authorError = validateSubmissionAuthor(userDetails, submitter);
            if (authorError != null) return authorError;

            if (!hasSubmissionType(requestData.content, assignment)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Submission content type does not match the assignment type"));
            }
            String linkValidationError = validateLinkContent(requestData.content);
            if (linkValidationError != null) {
                return ResponseEntity.badRequest().body(Map.of("error", linkValidationError));
            }

            AssignmentSubmission submission = new AssignmentSubmission(assignment, submitter, requestData.content, requestData.comment, Boolean.TRUE.equals(requestData.isLate));
            submission.setTechnicalExcellence(requestData.technicalExcellence);
            submission.setCommunication(requestData.communication);
            submission.setWorkHabits(requestData.workHabits);
            submission.setAiOrchestration(requestData.aiOrchestration);
            submission.setSelfAssessmentReflection(requestData.selfAssessmentReflection);
            return saveAndGrade(submission, getAuthenticatedPerson(userDetails));
        }
        Map<String, String> error = new HashMap<>();
        error.put("error", "Assignment not found");
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    /**
     * Edits a submission; new content becomes a new revision and is re-graded. Not transactional,
     * so no transaction is open during the AI call.
     */
    @PutMapping(value = "/{submissionId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> updateSubmission(
            @PathVariable Long submissionId,
            @RequestParam String contentType,
            @RequestParam(required = false) String url,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) String comment,
            @RequestParam(required = false) Boolean isLate,
            @RequestParam(required = false) MultipartFile file,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }

        Person currentUser = personRepo.findByUid(userDetails.getUsername());
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authenticated user not found"));
        }

        AssignmentSubmission submission = submissionRepo.findById(submissionId).orElse(null);
        if (submission == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Submission not found"));
        }

        if (!canEditSubmission(currentUser, submission)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You can only edit your own submissions"));
        }
        loadAssignment(submission);

        String normalizedContentType = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> updatedContent;

        // The submissions page edits every URL-based submission as "link"; store it under the
        // assignment's own URL type (github_issue/code) so it validates and grades like the original.
        String assignmentType = submission.getAssignment() == null || submission.getAssignment().getAssignmentType() == null
                ? "" : submission.getAssignment().getAssignmentType().trim().toLowerCase(Locale.ROOT);
        if ("link".equals(normalizedContentType) && ("github_issue".equals(assignmentType) || "code".equals(assignmentType))) {
            normalizedContentType = assignmentType;
        }

        if (!isAllowedSubmissionType(submission.getAssignment(), normalizedContentType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Submission content type does not match the assignment type"));
        }

        if (List.of("link", "github_issue", "code").contains(normalizedContentType)) {
            if (url == null || url.trim().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "A submission URL is required for link submissions"));
            }

            updatedContent = new HashMap<>();
            updatedContent.put("type", normalizedContentType);
            updatedContent.put("url", url.trim());
            updatedContent.put("notes", notes == null ? "" : notes);
            String linkValidationError = validateLinkContent(updatedContent);
            if (linkValidationError != null) {
                return ResponseEntity.badRequest().body(Map.of("error", linkValidationError));
            }
        } else if ("file".equals(normalizedContentType)) {
            updatedContent = updateFileSubmissionContent(submission, currentUser, file, notes);
            if (updatedContent == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Failed to update file submission"));
            }
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "contentType must be either link or file"));
        }

        submission.setComment(comment == null ? "" : comment);
        submission.setIsLate(Boolean.TRUE.equals(isLate));

        GradingResult revision = gradingService.revise(submission, updatedContent, currentUser);
        if (revision.outcome() == GradingOutcome.REVISION_CREATED) {
            aiGrader.autoGrade(submission);
        } else if (revision.outcome() != GradingOutcome.CONTENT_UNCHANGED) {
            return GradingResponse.from(revision);
        }
        return ResponseEntity.ok(new AssignmentSubmissionReturnDto(submission));
    }

    /**
     * Sets a manual grade (manual override).
     *
     * @param grade    0 to the assignment's points
     * @param revision the revision the grader saw; a newer one gets STALE_REVISION. Optional.
     */
    @PostMapping("/grade/{submissionId}")
    public ResponseEntity<?> gradeSubmission(
            @PathVariable Long submissionId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam Double grade,
            @RequestParam(required = false) String feedback,
            @RequestParam(required = false) Integer revision
    ) {
        Person currentUser = getAuthenticatedPerson(userDetails);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }
        return GradingResponse.from(gradingService.applyManualGrade(submissionId, currentUser, revision, grade, feedback));
    }

    /** Removes a manual override so the AI grade is effective again. */
    @DeleteMapping("/grade/{submissionId}")
    public ResponseEntity<?> clearManualGrade(
            @PathVariable Long submissionId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) Integer revision
    ) {
        Person currentUser = getAuthenticatedPerson(userDetails);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }
        return GradingResponse.from(gradingService.clearManualOverride(submissionId, currentUser, revision));
    }

    /**
     * Every version of a submission: the current one first, then earlier revisions and any older
     * rows for the same assignment (from before resubmits became revisions). Given an older row,
     * answers for the current submission. Same access as the grading history.
     */
    @GetMapping("/{submissionId}/history")
    @Transactional
    public ResponseEntity<?> getSubmissionHistory(
            @PathVariable Long submissionId,
            @AuthenticationPrincipal UserDetails userDetails) {
        Person currentUser = getAuthenticatedPerson(userDetails);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }

        AssignmentSubmission submission = submissionRepo.findById(submissionId).orElse(null);
        if (submission == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Submission not found"));
        }
        AssignmentSubmission current = historyService.currentOf(submission);
        if (!canManageSubmission(currentUser, current) && !canEditSubmission(currentUser, current)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", MANAGE_DENIED_MESSAGE));
        }
        return ResponseEntity.ok(historyService.historyOf(current));
    }

    /** Names one earlier version from the history: its submissionId, and its revision (null for an older row). */
    public record RestoreRequest(Long submissionId, Integer revision) {
    }

    /**
     * Restores an earlier version: its content is submitted again as the next revision of the
     * current submission (like a revert commit), so nothing in the history is lost and any manual
     * grade carries over. Same access as editing; refused once the assignment is closed. Not
     * transactional, so no transaction is open during the AI re-grade.
     */
    @PostMapping("/{submissionId}/history/restore")
    public ResponseEntity<?> restoreSubmissionVersion(
            @PathVariable Long submissionId,
            @RequestBody RestoreRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Person currentUser = getAuthenticatedPerson(userDetails);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }
        AssignmentSubmission submission = submissionRepo.findById(submissionId).orElse(null);
        if (submission == null || request == null) {
            return GradingResponse.from(GradingResult.notFound());
        }
        AssignmentSubmission current = historyService.currentOf(submission);
        if (!canEditSubmission(currentUser, current)) {
            return GradingResponse.from(GradingResult.notPermitted());
        }
        Map<String, Object> content = historyService
                .earlierVersionContent(current.getId(), request.submissionId(), request.revision()).orElse(null);
        if (content == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "That version is not in this submission's history"));
        }
        loadAssignment(current);
        // The same rules as a submit or edit: the assignment's type (or its link rules) may have
        // changed since that version was submitted.
        if (!hasSubmissionType(content, current.getAssignment())) {
            Assignment assignment = current.getAssignment();
            return ResponseEntity.badRequest().body(Map.of("error", "That version is a " + content.get("type")
                    + " submission, but this assignment now takes "
                    + (assignment == null ? "no submissions" : assignment.getAssignmentType()) + "; submit new work instead"));
        }
        String linkError = validateLinkContent(content);
        if (linkError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", linkError));
        }
        GradingResult result = gradingService.revise(current, new HashMap<>(content), currentUser);
        if (result.outcome() == GradingOutcome.REVISION_CREATED) {
            aiGrader.autoGrade(current);
        }
        return GradingResponse.from(result);
    }

    /** Grading history, oldest first, for the submission's graders and its submitter. */
    @GetMapping("/{submissionId}/grading-history")
    @Transactional
    public ResponseEntity<?> getGradingHistory(
            @PathVariable Long submissionId,
            @AuthenticationPrincipal UserDetails userDetails) {
        Person currentUser = getAuthenticatedPerson(userDetails);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }

        AssignmentSubmission submission = submissionRepo.findById(submissionId).orElse(null);
        if (submission == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Submission not found"));
        }
        if (!canManageSubmission(currentUser, submission) && !canEditSubmission(currentUser, submission)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", MANAGE_DENIED_MESSAGE));
        }

        List<SubmissionGradeEventEntity> events = gradingService.history(submissionId);
        Set<Long> actorIds = events.stream().map(SubmissionGradeEventEntity::getActorId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> actorNames = personRepo.findAllById(actorIds).stream()
                .collect(Collectors.toMap(Person::getId, Person::getName, (first, second) -> first));
        return ResponseEntity.ok(new GradingHistoryResponse(
                SubmissionGradingState.of(submission),
                events.stream().map(event -> GradeEventView.of(event, actorNames.get(event.getActorId()))).toList()));
    }

    public record GradingHistoryResponse(SubmissionGradingState current, List<GradeEventView> events) {
    }

    /** actorName is null for the AI or a deleted account. */
    public record GradeEventView(Long id, Integer revision, SubmissionGradeEventEntity.Type type, Double grade,
            String feedback, Boolean effective, Long actorId, String actorName, Map<String, Object> content,
            LocalDateTime createdAt) {
        static GradeEventView of(SubmissionGradeEventEntity event, String actorName) {
            return new GradeEventView(event.getId(), event.getRevision(), event.getType(), event.getGrade(),
                    event.getFeedback(), event.getEffective(), event.getActorId(), actorName, event.getContent(),
                    event.getCreatedAt());
        }
    }

    /**
     * Starts re-grading every submission whose grade is above its assignment's points (e.g. 4/1
     * left over from the old 2-4 scale). Runs in the background, one attempt per submission;
     * poll the GET mapping for progress.
     */
    @PostMapping("/fix-out-of-range-grades")
    public ResponseEntity<?> fixOutOfRangeGrades(@AuthenticationPrincipal UserDetails userDetails) {
        ResponseEntity<?> denied = requireTeacherOrAdmin(userDetails);
        return denied != null ? denied : ResponseEntity.ok(aiRegradeService.startFixOutOfRangeGrades());
    }

    @GetMapping("/fix-out-of-range-grades")
    public ResponseEntity<?> fixOutOfRangeGradesStatus(@AuthenticationPrincipal UserDetails userDetails) {
        ResponseEntity<?> denied = requireTeacherOrAdmin(userDetails);
        return denied != null ? denied : ResponseEntity.ok(aiRegradeService.getFixStatus());
    }

    /**
     * The AI grading queue with ETAs. Teachers/admins see every queued submission; anyone else
     * only sees their own (or their groups') submissions, plus the total queue length.
     */
    @GetMapping("/ai-queue")
    public ResponseEntity<?> aiQueue(@AuthenticationPrincipal UserDetails userDetails) {
        Person currentUser = getAuthenticatedPerson(userDetails);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        AssignmentAiRegradeService.QueueSnapshot snapshot = aiRegradeService.queueSnapshot();
        List<AssignmentAiRegradeService.QueueEntry> entries = snapshot.entries();
        if (!assignmentAuthorizationService.isTeacherOrAdmin(currentUser)) {
            Set<Long> submitterIds = new HashSet<>();
            submitterIds.add(currentUser.getId());
            groupRepo.findGroupsByPersonId(currentUser.getId()).forEach(group -> submitterIds.add(group.getId()));
            entries = entries.stream().filter(entry -> submitterIds.contains(entry.submitterId())).toList();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("running", snapshot.running());
        body.put("nextRunInSeconds", snapshot.nextRunInSeconds());
        body.put("secondsPerGrade", snapshot.secondsPerGrade());
        body.put("queueLength", snapshot.entries().size());
        body.put("entries", entries);
        return ResponseEntity.ok(body);
    }

    /** Skip the queue: AI-grade one queued submission now (teacher/admin; for testing). */
    @PostMapping("/ai-queue/{submissionId}/grade-now")
    public ResponseEntity<?> gradeQueuedNow(@PathVariable Long submissionId,
            @AuthenticationPrincipal UserDetails userDetails) {
        ResponseEntity<?> denied = requireTeacherOrAdmin(userDetails);
        if (denied != null) {
            return denied;
        }
        AssignmentAiRegradeService.GradeNowResult result = aiRegradeService.gradeNow(submissionId);
        HttpStatus status = switch (result.status()) {
            case "graded" -> HttpStatus.OK;
            case "busy", "not_queued" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(result);
    }

    /** Start a full AI queue run now instead of waiting for the timer (teacher/admin). */
    @PostMapping("/ai-queue/run-now")
    public ResponseEntity<?> runAiQueueNow(@AuthenticationPrincipal UserDetails userDetails) {
        ResponseEntity<?> denied = requireTeacherOrAdmin(userDetails);
        if (denied != null) {
            return denied;
        }
        return aiRegradeService.runQueueNow()
                ? ResponseEntity.accepted().body(Map.of("status", "started"))
                : ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "A grading run is already in progress."));
    }

    /** Null when allowed; otherwise the 401/403 response. AI grading calls a metered API. */
    private ResponseEntity<?> requireTeacherOrAdmin(UserDetails userDetails) {
        Person currentUser = getAuthenticatedPerson(userDetails);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        if (!assignmentAuthorizationService.isTeacherOrAdmin(currentUser)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin or teacher access required"));
        }
        return null;
    }

    /**
     * AI re-grade on request, for anyone who may grade the submission, until the assignment
     * closes. Not transactional, so no transaction is open during the AI call.
     */
    @PostMapping("/ai-grade/{submissionId}")
    public ResponseEntity<?> aiGradeSubmission(
            @PathVariable Long submissionId,
            @AuthenticationPrincipal UserDetails userDetails) {
        Person currentUser = getAuthenticatedPerson(userDetails);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        AssignmentSubmission submission = submissionRepo.findById(submissionId).orElse(null);
        if (submission == null) {
            return GradingResponse.from(GradingResult.notFound());
        }
        if (!gradingService.canManage(submissionId, currentUser)) {
            return GradingResponse.from(GradingResult.notPermitted());
        }
        loadAssignment(submission);
        return GradingResponse.from(aiGrader.grade(submission));
    }

    /**
     * Persist an AI-generated summary for a submission (admin/teacher only).
     *
     * @param submissionId the ID of the submission being summarized
     * @param summary      the generated summary text
     * @return a ResponseEntity indicating success or an error if the submission is not found
     */
    @PostMapping("/summary/{submissionId}")
    @Transactional
    public ResponseEntity<?> saveSubmissionSummary(
            @PathVariable Long submissionId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam String summary,
            @RequestParam(required = false) Integer qualityScore
    ) {
        Person currentUser = getAuthenticatedPerson(userDetails);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }

        AssignmentSubmission submission = submissionRepo.findById(submissionId).orElse(null);
        if (submission == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Submission not found"));
        }
        if (!canManageSubmission(currentUser, submission)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", MANAGE_DENIED_MESSAGE));
        }

        Integer validQualityScore = qualityScore != null && qualityScore >= 1 && qualityScore <= 5 ? qualityScore : null;
        gradingService.saveAiSummary(submissionId, summary, validQualityScore)
                .ifPresent(state -> state.copyTo(submission));
        return ResponseEntity.ok(new AssignmentSubmissionReturnDto(submission));
    }

    /**
     * Delete an existing assignment submission.
     *
     * @param submissionId the ID of the submission to delete
     * @param userDetails the authenticated user
     * @return a success or error response
     */
    @DeleteMapping("/{submissionId}")
    @Transactional
    public ResponseEntity<?> deleteSubmission(
            @PathVariable Long submissionId,
            @AuthenticationPrincipal UserDetails userDetails) {
        Person currentUser = getAuthenticatedPerson(userDetails);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }

        AssignmentSubmission submission = submissionRepo.findById(submissionId).orElse(null);
        if (submission == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Submission not found"));
        }
        if (!canManageSubmission(currentUser, submission)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", MANAGE_DENIED_MESSAGE));
        }

        submissionRepo.delete(submission);
        return ResponseEntity.ok(Map.of("message", "Submission deleted successfully"));
    }

    /**
     * Get all submissions for a specific assignment.
     * 
     * @param assignmentId the ID of the assignment whose submissions are to be fetched
     * @return a ResponseEntity containing a list of submissions or an error if the assignment is not found
     */
    @Transactional
    @GetMapping("/assignment/{assignmentId}")
    public ResponseEntity<?> getSubmissionsByAssignment(@PathVariable Long assignmentId,
                                                       @AuthenticationPrincipal UserDetails userDetails) {
        String uid = userDetails.getUsername();
        Person user = personRepo.findByUid(uid);
        if (user == null) {
            logger.error("User not found with email: {}", uid);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found with uid: " + uid);
        }

        Assignment assignment = assignmentRepo.findById(assignmentId).orElse(null);
        if (assignment == null) {
            return new ResponseEntity<>(
                Collections.singletonMap("error", "Assignment not found"), 
                HttpStatus.NOT_FOUND
            );
        }

        List<AssignmentSubmission> submissions = submissionRepo.findByAssignmentId(assignmentId);
        List<AssignmentSubmissionReturnDto> submissionsReturn;

        if (!(user.hasRoleWithName("ROLE_TEACHER") || user.hasRoleWithName("ROLE_ADMIN"))) {
            // if they aren't a teacher or admin, only let them see submissions they are assigned to grade
            submissionsReturn = submissions.stream()
                .filter(submission -> submission.getAssignedGraders().contains(user))
                .map(AssignmentSubmissionReturnDto::new)
                .collect(Collectors.toList());
        } else {
            submissionsReturn = submissions.stream()
                .map(AssignmentSubmissionReturnDto::new)
                .collect(Collectors.toList());
        }
    
        return new ResponseEntity<>(submissionsReturn, HttpStatus.OK);
    }

    /**
     * Assign persons as graders to a specific submission.
     * @param id the ID of the submission to which graders are being assigned
     * @param personIds a list of person IDs to be assigned as graders
     * @return a ResponseEntity indicating success or failure
     */
    @PostMapping("/{id}/assigned-graders")
    public ResponseEntity<?> assignGradersToSubmission(@PathVariable Long id, @RequestBody List<Long> personIds) {
        Optional<AssignmentSubmission> submissionOptional = submissionRepo.findById(id);
        if (!submissionOptional.isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Submission not found");
        }

        AssignmentSubmission submission = submissionOptional.get();
        List<Person> persons = personRepo.findAllById(personIds);

        submission.setAssignedGraders(persons);

        submissionRepo.save(submission);
        return ResponseEntity.ok("Persons assigned successfully");
    }

    /**
     * Get the IDs of persons assigned as graders for a specific submission.
     * 
     * @param id the ID of the submission whose assigned graders are to be fetched
     * @return a ResponseEntity containing a list of assigned grader IDs or an error if the submission is not found
     */
    @GetMapping("/{id}/assigned-graders")
    @Transactional
    public ResponseEntity<?> getAssignedGraders(@PathVariable Long id) {
        Optional<AssignmentSubmission> submissionOptional = submissionRepo.findById(id);
        if (!submissionOptional.isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Submission not found");
        }

        AssignmentSubmission submission = submissionOptional.get();
        List<Person> assignedGraders = submission.getAssignedGraders();
        
        // Return just the IDs of assigned persons
        List<Long> assignedGraderIds = assignedGraders.stream()
            .map(Person::getId)
            .collect(Collectors.toList());
            
        return ResponseEntity.ok(assignedGraderIds);
    }

    /**
     * Extract all submissions for a specific assignment.
     * 
     * @param assignmentId the ID of the assignment whose submissions are to be extracted
     * @return a ResponseEntity containing a list of all submissions for the assignment
     */
    @GetMapping("/extract/{assignmentId}")
    @Transactional
    public ResponseEntity<?> extractSubmissionsByAssignment(@PathVariable Long assignmentId) {
        Assignment assignment = assignmentRepo.findById(assignmentId).orElse(null);
        if (assignment == null) {
            return new ResponseEntity<>(
                Collections.singletonMap("error", "Assignment not found"), 
                HttpStatus.NOT_FOUND
            );
        }

        List<AssignmentSubmission> submissions = submissionRepo.findByAssignmentId(assignmentId);
        List<AssignmentSubmissionReturnDto> submissionDtos = submissions.stream()
            .map(AssignmentSubmissionReturnDto::new)
            .collect(Collectors.toList());

        return new ResponseEntity<>(submissionDtos, HttpStatus.OK);
    }

    private boolean canEditSubmission(Person currentUser, AssignmentSubmission submission) {
        if (currentUser.hasRoleWithName("ROLE_ADMIN") || currentUser.hasRoleWithName("ROLE_TEACHER")) {
            return true;
        }

        Submitter submitter = submission.getSubmitter();
        if (submitter == null) {
            return false;
        }

        if (submitter instanceof Person personSubmitter) {
            return Objects.equals(personSubmitter.getId(), currentUser.getId());
        }

        return groupRepo.findGroupsByPersonId(currentUser.getId()).stream()
                .anyMatch(group -> Objects.equals(group.getId(), submitter.getId()));
    }

    /**
     * Teachers and admins keep their existing reach; a creator may only manage submissions
     * belonging to an assignment they own; a grader assigned to the whole assignment or to
     * this specific submission may manage it too. The denial message is deliberately generic
     * so a failed attempt never confirms anything about another assignment's submissions.
     */
    private boolean canManageSubmission(Person currentUser, AssignmentSubmission submission) {
        return assignmentAuthorizationService.canManageSubmission(currentUser, submission);
    }

    private boolean hasSubmissionType(Map<String, Object> content, Assignment assignment) {
        if (content == null) {
            return false;
        }
        Object contentType = content.get("type");
        return contentType != null && isAllowedSubmissionType(assignment, String.valueOf(contentType));
    }

    private boolean isAllowedSubmissionType(Assignment assignment, String contentType) {
        return assignment != null
                && assignment.getAssignmentType() != null
                && contentType != null
                && assignment.getAssignmentType().trim().equalsIgnoreCase(contentType.trim());
    }

    /**
     * Rejects github_issue/code submissions whose url isn't actually a GitHub issue
     * or Gist link, so a bad link is caught at submit time rather than only surfacing
     * later as "not gradeable" once auto-grading runs.
     */
    private String validateLinkContent(Map<String, Object> content) {
        if (content == null) {
            return null;
        }
        String type = String.valueOf(content.getOrDefault("type", "")).trim();
        String url = String.valueOf(content.getOrDefault("url", "")).trim();
        if ("github_issue".equalsIgnoreCase(type)
                && !AssignmentAiGradingService.isGithubIssueUrl(url)
                && !AssignmentAiGradingService.isGithubBlobUrl(url)) {
            return "A github_issue submission must be a public GitHub issue or file link";
        }
        // "link" is deliberately unrestricted — any URL is accepted. It just isn't
        // AI-gradable unless it happens to be a GitHub issue/file link, which
        // AssignmentAiGradingService already handles gracefully (notGradeable, not an error).
        if ("code".equalsIgnoreCase(type) && !AssignmentAiGradingService.isGistUrl(url)) {
            return "A code submission must include a valid Gist link";
        }
        return null;
    }

    /** Payload IDs must not let another student consume the submission author's personal AI quota. */
    private ResponseEntity<?> validateSubmissionAuthor(UserDetails userDetails, Submitter submitter) {
        Person currentUser = getAuthenticatedPerson(userDetails);
        if (currentUser == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "Authentication required"));
        if (assignmentAuthorizationService.isTeacherOrAdmin(currentUser)) return null;
        boolean ownsIdentity = submitter instanceof Person person
            && Objects.equals(person.getId(), currentUser.getId());
        // Queried, not group.getGroupMembers(): that lazy list can't load here (no session open).
        boolean groupMember = submitter instanceof Groups group && groupRepo.findGroupsByPersonId(currentUser.getId())
            .stream().anyMatch(memberOf -> Objects.equals(memberOf.getId(), group.getId()));
        if (!ownsIdentity && !groupMember) return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("error", "You can only submit as yourself or a group you belong to"));
        return null;
    }

    /**
     * Replaces the lazy assignment proxy with the loaded assignment, for use outside a transaction.
     */
    private void loadAssignment(AssignmentSubmission submission) {
        if (submission.getAssignment() != null) {
            assignmentRepo.findById(submission.getAssignment().getId()).ifPresent(submission::setAssignment);
        }
    }

    /**
     * Saves a new submission, or makes it the next revision of the submitter's existing one for the
     * assignment, then AI-grades the new revision. 201 when new, 200 when it revised an earlier one.
     */
    private ResponseEntity<?> saveAndGrade(AssignmentSubmission submission, Person submittedBy) {
        AssignmentSubmissionHistoryService.SubmitResult saved = historyService.submit(submission, submittedBy);
        GradingOutcome outcome = saved.result().outcome();
        if (outcome == GradingOutcome.REVISION_CREATED) {
            aiGrader.autoGrade(saved.submission());
        } else if (outcome != GradingOutcome.CONTENT_UNCHANGED) {
            return GradingResponse.from(saved.result()); // e.g. the assignment is closed
        }
        return new ResponseEntity<>(new AssignmentSubmissionReturnDto(saved.submission()),
                saved.replaced() ? HttpStatus.OK : HttpStatus.CREATED);
    }

    private Person getAuthenticatedPerson(UserDetails userDetails) {
        if (userDetails == null) {
            return null;
        }
        return personRepo.findByUid(userDetails.getUsername());
    }

    private Map<String, Object> updateFileSubmissionContent(
            AssignmentSubmission submission,
            Person currentUser,
            MultipartFile file,
            String notes) {

        Map<String, Object> existingContent = submission.getContent() != null
                ? submission.getContent()
                : Collections.emptyMap();

        String updatedNotes = notes != null ? notes : (String) existingContent.getOrDefault("notes", "");

        if (file == null || file.isEmpty()) {
            Map<String, Object> updatedContent = new HashMap<>(existingContent);
            updatedContent.put("type", "file");
            updatedContent.put("notes", updatedNotes);
            return updatedContent;
        }

        if (submission.getAssignment() == null) {
            return null;
        }

        String originalFilename = sanitizeFilename(file);
        String storageUid = resolveStorageUid(submission.getSubmitter());
        String s3Filename = buildS3Filename(submission.getAssignment().getName(), originalFilename);
        String base64Data = toBase64(file);
        String storedFilename = fileHandler.uploadFile(base64Data, s3Filename, storageUid);

        if (storedFilename == null) {
            return null;
        }

        Map<String, Object> updatedContent = new HashMap<>();
        updatedContent.put("type", "file");
        updatedContent.put("filename", originalFilename);
        updatedContent.put("storedFilename", storedFilename);
        updatedContent.put("storagePath", storageUid + "/" + s3Filename);
        updatedContent.put("contentType", file.getContentType());
        updatedContent.put("size", file.getSize());
        updatedContent.put("uploadedBy", currentUser.getUid());
        updatedContent.put("notes", updatedNotes);
        return updatedContent;
    }

    private String resolveStorageUid(Submitter submitter) {
        if (submitter instanceof Person personSubmitter) {
            return personSubmitter.getUid();
        }

        return submitter != null ? String.valueOf(submitter.getId()) : "submission";
    }

    private String sanitizeFilename(MultipartFile file) {
        String incomingName = file.getOriginalFilename() == null ? "submission.bin" : file.getOriginalFilename();
        return Paths.get(incomingName).getFileName().toString();
    }

    private String toBase64(MultipartFile file) {
        try {
            return Base64.getEncoder().encodeToString(file.getBytes());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Error processing submission file: " + e.getMessage());
        }
    }

    private String buildS3Filename(String assignmentName, String originalFilename) {
        return "assignment-submissions/"
                + slugify(assignmentName)
                + "/"
                + Instant.now().toEpochMilli()
                + "_"
                + UUID.randomUUID()
                + "_"
                + originalFilename;
    }

    private String slugify(String input) {
        String normalized = input == null ? "assignment" : input.toLowerCase(Locale.ROOT).trim();
        String slug = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "assignment" : slug;
    }
}
