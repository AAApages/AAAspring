package com.open.spring.mvc.assignments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.open.spring.mvc.groups.Submitter;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

@RestController
@RequestMapping("/api/assignment-submission-view")
public class AssignmentSubmissionViewController {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentSubmissionViewController.class);

    @Autowired
    private AssignmentSubmissionJPA submissionRepo;

    @Autowired
    private PersonJpaRepository personRepo;

    @Autowired
    private AssignmentJpaRepository assignmentRepo;

    @Autowired
    private AssignmentAuthorizationService assignmentAuthorizationService;

    @Autowired
    private AssignmentCourseSyncService assignmentCourseSyncService;

    @Autowired
    private GroupsJpaRepository groupsRepository;

    @Autowired
    private CourseGroupProperties courseGroupProperties;

    /**
     * Get all submissions for current user (or all if admin)
     * 
     * @return List of submissions filtered by user role
     */
    @GetMapping("/list")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getSubmissions() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                logger.warn("Unauthorized access to submissions endpoint");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("Not authenticated"));
            }

            logger.debug("Processing submissions request for user: {}", auth.getName());

            // Check if user is admin
            boolean isAdmin = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_TEACHER"));

            logger.debug("User is admin: {}", isAdmin);

            List<AssignmentSubmission> submissions;

            if (isAdmin) {
                // Admin sees all submissions
                logger.debug("Fetching all submissions for admin user");
                submissions = submissionRepo.findAll();
            } else {
                // Regular user sees only their own submissions
                String username = auth.getName();
                logger.debug("Fetching submissions for user: {}", username);
                
                Person person = personRepo.findByUid(username);
                if (person == null) {
                    logger.error("User not found with uid: {}", username);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(new ErrorResponse("User not found"));
                }
                
                logger.debug("Found person with id: {}", person.getId());
                submissions = submissionRepo.findBySubmitterId(person.getId());
            }

            logger.debug("Retrieved {} submissions from database", submissions.size());

            List<SubmissionListDTO> dtos = toDtos(submissions);

            logger.info("Successfully fetched {} submissions", dtos.size());
            return ResponseEntity.ok(dtos);

        } catch (Exception e) {
            logger.error("Unexpected error in getSubmissions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Error fetching submissions"));
        }
    }

    /**
     * Get every submission the caller is allowed to manage.
     *
     * Distinct from /list, which answers "my submissions" (or all of them for staff).
     * Here a creator sees the submissions of the assignments they own and nothing else,
     * so the two endpoints can keep serving different views without one shadowing the other.
     *
     * @return List of submissions scoped to managed assignments
     */
    @GetMapping("/managed")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getManagedSubmissions() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
                logger.warn("Unauthorized access to managed submissions endpoint");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("Not authenticated"));
            }

            Person person = personRepo.findByUid(auth.getName());
            if (person == null) {
                logger.error("User not found with uid: {}", auth.getName());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("Not authenticated"));
            }

            List<AssignmentSubmission> submissions;
            if (assignmentAuthorizationService.isTeacherOrAdmin(person)) {
                submissions = submissionRepo.findAll();
            } else {
                List<Long> ownedAssignmentIds = assignmentRepo.findByCreatorId(person.getId()).stream()
                        .map(Assignment::getId)
                        .collect(Collectors.toList());
                // A student who owns nothing gets an empty list without hitting the submission table.
                submissions = ownedAssignmentIds.isEmpty()
                        ? List.of()
                        : submissionRepo.findByAssignmentIdIn(ownedAssignmentIds);
            }

            List<SubmissionListDTO> dtos = toDtos(submissions);

            logger.info("Successfully fetched {} managed submissions for {}", dtos.size(), auth.getName());
            return ResponseEntity.ok(dtos);

        } catch (Exception e) {
            logger.error("Unexpected error in getManagedSubmissions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Error fetching submissions"));
        }
    }

    /** Build all course membership metadata in one query before mapping individual rows. */
    private List<SubmissionListDTO> toDtos(List<AssignmentSubmission> submissions) {
        Map<Long, List<String>> courseCodesByPersonId = loadCourseCodesByPersonId(submissions);
        List<String> canonicalCourses = courseGroupProperties.getGroupNames();
        List<SubmissionListDTO> dtos = new ArrayList<>();

        for (AssignmentSubmission submission : submissions) {
            try {
                List<String> submitterCourses = submitterCourseCodes(
                    submission.getSubmitter(), courseCodesByPersonId, canonicalCourses);
                List<String> assignmentCourses = assignmentCourseSyncService.courseCodesOf(
                    submission.getAssignment());
                dtos.add(SubmissionListDTO.from(submission, submitterCourses, assignmentCourses));
            } catch (Exception e) {
                logger.error("Error converting submission {} to DTO", submission.getId(), e);
                dtos.add(SubmissionListDTO.createFallback(submission));
            }
        }
        return dtos;
    }

    private Map<Long, List<String>> loadCourseCodesByPersonId(
            List<AssignmentSubmission> submissions) {
        Set<Long> personIds = submissions.stream()
            .map(AssignmentSubmission::getSubmitter)
            .filter(Person.class::isInstance)
            .map(Person.class::cast)
            .map(Person::getId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        if (personIds.isEmpty()) {
            return Map.of();
        }

        List<String> canonicalCourses = courseGroupProperties.getGroupNames();
        Map<Long, Set<String>> courseSets = new HashMap<>();
        for (Object[] row : groupsRepository.findCourseMembershipsByPersonIds(
                personIds, canonicalCourses)) {
            Long personId = ((Number) row[0]).longValue();
            String courseCode = String.valueOf(row[1]).toUpperCase(Locale.ROOT);
            courseSets.computeIfAbsent(personId, ignored -> new LinkedHashSet<>()).add(courseCode);
        }

        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (Long personId : personIds) {
            Set<String> memberships = courseSets.getOrDefault(personId, Set.of());
            result.put(personId, canonicalCourses.stream().filter(memberships::contains).toList());
        }
        return result;
    }

    private List<String> submitterCourseCodes(
            Submitter submitter,
            Map<Long, List<String>> courseCodesByPersonId,
            List<String> canonicalCourses) {
        if (submitter instanceof Person person) {
            return courseCodesByPersonId.getOrDefault(person.getId(), List.of());
        }
        if (submitter instanceof Groups group) {
            for (String candidate : new String[] {group.getName(), group.getCourse()}) {
                if (candidate == null) {
                    continue;
                }
                String normalized = candidate.trim().toUpperCase(Locale.ROOT);
                if (canonicalCourses.contains(normalized)) {
                    return List.of(normalized);
                }
            }
        }
        return List.of();
    }

    /**
     * Get current user info including role status
     * Used to determine if user is admin and should see admin tabs
     */
    @GetMapping("/user-info")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getUserInfo() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                logger.debug("Unauthorized access to user-info endpoint");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("Not authenticated"));
            }

            boolean isAdmin = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_TEACHER"));

            logger.debug("User info requested - isAdmin: {}", isAdmin);

            return ResponseEntity.ok(new UserInfoDTO(isAdmin, auth.getName()));

        } catch (Exception e) {
            logger.error("Error in getUserInfo", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Error fetching user info"));
        }
    }

    /**
     * DTO for submission list display - uses factory method pattern for safe construction
     */
    public static class SubmissionListDTO {
        private Long id;
        private Long assignmentId;
        private String assignmentName;
        private String assignmentContentUrl;
        private List<String> assignmentCourseCodes;
        private String submitterName;
        private String submitterUid;
        private List<String> submitterCourseCodes;
        private Long submitterId;
        private Map<String, Object> content;
        private String comment;
        private Double grade;
        private String feedback;
        private Boolean isLate;
        private Boolean isGroup;
        private String aiSummary;
        private Integer qualityScore;

        // Private constructor for factory use
        private SubmissionListDTO() {}

        /**
         * Factory method - safe conversion with fallback handling
         */
        public static SubmissionListDTO from(AssignmentSubmission submission) {
            return from(submission, List.of(), List.of());
        }

        public static SubmissionListDTO from(
                AssignmentSubmission submission,
                List<String> submitterCourseCodes,
                List<String> assignmentCourseCodes) {
            SubmissionListDTO dto = new SubmissionListDTO();
            
            dto.id = submission.getId();
            
            // Handle null assignment
            if (submission.getAssignment() != null) {
                try {
                    dto.assignmentId = submission.getAssignment().getId();
                    dto.assignmentName = submission.getAssignment().getName();
                    dto.assignmentContentUrl = submission.getAssignment().getContentUrl();
                    dto.assignmentCourseCodes = List.copyOf(assignmentCourseCodes);
                } catch (Exception e) {
                    dto.assignmentId = null;
                    dto.assignmentName = "Unknown Assignment";
                    dto.assignmentContentUrl = null;
                    dto.assignmentCourseCodes = List.of();
                }
            } else {
                dto.assignmentId = null;
                dto.assignmentName = "Unknown Assignment";
                dto.assignmentContentUrl = null;
                dto.assignmentCourseCodes = List.of();
            }
            
            // Handle both Person and Groups submitters
            if (submission.getSubmitter() != null) {
                try {
                    Submitter submitter = submission.getSubmitter();
                    if (submitter instanceof Person) {
                        Person person = (Person) submitter;
                        dto.submitterName = person.getName();
                        dto.submitterUid = person.getUid();
                        dto.submitterCourseCodes = List.copyOf(submitterCourseCodes);
                        dto.isGroup = false;
                    } else if (submitter instanceof Groups) {
                        dto.submitterName = ((Groups) submitter).getName();
                        dto.submitterUid = null;
                        dto.submitterCourseCodes = List.copyOf(submitterCourseCodes);
                        dto.isGroup = true;
                    } else {
                        dto.submitterName = "Unknown";
                        dto.submitterUid = null;
                        dto.submitterCourseCodes = List.of();
                        dto.isGroup = false;
                    }
                    dto.submitterId = submitter.getId();
                } catch (Exception e) {
                    dto.submitterName = "Unknown";
                    dto.submitterId = null;
                    dto.submitterUid = null;
                    dto.submitterCourseCodes = List.of();
                    dto.isGroup = false;
                }
            } else {
                dto.submitterName = "Unknown";
                dto.submitterId = null;
                dto.submitterUid = null;
                dto.submitterCourseCodes = List.of();
                dto.isGroup = false;
            }
            
            try {
                dto.content = submission.getContent();
                dto.comment = submission.getComment() != null ? submission.getComment() : "";
                dto.grade = submission.getGrade();
                dto.feedback = submission.getFeedback() != null ? submission.getFeedback() : "";
                dto.isLate = submission.getIsLate() != null ? submission.getIsLate() : false;
                dto.aiSummary = submission.getAiSummary();
                dto.qualityScore = submission.getQualityScore();
            } catch (Exception e) {
                dto.content = null;
                dto.comment = "";
                dto.grade = null;
                dto.feedback = "";
                dto.isLate = false;
                dto.aiSummary = null;
                dto.qualityScore = null;
            }

            return dto;
        }

        /**
         * Fallback constructor for when main factory fails - returns minimal valid DTO
         */
        public static SubmissionListDTO createFallback(AssignmentSubmission submission) {
            SubmissionListDTO dto = new SubmissionListDTO();
            try {
                dto.id = submission.getId();
                dto.assignmentId = null;
                dto.assignmentName = "Unknown Assignment";
                dto.assignmentContentUrl = null;
                dto.assignmentCourseCodes = List.of();
                dto.submitterName = "Unknown";
                dto.submitterId = null;
                dto.submitterUid = null;
                dto.submitterCourseCodes = List.of();
                dto.isGroup = false;
                dto.content = null;
                dto.comment = "Error processing submission";
                dto.grade = null;
                dto.feedback = "";
                dto.isLate = false;
                dto.aiSummary = null;
                dto.qualityScore = null;
            } catch (Exception e) {
                logger.error("Error in SubmissionListDTO.createFallback()", e);
            }
            return dto;
        }

        // Getters for Jackson JSON serialization
        public Long getId() { return id; }
        public Long getAssignmentId() { return assignmentId; }
        public String getAssignmentName() { return assignmentName; }
        public String getAssignmentContentUrl() { return assignmentContentUrl; }
        public List<String> getAssignmentCourseCodes() { return assignmentCourseCodes; }
        public String getSubmitterName() { return submitterName; }
        public String getSubmitterUid() { return submitterUid; }
        public List<String> getSubmitterCourseCodes() { return submitterCourseCodes; }
        public Long getSubmitterId() { return submitterId; }
        public Map<String, Object> getContent() { return content; }
        public String getComment() { return comment; }
        public Double getGrade() { return grade; }
        public String getFeedback() { return feedback; }
        public Boolean getIsLate() { return isLate; }
        public Boolean getIsGroup() { return isGroup; }
        public String getAiSummary() { return aiSummary; }
        public Integer getQualityScore() { return qualityScore; }
    }

    /**
     * Simple error response with getter for JSON serialization
     */
    public static class ErrorResponse {
        private String error;

        public ErrorResponse(String error) {
            this.error = error;
        }

        public String getError() { return error; }
    }

    /**
     * User info response DTO
     */
    public static class UserInfoDTO {
        private boolean isAdmin;
        private String username;

        public UserInfoDTO(boolean isAdmin, String username) {
            this.isAdmin = isAdmin;
            this.username = username;
        }

        public boolean getIsAdmin() { return isAdmin; }
        public String getUsername() { return username; }
    }
}
