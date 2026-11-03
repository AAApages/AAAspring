package com.open.spring.mvc.assignments;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.open.spring.mvc.S3uploads.FileHandler;
import com.open.spring.mvc.groups.Submitter;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

@Service
public class AssignmentSubmissionUploadService {

    private final FileHandler fileHandler;
    private final PersonJpaRepository personRepo;
    private final AssignmentJpaRepository assignmentRepo;
    private final AssignmentSubmissionHistoryService historyService;
    private final SubmissionAiGrader aiGrader;

    public AssignmentSubmissionUploadService(
            FileHandler fileHandler,
            PersonJpaRepository personRepo,
            AssignmentJpaRepository assignmentRepo,
            AssignmentSubmissionHistoryService historyService,
            SubmissionAiGrader aiGrader) {
        this.fileHandler = fileHandler;
        this.personRepo = personRepo;
        this.assignmentRepo = assignmentRepo;
        this.historyService = historyService;
        this.aiGrader = aiGrader;
    }

    public Map<String, Object> upload(
            String assignmentName,
            Long assignmentId,
            Long userId,
            String username,
            MultipartFile file,
            String notes,
            Integer technicalExcellence,
            Integer communication,
            Integer workHabits,
            Integer aiOrchestration,
            String selfAssessmentReflection,
            UserDetails userDetails) {

        validateAuthentication(userDetails);
        validateRequiredFields(userId, username, file);

        // Self-assessment is paused (Pages no longer sends it); uncomment this block to require it again.
        // String selfAssessmentError = AssignmentSubmission.validateSelfAssessment(
        //         technicalExcellence, communication, workHabits, aiOrchestration, selfAssessmentReflection);
        // if (selfAssessmentError != null) {
        //     throw new UploadException(HttpStatus.BAD_REQUEST, selfAssessmentError);
        // }

        Person authenticatedUser = getAuthenticatedUser(userDetails);
        Person targetUser = getTargetUser(userId);
        Assignment assignment = resolveAssignment(assignmentId, assignmentName);

        if (!isAllowedSubmissionType(assignment, "file")) {
            throw new UploadException(HttpStatus.BAD_REQUEST, "This assignment does not accept file submissions");
        }

        validateUsernameMatchesTarget(username, targetUser);
        validateSubmitPermission(authenticatedUser, userId);
        // Checked again when saving; checking now avoids uploading a file that would be refused.
        if (assignment.isClosed()) {
            throw new UploadException(GradingOutcome.ASSIGNMENT_CLOSED);
        }
        if (historyService.isLessonFeedbackMissing(authenticatedUser, assignment)) {
            throw new UploadException(GradingOutcome.LESSON_FEEDBACK_REQUIRED);
        }

        String originalFilename = sanitizeFilename(file);
        String s3Filename = buildS3Filename(assignment.getName(), originalFilename);
        String base64Data = toBase64(file);

        String storedFilename = uploadToStorage(base64Data, s3Filename, targetUser.getUid());

        AssignmentSubmission savedSubmission = saveSubmission(
            assignment,
            targetUser,
            notes,
            file,
            authenticatedUser,
            originalFilename,
            s3Filename,
            storedFilename,
            technicalExcellence,
            communication,
            workHabits,
            aiOrchestration,
            selfAssessmentReflection);

        // Best-effort: a grading failure never fails the upload; the revision is queued for a retry.
        aiGrader.autoGrade(savedSubmission);

        return buildResponse(
            assignment,
                notes,
                file,
                authenticatedUser,
                targetUser,
                originalFilename,
                s3Filename,
            storedFilename,
            savedSubmission);
    }

