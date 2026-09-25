package com.open.spring.mvc.assignments;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private AssignmentAiGradingService aiGradingService;

    @Autowired
    private AssignmentAuthorizationService assignmentAuthorizationService;
    
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
        @RequestBody SubmitAssignmentDto submissionInfo
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

        String selfAssessmentError = AssignmentSubmission.validateSelfAssessment(
                submissionInfo.technicalExcellence,
                submissionInfo.communication,
                submissionInfo.workHabits,
                submissionInfo.aiOrchestration,
                submissionInfo.selfAssessmentReflection);
        if (selfAssessmentError != null) {
            return new ResponseEntity<>(Map.of("error", selfAssessmentError), HttpStatus.BAD_REQUEST);
        }

        if (assignment != null) {
            if (!hasSubmissionType(submissionInfo.content, assignment)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Submission content type does not match the assignment type"));
            }
            String linkValidationError = validateLinkContent(submissionInfo.content);
            if (linkValidationError != null) {
                return ResponseEntity.badRequest().body(Map.of("error", linkValidationError));
            }
            AssignmentSubmission submission = new AssignmentSubmission(assignment, submitter, submissionInfo.content, submissionInfo.comment, submissionInfo.isLate);
            submission.setTechnicalExcellence(submissionInfo.technicalExcellence);
            submission.setCommunication(submissionInfo.communication);
            submission.setWorkHabits(submissionInfo.workHabits);
            submission.setAiOrchestration(submissionInfo.aiOrchestration);
            submission.setSelfAssessmentReflection(submissionInfo.selfAssessmentReflection);
            AssignmentSubmission savedSubmission = submissionRepo.save(submission);
            savedSubmission = tryAutoGrade(savedSubmission);
            return new ResponseEntity<>(new AssignmentSubmissionReturnDto(savedSubmission), HttpStatus.CREATED);
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
            @RequestBody SubmissionRequestDto requestData
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

            if (!hasSubmissionType(requestData.content, assignment)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Submission content type does not match the assignment type"));
            }
            String linkValidationError = validateLinkContent(requestData.content);
            if (linkValidationError != null) {
                return ResponseEntity.badRequest().body(Map.of("error", linkValidationError));
            }

            AssignmentSubmission submission = new AssignmentSubmission(assignment, submitter, requestData.content, requestData.comment,requestData.isLate);
            submission.setTechnicalExcellence(requestData.technicalExcellence);
            submission.setCommunication(requestData.communication);
            submission.setWorkHabits(requestData.workHabits);
            submission.setAiOrchestration(requestData.aiOrchestration);
            submission.setSelfAssessmentReflection(requestData.selfAssessmentReflection);
            AssignmentSubmission savedSubmission = submissionRepo.save(submission);
            savedSubmission = tryAutoGrade(savedSubmission);
            return new ResponseEntity<>(new AssignmentSubmissionReturnDto(savedSubmission), HttpStatus.CREATED);
        }
        Map<String, String> error = new HashMap<>();
        error.put("error", "Assignment not found");
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @PutMapping(value = "/{submissionId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
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

        String normalizedContentType = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> updatedContent;

        if (!isAllowedSubmissionType(submission.getAssignment(), normalizedContentType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Submission content type does not match the assignment type"));
        }

        if ("link".equals(normalizedContentType)) {
            if (url == null || url.trim().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "A submission URL is required for link submissions"));
            }

            updatedContent = new HashMap<>();
            updatedContent.put("type", "link");
            updatedContent.put("url", url.trim());
            updatedContent.put("notes", notes == null ? "" : notes);
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

        // Preserve existing content map for change detection
        Map<String, Object> existingContent = submission.getContent() != null ? submission.getContent() : Collections.emptyMap();
        boolean contentChanged = !Objects.equals(existingContent, updatedContent);

        submission.setContent(updatedContent);
        submission.setComment(comment == null ? "" : comment);
        submission.setIsLate(Boolean.TRUE.equals(isLate));

        // Only clear grade/feedback/AI summary/quality score when the content actually changed
        if (contentChanged) {
            submission.setGrade(null);
            submission.setFeedback(null);
            submission.setAiSummary(null);
            submission.setQualityScore(null);
        }

        AssignmentSubmission savedSubmission = submissionRepo.save(submission);
        return ResponseEntity.ok(new AssignmentSubmissionReturnDto(savedSubmission));
    }
    
    /**
     * Grade an existing assignment submission.
     * 
     * @param submissionId the ID of the submission to be graded
     * @param grade        the grade to be assigned to the submission
     * @param feedback     optional feedback for the submission
     * @return a ResponseEntity indicating success or an error if the submission is not found
     */
    @PostMapping("/grade/{submissionId}")
    @Transactional
    public ResponseEntity<?> gradeSubmission(
            @PathVariable Long submissionId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam Double grade,
            @RequestParam(required = false) String feedback
    ) {
        Person currentUser = getAuthenticatedPerson(userDetails);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }

        AssignmentSubmission submission = submissionRepo.findById(submissionId).orElse(null);
        if (submission == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Submission not found");
            return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);    
        }
        if (!canManageSubmission(currentUser, submission)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", MANAGE_DENIED_MESSAGE));
        }

        // we have a correct submission
        submission.setGrade(grade);
        submission.setFeedback(feedback);
        AssignmentSubmission savedSubmission = submissionRepo.save(submission);
        return ResponseEntity.ok(new AssignmentSubmissionReturnDto(savedSubmission));
    }

    @PostMapping("/ai-grade/{submissionId}")
    @Transactional
    public ResponseEntity<?> aiGradeSubmission(
            @PathVariable Long submissionId,
            @AuthenticationPrincipal UserDetails userDetails) {
        Person currentUser = getAuthenticatedPerson(userDetails);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        // AI grading calls an external, metered API - deliberately kept to teacher/admin only,
        // narrower than canManageSubmission's teacher-or-creator reach for grading/deletion.
        if (!assignmentAuthorizationService.isTeacherOrAdmin(currentUser)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin or teacher access required"));
        }

        AssignmentSubmission submission = submissionRepo.findById(submissionId).orElse(null);
        if (submission == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Submission not found"));
        }

        try {
            AssignmentAiGradingService.GradeResult result = aiGradingService.grade(submission);
            if (!"graded".equals(result.status())) {
                return ResponseEntity.unprocessableEntity().body(result);
            }
            applyAiGradeResult(submission, result);
                AssignmentSubmission savedSubmission = submissionRepo.save(submission);
                return ResponseEntity.ok(new AiGradeResponse(
                    result.status(),
                    result.score(),
                    savedSubmission.getQualityScore(),
                    savedSubmission.getFeedback(),
                    new AssignmentSubmissionReturnDto(savedSubmission)));
        } catch (Exception e) {
            logger.error("AI grading failed for submission {}", submissionId, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("status", "failed", "message", "The AI grader could not complete this submission: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
        }
    }

                public record AiGradeResponse(
                    String status,
                    Integer score,
                    Integer qualityScore,
                    String feedback,
                    AssignmentSubmissionReturnDto submission) {
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

        submission.setAiSummary(summary);
        if (qualityScore != null && qualityScore >= 1 && qualityScore <= 5) {
            submission.setQualityScore(qualityScore);
        }
        AssignmentSubmission savedSubmission = submissionRepo.save(submission);
        return ResponseEntity.ok(new AssignmentSubmissionReturnDto(savedSubmission));
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
     * belonging to an assignment they own. The denial message is deliberately generic so a
     * failed attempt never confirms anything about another assignment's submissions.
     */
    private boolean canManageSubmission(Person currentUser, AssignmentSubmission submission) {
        return assignmentAuthorizationService.canManage(currentUser, submission.getAssignment());
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

    /**
     * Attempts to AI-grade a freshly created submission immediately, best-effort.
     * Grading failures never fail the submission itself — the student's work is
     * saved either way, ungraded if the AI grader can't produce a result.
     */
    private AssignmentSubmission tryAutoGrade(AssignmentSubmission submission) {
        try {
            AssignmentAiGradingService.GradeResult result = aiGradingService.grade(submission);
            if ("graded".equals(result.status())) {
                applyAiGradeResult(submission, result);
                return submissionRepo.save(submission);
            }
        } catch (Exception e) {
            logger.warn("Auto-grade failed for submission {}: {}", submission.getId(), e.getMessage());
        }
        return submission;
    }

    private void applyAiGradeResult(AssignmentSubmission submission, AssignmentAiGradingService.GradeResult result) {
        submission.setQualityScore(result.score());
        submission.setGrade(result.score().doubleValue());
        submission.setFeedback(result.feedback());
        submission.setAiSummary(result.feedback());
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