    private void validateAuthentication(UserDetails userDetails) {
        if (userDetails == null) {
            throw new UploadException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

    private void validateRequiredFields(Long userId, String username, MultipartFile file) {
        if (isBlank(username) || userId == null) {
            throw new UploadException(HttpStatus.BAD_REQUEST, "userId and username are required");
        }
        if (file == null || file.isEmpty()) {
            throw new UploadException(HttpStatus.BAD_REQUEST, "file is required");
        }
    }

    private Person getAuthenticatedUser(UserDetails userDetails) {
        Person authenticatedUser = personRepo.findByUid(userDetails.getUsername());
        if (authenticatedUser == null) {
            throw new UploadException(HttpStatus.UNAUTHORIZED, "Authenticated user not found");
        }
        return authenticatedUser;
    }

    private Person getTargetUser(Long userId) {
        Person targetUser = personRepo.findById(userId).orElse(null);
        if (targetUser == null) {
            throw new UploadException(HttpStatus.NOT_FOUND, "Target user not found for userId=" + userId);
        }
        return targetUser;
    }

    private Assignment resolveAssignment(Long assignmentId, String assignmentName) {
        if (assignmentId != null) {
            Assignment assignment = assignmentRepo.findById(assignmentId).orElse(null);
            if (assignment == null) {
                throw new UploadException(HttpStatus.NOT_FOUND, "Assignment not found with id: " + assignmentId);
            }
            return assignment;
        }

        if (isMissingAssignmentName(assignmentName)) {
            throw new UploadException(HttpStatus.BAD_REQUEST, "assignmentId or assignmentName is required");
        }

        Assignment assignment = assignmentRepo.findByName(assignmentName.trim());
        if (assignment == null) {
            throw new UploadException(HttpStatus.NOT_FOUND, "Assignment not found: " + assignmentName);
        }
        return assignment;
    }

    private void validateUsernameMatchesTarget(String username, Person targetUser) {
        boolean usernameMatchesUid = username.equalsIgnoreCase(targetUser.getUid());
        boolean usernameMatchesName = username.equalsIgnoreCase(targetUser.getName());
        if (!usernameMatchesUid && !usernameMatchesName) {
            throw new UploadException(HttpStatus.BAD_REQUEST, "username does not match the provided userId");
        }
    }

    private void validateSubmitPermission(Person authenticatedUser, Long userId) {
        boolean privileged = authenticatedUser.hasRoleWithName("ROLE_TEACHER")
                || authenticatedUser.hasRoleWithName("ROLE_ADMIN");
        if (!privileged && !Objects.equals(authenticatedUser.getId(), userId)) {
            throw new UploadException(HttpStatus.FORBIDDEN, "You can only submit assignments for your own account");
        }
    }

    private String sanitizeFilename(MultipartFile file) {
        String incomingName = file.getOriginalFilename() == null ? "submission.bin" : file.getOriginalFilename();
        return Paths.get(incomingName).getFileName().toString();
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

    private String toBase64(MultipartFile file) {
        try {
            return Base64.getEncoder().encodeToString(file.getBytes());
        } catch (Exception e) {
            throw new UploadException(HttpStatus.BAD_REQUEST, "Error processing submission file: " + e.getMessage());
        }
    }

    private String uploadToStorage(String base64Data, String s3Filename, String uid) {
        String storedFilename = fileHandler.uploadFile(base64Data, s3Filename, uid);
        if (storedFilename == null) {
            throw new UploadException(HttpStatus.INTERNAL_SERVER_ERROR, "Upload failed. Check S3 configuration and retry.");
        }
        return storedFilename;
    }

    private AssignmentSubmission saveSubmission(
            Assignment assignment,
            Person targetUser,
            String notes,
            MultipartFile file,
            Person authenticatedUser,
            String originalFilename,
            String s3Filename,
            String storedFilename,
            Integer technicalExcellence,
            Integer communication,
            Integer workHabits,
            Integer aiOrchestration,
            String selfAssessmentReflection) {

        Map<String, Object> content = new HashMap<>();
        content.put("type", assignment.getAssignmentType());
        content.put("filename", originalFilename);
        content.put("storedFilename", storedFilename);
        content.put("storagePath", targetUser.getUid() + "/" + s3Filename);
        content.put("contentType", file.getContentType());
        content.put("size", file.getSize());
        content.put("uploadedBy", authenticatedUser.getUid());
        content.put("notes", notes);

        Submitter submitter = targetUser;
        AssignmentSubmission submission = new AssignmentSubmission(
                assignment,
                submitter,
                content,
                notes == null ? "" : notes,
                false);
        submission.setTechnicalExcellence(technicalExcellence);
        submission.setCommunication(communication);
        submission.setWorkHabits(workHabits);
        submission.setAiOrchestration(aiOrchestration);
        submission.setSelfAssessmentReflection(selfAssessmentReflection);

        // A student's earlier submission for this assignment gets this upload as its next revision.
        AssignmentSubmissionHistoryService.SubmitResult submitted = historyService.submit(submission, authenticatedUser);
        if (submitted.result().outcome() != GradingOutcome.REVISION_CREATED) {
            throw new UploadException(submitted.result().outcome());
        }
        return submitted.submission();
    }

    private Map<String, Object> buildResponse(
            Assignment assignment,
            String notes,
            MultipartFile file,
            Person authenticatedUser,
            Person targetUser,
            String originalFilename,
            String s3Filename,
            String storedFilename,
            AssignmentSubmission savedSubmission) {

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Assignment submission uploaded successfully");
        response.put("submissionId", savedSubmission.getId());
        response.put("assignmentId", assignment.getId());
        response.put("assignmentName", assignment.getName());
        response.put("userId", targetUser.getId());
        response.put("username", targetUser.getUid());
        response.put("displayName", targetUser.getName());
        response.put("storedFilename", storedFilename);
        response.put("storagePath", targetUser.getUid() + "/" + s3Filename);
        response.put("originalFilename", originalFilename);
        response.put("contentType", file.getContentType());
        response.put("size", file.getSize());
        response.put("notes", notes);
        response.put("uploadedBy", authenticatedUser.getUid());
        if (savedSubmission.getGrade() != null) {
            response.put("grade", savedSubmission.getGrade());
            response.put("feedback", savedSubmission.getFeedback());
            response.put("qualityScore", savedSubmission.getQualityScore());
        }
        return response;
    }

    private String slugify(String input) {
        String normalized = input.toLowerCase(Locale.ROOT).trim();
        String slug = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "assignment" : slug;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isMissingAssignmentName(String value) {
        if (isBlank(value)) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "null".equals(normalized) || "undefined".equals(normalized);
    }

    private boolean isAllowedSubmissionType(Assignment assignment, String contentType) {
        return assignment != null
                && assignment.getAssignmentType() != null
                && contentType != null
                && assignment.getAssignmentType().trim().equalsIgnoreCase(contentType.trim());
    }

    public static class UploadException extends RuntimeException {
        private final HttpStatus status;
        private final GradingOutcome outcome;

        public UploadException(HttpStatus status, String message) {
            super(message);
            this.status = status;
            this.outcome = null;
        }

        /** An expected grading outcome, answered as a GradingResponse. */
        public UploadException(GradingOutcome outcome) {
            super(outcome.name());
            this.status = GradingResponse.statusOf(outcome);
            this.outcome = outcome;
        }

        public HttpStatus getStatus() {
            return status;
        }

        public GradingOutcome getOutcome() {
            return outcome;
        }
    }
}
