package com.open.spring.mvc.assignments;

import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
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
import com.open.spring.mvc.assignments.AssignmentCreatorSyncService.UnknownCreatorException;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import jakarta.transaction.Transactional;
import lombok.Getter;
import lombok.Setter;

@RestController
@RequestMapping("/api/assignments")
public class AssignmentsApiController {

    private Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private GroupsJpaRepository groupRepo;

    @Autowired
    private AssignmentJpaRepository assignmentRepo;

    @Autowired
    private AssignmentSubmissionJPA submissionRepo;

    @Autowired
    private PersonJpaRepository personRepo;

    @Autowired
    private FileHandler fileHandler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AssignmentRubricService rubricService;

    @Autowired
    private AssignmentAuthorizationService assignmentAuthorizationService;

    @Autowired
    private AssignmentCreatorSyncService assignmentCreatorSyncService;

    @Getter
    @Setter
    public static class AssignmentDto {
        public Long id;
        public String name;
        public String type;
        public String assignmentType;
        public String description;
        public String aiRubric;
        public Double points;
        public String dueDate;
        public String timestamp;
        public String resourceType;
        public String resourceUrl;
        public String resourceFilename;
        public String resourceStoragePath;
        public String resourceUploadedBy;
        /** Owner uids; null on endpoints that do not load the creator relationship. */
        public List<String> creatorUids;

        public AssignmentDto(Assignment assignment, List<String> creatorUids) {
            this(assignment);
            this.creatorUids = creatorUids;
        }

        public AssignmentDto(Assignment assignment) {
            this.id = assignment.getId();
            this.name = assignment.getName();
            this.type = assignment.getType();
            this.assignmentType = assignment.getAssignmentType();
            this.description = assignment.getDescription();
            this.aiRubric = assignment.getAiRubric();
            this.points = assignment.getPoints();
            this.dueDate = assignment.getDueDate();
            this.timestamp = assignment.getTimestamp();
            this.resourceType = assignment.getResourceType();
            this.resourceUrl = assignment.getResourceUrl();
            this.resourceFilename = assignment.getResourceFilename();
            this.resourceStoragePath = assignment.getResourceStoragePath();
            this.resourceUploadedBy = extractResourceUploader(assignment);
        }

        private static String extractResourceUploader(Assignment assignment) {
            String path = assignment.getResourceStoragePath();
            if (path == null || path.isBlank()) {
                return "unknown";
            }
            int slash = path.indexOf('/');
            if (slash <= 0) {
                return "unknown";
            }
            return path.substring(0, slash);
        }
    }

    
    @Getter
    @Setter
    public static class PersonSubmissionDto {
        public Long id;
        public String name;
        public String email;
        public String uid;

        public PersonSubmissionDto(Person person) {
            this.id = person.getId();
            this.name = person.getName();
            this.email = person.getEmail();
            this.uid = person.getUid();
        }
    }

    /**
     * A POST endpoint to create an assignment, accepts parametes as FormData.
     * @param name The name of the assignment.
     * @param type The type of assignment.
     * @param description The description of the assignment.
     * @param points The amount of points the assignment is worth.
     * @param dueDate The due date of the assignment, in MM/DD/YYYY format.
     * @return The saved assignment.
     */
    @PostMapping("/create") 
    public ResponseEntity<?> createAssignment(
            @RequestParam String name,
            @RequestParam String type,
            @RequestParam String description,
            @RequestParam(required = false) String aiRubric,
            @RequestParam(required = false) String pageContent,
            @RequestParam Double points,
            @RequestParam String dueDate,
            @RequestParam(required = false, defaultValue = "file") String assignmentType,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        requireTeacherOrAdmin(userDetails);
        logger.debug("createAssignment called with name='{}' type='{}' points={} dueDate='{}' by user={}", name, type, assignmentType, points, dueDate, userDetails==null?"<anon>":userDetails.getUsername());
        Assignment newAssignment = new Assignment(name, type, description, points, dueDate, assignmentType);
        if (aiRubric != null && !aiRubric.isBlank()) {
            newAssignment.setAiRubric(aiRubric.trim());
        } else {
            applyGeneratedRubric(newAssignment, name, description, pageContent);
        }
        normalizeAssignmentSequenceForSqlite();
        Assignment savedAssignment = assignmentRepo.save(newAssignment);
        return new ResponseEntity<>(new AssignmentDto(savedAssignment), HttpStatus.CREATED);
    }

    @PutMapping("/{id}/ai-rubric")
    public ResponseEntity<?> updateAiRubric(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal UserDetails userDetails) {
        requireTeacherOrAdmin(userDetails);
        Assignment assignment = assignmentRepo.findById(id).orElse(null);
        if (assignment == null) {
            return ResponseEntity.notFound().build();
        }
        String rubric = request == null ? null : request.get("rubric");
        if (rubric == null || rubric.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "rubric is required"));
        }
        assignment.setAiRubric(rubric.trim());
        return ResponseEntity.ok(new AssignmentDto(assignmentRepo.save(assignment)));
    }

    @PutMapping("/{id}/details")
    public ResponseEntity<?> updateAssignmentDetails(
            @PathVariable Long id,
            @RequestBody AssignmentUpdateRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        requireTeacherOrAdmin(userDetails);
        Assignment assignment = assignmentRepo.findById(id).orElse(null);
        if (assignment == null) {
            return ResponseEntity.notFound().build();
        }
        if (request == null || request.name == null || request.name.isBlank()
                || request.type == null || request.type.isBlank()
                || request.points == null || request.points < 0
                || request.dueDate == null || request.dueDate.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Name, type, points, and due date are required"));
        }

        assignment.setName(request.name.trim());
        assignment.setType(request.type.trim());
        assignment.setDescription(request.description == null ? "" : request.description.trim());
        assignment.setPoints(request.points);
        assignment.setDueDate(request.dueDate.trim());
        if (request.assignmentType != null && !request.assignmentType.isBlank()) {
            assignment.setAssignmentType(request.assignmentType.trim());
        }
        return ResponseEntity.ok(new AssignmentDto(assignmentRepo.save(assignment)));
    }

    @Getter
    @Setter
    public static class AssignmentUpdateRequest {
        public String name;
        public String type;
        public String description;
        public Double points;
        public String dueDate;
        public String assignmentType;
    }

    /**
     * A GET endpoint to retrieve all the assignments.
     * @return A list of all the assignments.
     */
    @Transactional
    @GetMapping("/")
    public ResponseEntity<?> getAllAssignments() {
        List<Assignment> assignments = assignmentRepo.findAll();
        List<Map<String, String>> simple = new ArrayList<>();
        for (Assignment a : assignments) {
            Map<String, String> map = new HashMap<>();
            map.put("id", String.valueOf(a.getId()));
            map.put("name", a.getName());
            map.put("description", a.getDescription());
            map.put("dueDate", a.getDueDate());
            map.put("points", String.valueOf(a.getPoints()));
            map.put("type", a.getType());
            map.put("assignmentType", a.getAssignmentType());
            map.put("resourceType", String.valueOf(a.getResourceType()));
            map.put("resourceUrl", String.valueOf(a.getResourceUrl()));
            map.put("resourceFilename", String.valueOf(a.getResourceFilename()));
            map.put("resourceStoragePath", String.valueOf(a.getResourceStoragePath()));
            map.put("resourceUploadedBy", extractResourceUploader(a));
            simple.add(map);
        }
        return new ResponseEntity<>(simple, HttpStatus.OK);
    }

    /**
     * Auto-create an assignment from frontmatter.
     * This endpoint is called when assignment: true is set in Jekyll frontmatter.
     * Any authenticated user (student, person, teacher, admin) can create assignments.
     * If an assignment with the same contentUrl already exists, it returns that instead of creating a duplicate.
     *
     * Ownership synchronization is separate from creation: creatorUids is only honored for
     * the trusted Pages bot (ROLE_ASSIGNMENT_SYNC). Browser calls omit the parameter entirely
     * and keep their existing behavior.
     *
     * @param name The name of the assignment (required)
     * @param contentUrl The URL to the lesson page (required)
     * @param description The description of the assignment (optional)
     * @param creatorUids Repeated form fields naming the assignment owners by Person.uid (optional, sync bot only)
     * @param userDetails The authenticated user making the request
     * @return The created or existing assignment with auto-generated ID
     */
    @PostMapping("/auto-create")
    @Transactional
    public ResponseEntity<?> autoCreateAssignment(
            @RequestParam String name,
            @RequestParam String contentUrl,
            @RequestParam(required = false, defaultValue = "") String description,
            @RequestParam(required = false) Double points,
            @RequestParam(required = false) String dueDate,
            @RequestParam(required = false) String assignmentType,
            @RequestParam(required = false) String pageContent,
            @RequestParam(required = false) List<String> creatorUids,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        // Debug log input
        logger.debug("autoCreateAssignment called with name='{}' contentUrl='{}' description='{}' points={} dueDate='{}' assignmentType='{}' userDetails={}", name, contentUrl, description, points, dueDate, assignmentType, userDetails==null?"<anon>":userDetails.getUsername());

        // Check authentication - any authenticated user can create assignments from frontmatter
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Authentication required"));
        }
        
        // Validate required parameters
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Assignment name is required"));
        }
        if (contentUrl == null || contentUrl.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Content URL is required"));
        }

        if (assignmentType == null){
            assignmentType = "file";
        }

        // The browser sends Jekyll's page.url ("/csa/lesson") and the Pages sync script
        // sends the same URL derived from the source file; canonicalizing here is what
        // makes both spellings resolve to one assignment instead of two.
        final String canonicalUrl = AssignmentContentUrls.canonicalize(contentUrl);
        if (canonicalUrl == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Content URL is required"));
        }

        // Ownership is only synchronized when the caller actually sent creatorUids.
        // Absent parameter == legacy behavior, which is what the browser flow relies on.
        boolean synchronizeCreators = creatorUids != null;
        List<Person> resolvedCreators = List.of();
        if (synchronizeCreators) {
            Person requester = personRepo.findByUid(userDetails.getUsername());
            if (!assignmentAuthorizationService.canSynchronizeCreators(requester)) {
                logger.warn("Rejected creator synchronization from non-sync user '{}' for contentUrl {}",
                    userDetails.getUsername(), contentUrl);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Assignment sync role required to set assignment creators"));
            }
            try {
                // Resolve every uid up front so a bad list never creates an assignment
                // and never leaves a partially written creator relationship behind.
                resolvedCreators = assignmentCreatorSyncService.resolveCreators(
                    assignmentCreatorSyncService.normalizeUids(creatorUids));
            } catch (UnknownCreatorException e) {
                logger.warn("Rejected creator synchronization for contentUrl {}: {}", contentUrl, e.getMessage());
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown creator uid(s)",
                    "unknownCreatorUids", e.getUnknownUids()));
            }
        }

        double resolvedPoints = points != null ? points : 1.0;
        String resolvedDueDate = (dueDate != null && !dueDate.trim().isEmpty())
            ? dueDate.trim()
            : LocalDate.now().plusDays(3).format(DateTimeFormatter.ofPattern("MM/dd/yyyy"));
        
        // Check if an assignment already exists for this content URL. This is a single
        // indexed lookup on content_url; it used to be a findAll() scan filtering on a
        // "[CONTENT_URL: <url>]" prefix written into the description, which read the whole
        // assignment table on every lesson page load. AssignmentContentUrlMigration adds
        // the column and backfills it from that legacy marker.
        Assignment existing = assignmentRepo.findFirstByContentUrlOrderByIdAsc(canonicalUrl);

        if (existing != null) {
            // Return existing assignment (already has auto-generated ID) — avoids duplicate
            // assignments for the same page. Ownership still has to be resynchronized here,
            // otherwise frontmatter edits would only ever reach assignments on their very
            // first deploy.
            Assignment assignment = existing;
            if (assignmentType != null && !assignmentType.isBlank()
                    && !assignmentType.equalsIgnoreCase(assignment.getAssignmentType())) {
                assignment.setAssignmentType(assignmentType.trim());
                assignment = assignmentRepo.save(assignment);
            }
            if (synchronizeCreators
                    && assignmentCreatorSyncService.applyCreators(assignment, resolvedCreators)) {
                assignment = assignmentRepo.save(assignment);
                logger.info("Synchronized creators for existing assignment ID {} (contentUrl: {})",
                    assignment.getId(), canonicalUrl);
            }
            logger.info("Assignment already exists for contentUrl: " + canonicalUrl + ", ID: " + assignment.getId());
            return ResponseEntity.ok(toDto(assignment, synchronizeCreators));
        }

        try {
            // Create new assignment with dynamic ID (auto-generated by database)
            // The content URL lives in its own column now, so the description is only ever
            // the human-readable text.
            // Null, not "", when the page declares no description. GeminiFeedbackService
            // builds its rubric as `description == null ? name : description`, so an empty
            // string would slip past that fallback and send the model an empty rubric.
            String trimmedDescription = (description == null) ? "" : description.trim();
            String finalDescription = trimmedDescription.isEmpty() ? null : trimmedDescription;

            Assignment newAssignment = new Assignment(
                name.trim(),
                "auto-created",  // type - identifies this as auto-created from frontmatter
                finalDescription,
                resolvedPoints,
                resolvedDueDate,
                assignmentType
            );
            applyGeneratedRubric(newAssignment, name, description, pageContent);

            newAssignment.setContentUrl(canonicalUrl);
            if (synchronizeCreators) {
                assignmentCreatorSyncService.applyCreators(newAssignment, resolvedCreators);
            }
            
            normalizeAssignmentSequenceForSqlite();
            Assignment savedAssignment = assignmentRepo.save(newAssignment);
            logger.info("Auto-created assignment with ID: " + savedAssignment.getId() + " for contentUrl: " + canonicalUrl);
            return new ResponseEntity<>(toDto(savedAssignment, synchronizeCreators), HttpStatus.CREATED);
        } catch (Exception e) {
            logger.error("Error auto-creating assignment for contentUrl: " + contentUrl, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to create assignment: " + e.getMessage()));
        }
    }

    /**
     * Assignments the caller is allowed to manage.
     *
     * Teachers and admins see every assignment; a creator sees only the assignments they
     * own; everyone else gets an empty list. Used by the Pages creator notice and by the
     * creator-facing submission views.
     *
     * @return assignment DTOs including their creator uids
     */
    @GetMapping("/managed")
    @Transactional
    public ResponseEntity<?> getManagedAssignments(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Authentication required"));
        }
        Person user = personRepo.findByUid(userDetails.getUsername());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Authentication required"));
        }

        List<Assignment> managed = assignmentAuthorizationService.isTeacherOrAdmin(user)
            ? assignmentRepo.findAllWithCreators()
            : assignmentRepo.findByCreatorId(user.getId());

        List<AssignmentDto> dtos = managed.stream()
            .map(assignment -> toDto(assignment, true))
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * @param includeCreators only read the creator relationship when the caller is entitled
     *        to see it and the collection is loadable in the current transaction.
     */
    private AssignmentDto toDto(Assignment assignment, boolean includeCreators) {
        return includeCreators
            ? new AssignmentDto(assignment, assignmentCreatorSyncService.creatorUidsOf(assignment))
            : new AssignmentDto(assignment);
    }

    @Getter
    @Setter
    public static class AssignmentResourceUrlDto {
        public String url;
    }

    /**
     * Generates a thorough, page-grounded AI rubric for a brand-new assignment when the
     * caller supplied the assignment page's content and didn't already set an explicit
     * rubric. Best-effort: any failure (no API key, bad response) leaves the assignment's
     * constructor-assigned {@link Assignment#DEFAULT_AI_RUBRIC} in place.
     */
    private void applyGeneratedRubric(Assignment assignment, String name, String description, String pageContent) {
        if (pageContent == null || pageContent.isBlank()) {
            return;
        }
        try {
            String generated = rubricService.generateRubric(name, description, pageContent);
            if (generated != null && !generated.isBlank()) {
                assignment.setAiRubric(generated);
            }
        } catch (Exception e) {
            logger.warn("Rubric generation failed for assignment '{}': {}", name, e.getMessage());
        }
    }

    /**
     * Repairs SQLite sequence drift/corruption for assignment IDs before inserts.
     * Some deployed databases keep assignment_seq behind the current max(id), which causes
     * duplicate primary key inserts when Hibernate asks SQLite for the next value.
     */
    private void normalizeAssignmentSequenceForSqlite() {
        try {
            Long maxId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(\"id\"), 0) FROM \"assignment\"", Long.class);
            Integer seqRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \"assignment_seq\"", Integer.class);
            Long nextVal = jdbcTemplate.queryForObject("SELECT MIN(\"next_val\") FROM \"assignment_seq\"", Long.class);

            long max = maxId == null ? 0L : maxId;
            long targetNext = Math.max(max + 1L, nextVal == null ? 1L : nextVal);

            if (seqRows == null || seqRows != 1 || nextVal == null || nextVal <= max) {
                jdbcTemplate.update("DELETE FROM \"assignment_seq\"");
                jdbcTemplate.update("INSERT INTO \"assignment_seq\"(\"next_val\") VALUES (?)", targetNext);
                logger.info("Normalized assignment_seq to next_val={} (max assignment id={})", targetNext, max);
            }
        } catch (DataAccessException ignored) {
            // Non-SQLite environments or DBs without assignment_seq can skip this silently.
        }
    }

    /**
     * Attach or update a URL resource on an existing assignment by ID.
     */
    @PostMapping("/{id}/resource/url")
    public ResponseEntity<?> attachUrlResource(
            @PathVariable Long id,
            @RequestBody AssignmentResourceUrlDto request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        Person user = requireTeacherOrAdmin(userDetails);

        if (request == null || request.url == null || request.url.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "url is required"));
        }

        Assignment assignment = assignmentRepo.findById(id).orElse(null);
        if (assignment == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Assignment not found"));
        }

        assignment.setUrlResource(request.url.trim(), user.getUid());
        Assignment saved = assignmentRepo.save(assignment);
        return ResponseEntity.ok(new AssignmentDto(saved));
    }

    /**
     * Attach or update a file resource on an existing assignment by ID.
     */
    @PostMapping(value = "/{id}/resource/file", consumes = "multipart/form-data")
    public ResponseEntity<?> attachFileResource(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        Person user = requireTeacherOrAdmin(userDetails);

        Assignment assignment = assignmentRepo.findById(id).orElse(null);
        if (assignment == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Assignment not found"));
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }

        try {
            String incomingName = file.getOriginalFilename() == null ? "resource.bin" : file.getOriginalFilename();
            String originalFilename = Paths.get(incomingName).getFileName().toString();
            String s3Key = "assignment-resources/"
                    + id
                    + "/"
                    + Instant.now().toEpochMilli()
                    + "_"
                    + UUID.randomUUID()
                    + "_"
                    + originalFilename;
            String base64Data = Base64.getEncoder().encodeToString(file.getBytes());

            String storedFilename = fileHandler.uploadFile(base64Data, s3Key, user.getUid());
            if (storedFilename == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Failed to upload file resource"));
            }

            assignment.setFileResource(originalFilename, user.getUid() + "/" + s3Key);
            Assignment saved = assignmentRepo.save(assignment);
            return ResponseEntity.ok(new AssignmentDto(saved));
        } catch (Exception e) {
            logger.error("Error uploading assignment resource file", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid file upload: " + e.getMessage()));
        }
    }

    /**
     * A GET endpoint to retrieve assignments by type.
     * @param type The type of assignment (e.g., "sprints", "all_assignments")
     * @return A list of assignments of the specified type.
     */
    @GetMapping("/type/{type}")
    public ResponseEntity<?> getAssignmentsByType(@PathVariable String type) {
        List<Assignment> assignments = assignmentRepo.findByAssignmentType(type);
        List<AssignmentDto> dtos = assignments.stream()
            .map(AssignmentDto::new)
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Read back file resource payload for an assignment by ID.
     */
    @GetMapping("/{id}/resource/file-content")
    public ResponseEntity<?> getFileResourceContent(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        requireTeacherOrAdmin(userDetails);

        Assignment assignment = assignmentRepo.findById(id).orElse(null);
        if (assignment == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Assignment not found"));
        }

        if (!"file".equalsIgnoreCase(defaultString(assignment.getResourceType(), ""))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Assignment does not have a file resource"));
        }

        String storagePath = assignment.getResourceStoragePath();
        if (storagePath == null || storagePath.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No file storage path set"));
        }

        int slash = storagePath.indexOf('/');
        if (slash <= 0 || slash >= storagePath.length() - 1) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Invalid file storage path format"));
        }

        String uid = storagePath.substring(0, slash);
        String key = storagePath.substring(slash + 1);
        String base64Data = fileHandler.decodeFile(uid, key);
        if (base64Data == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "File content not found"));
        }

        return ResponseEntity.ok(Map.of(
                "assignmentId", assignment.getId(),
                "filename", defaultString(assignment.getResourceFilename(), "resource.bin"),
                "storagePath", storagePath,
                "base64Data", base64Data
        ));
    }

    /**
     * Provide a frontmatter YAML snippet for frontend content linked by assignment ID.
     */
    @GetMapping("/{id}/frontmatter")
    public ResponseEntity<?> assignmentFrontmatterSnippet(@PathVariable Long id) {
        Assignment assignment = assignmentRepo.findById(id).orElse(null);
        if (assignment == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Assignment not found"));
        }

        StringBuilder snippet = new StringBuilder();
        snippet.append("assignment_id: ").append(assignment.getId()).append("\n");
        snippet.append("assignment_name: \"").append(escapeYaml(assignment.getName())).append("\"\n");
        snippet.append("assignment_type: \"").append(escapeYaml(assignment.getType())).append("\"\n");
        snippet.append("assignment_submission_type: \"").append(escapeYaml(assignment.getAssignmentType())).append("\"\n");
        snippet.append("assignment_due_date: \"").append(escapeYaml(assignment.getDueDate())).append("\"\n");
        snippet.append("assignment_resource_type: \"").append(escapeYaml(defaultString(assignment.getResourceType(), "none"))).append("\"\n");
        snippet.append("assignment_resource_url: \"").append(escapeYaml(defaultString(assignment.getResourceUrl(), ""))).append("\"\n");
        snippet.append("assignment_resource_file: \"").append(escapeYaml(defaultString(assignment.getResourceStoragePath(), ""))).append("\"\n");

        return ResponseEntity.ok(Map.of(
                "assignmentId", assignment.getId(),
                "frontmatter", snippet.toString(),
                "resourceType", defaultString(assignment.getResourceType(), "none")
        ));
    }

    /**
     * A POST endpoint to edit an assignment.
     * @param name The name of the assignment.
     * @param body The new information about the assignment.
     * @return The edited assignment.
     */
    @PostMapping("/edit/{name}")
    public ResponseEntity<?> editAssignment(
            @PathVariable String name,
            @RequestBody String body) {
        Assignment assignment = assignmentRepo.findByName(name);
        if (assignment != null) {
            assignment.setName(name);
            assignmentRepo.save(assignment);
            return new ResponseEntity<>(assignment, HttpStatus.OK);
        }
        Map<String, String> error = new HashMap<>();
        error.put("error", "Assignment not found: " + name);
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    /**
     * A GET endpoint to retrieve all submissions for a student.
     * @param studentId The ID of the student.
     * @return A list of all submissions for the student.
     * If the student is not found, returns a 404 error.
     * If the student has no submissions, returns an empty list.
     * If the student has submissions, returns a list of AssignmentSubmissionReturnDto objects.
     */
    @Transactional
    @GetMapping("/submissions/student/{studentId}")
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
     * A GET endpoint to retrieve an assignment by its ID.
     * @param id The ID of the assignment.
     * @return The name of the assignment if found, or an error message if not found.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        Optional<Assignment> assignment = assignmentRepo.findById(id);

        if (assignment.isPresent()) {
            return ResponseEntity.ok(assignment.get().getName());
        } else {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Assignment not found with ID: " + id);
            return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
        }
    }


    /**
     * A POST endpoint to delete an assignment.
     * @param id The ID of the assignment to delete.
     * @return A JSON object indicating that the assignment was deleted.
     */
    @PostMapping("/delete/{id}")
    public ResponseEntity<?> deleteAssignment(@PathVariable Long id) {
        Assignment assignment = assignmentRepo.findById(id).orElse(null);
        if (assignment != null) {
            assignmentRepo.delete(assignment);
            Map<String, String> response = new HashMap<>();
            response.put("message", "Assignment deleted successfully");
            return new ResponseEntity<>(response, HttpStatus.OK);
        }
        Map<String, String> error = new HashMap<>();
        error.put("error", "Assignment not found");
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    /**
     * A GET endpoint used for debugging which returns information about every assignment.
     * @return Information about all the assignments.
     */
    @GetMapping("/debug")
    public ResponseEntity<?> debugAssignments() {
        List<Assignment> assignments = assignmentRepo.findAll();
        List<AssignmentDto> simple = new ArrayList<>();
        for (Assignment a : assignments) {
            simple.add(new AssignmentDto(a));
        }
        return new ResponseEntity<>(simple, HttpStatus.OK);
    }

    /**
     * A GET endpoint to retrieve all submissions for the assignment.
     * @param assignmentId The ID of the assignment.
     * @return All submissions for the assignment.
     */
    @Transactional
    @GetMapping("/{assignmentId}/submissions")
    public ResponseEntity<?> getSubmissions(@PathVariable Long assignmentId, @AuthenticationPrincipal UserDetails userDetails) {
        String uid = userDetails.getUsername();
        Person user = personRepo.findByUid(uid);

        if (user == null) {
            logger.error("User not found with email: {}", uid);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found with uid: " + uid);
        }

        Stream<AssignmentSubmission> submissions = submissionRepo.findByAssignmentId(assignmentId).stream();

        if (!(user.hasRoleWithName("ROLE_TEACHER") || user.hasRoleWithName("ROLE_ADMIN"))) {
            // if they aren't a teacher or admin, only let them see submissions they are assigned to grade
            submissions = submissions
                .filter(submission -> submission.getAssignedGraders().contains(user));
        }

        List<AssignmentSubmissionReturnDto> returnValue = submissions
            .map(AssignmentSubmissionReturnDto::new)
            .toList();

        return new ResponseEntity<>(returnValue, HttpStatus.OK);
    }

    /**
     * A GET endpoint to retrieve the queue for an assignment.
     * @param id The ID of the assignment.
     * @return Queue for assignment, formatted in JSON
     */
    @GetMapping("/getQueue/{id}")
    public ResponseEntity<AssignmentQueue> getQueue(@PathVariable long id) {
        Optional<Assignment> optional = assignmentRepo.findById(id);
        if (optional.isPresent()) {
            Assignment assignment = optional.get();
            
            return new ResponseEntity<>(assignment.getAssignmentQueue(), HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    /**
     * A GET endpoint to retrieve the presentation length for an assignment.
     * @param id The ID of the assignment.
     * @return Presentation length for assignment, formatted in JSON
     */
    @GetMapping("/getPresentationLength/{id}")
    public ResponseEntity<Long> getPresentationLength(@PathVariable long id) {
        Optional<Assignment> optional = assignmentRepo.findById(id);
        if (optional.isPresent()) {
            Assignment assignment = optional.get();
            
            return new ResponseEntity<>(assignment.getPresentationLength(), HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    /**
     * A PUT endpoint to initialize an empty queue for an assignment.
     * @param id The ID of the assignment.
     * @return Queue for assignment, formatted in JSON
     */
    @PutMapping("/initQueue/{id}")
    public ResponseEntity<Assignment> initQueue(@PathVariable long id, @RequestBody List<List<String>> people) {
        Optional<Assignment> optional = assignmentRepo.findById(id);
        if (optional.isPresent()) {
            Assignment assignment = optional.get();
            assignment.initQueue(people.get(0), Long.parseLong(people.get(1).get(0)));
            assignmentRepo.save(assignment);
            return new ResponseEntity<>(assignment, HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    /**
     * A PUT endpoint to add a user to the waiting list
     * @param id The ID of the assignment.
     * @param person Name of person to be added to waiting list in one element array.
     * @return Updated queue for assignment, formatted in JSON
     */
    @PutMapping("/addToWaiting/{id}")
    public ResponseEntity<Assignment> addQueue(@PathVariable long id, @RequestBody List<String> person) {
        Optional<Assignment> optional = assignmentRepo.findById(id);
        if (optional.isPresent()) {
            Assignment assignment = optional.get();
            assignment.addQueue(person.get(0));
            assignmentRepo.save(assignment);
            return new ResponseEntity<>(assignment, HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    /**
     * A PUT endpoint to return a user to the working list
     * @param id The ID of the assignment.
     * @param person Name of person to be returned to the working list in one element array.
     * @return Updated queue for assignment, formatted in JSON
     */
    @PutMapping("/removeToWorking/{id}")
    public ResponseEntity<Assignment> removeQueue(@PathVariable long id, @RequestBody List<String> person) {
        Optional<Assignment> optional = assignmentRepo.findById(id);
        if (optional.isPresent()) {
            Assignment assignment = optional.get();
            assignment.removeQueue(person.get(0));
            assignmentRepo.save(assignment);
            return new ResponseEntity<>(assignment, HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    /**
     * A PUT endpoint to move a user to the completed list
     * @param id The ID of the assignment.
     * @param person Name of person to be moved to the completed list in one element array.
     * @return Updated queue for assignment, formatted in JSON
     */
    @PutMapping("/doneToCompleted/{id}")
    public ResponseEntity<Assignment> doneQueue(@PathVariable long id, @RequestBody List<String> person) {
        Optional<Assignment> optional = assignmentRepo.findById(id);
        if (optional.isPresent()) {
            Assignment assignment = optional.get();
            assignment.doneQueue(person.get(0));
            assignmentRepo.save(assignment);
            return new ResponseEntity<>(assignment, HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    /**
     * A PUT endpoint to reset a queue to its empty form.
     * @param id The ID of the assignment.
     * @return Updated queue for assignment, formatted in JSON
     */
    @PutMapping("/resetQueue/{id}")
    public ResponseEntity<Assignment> resetQueue(@PathVariable long id) {
        Optional<Assignment> optional = assignmentRepo.findById(id);
        if (optional.isPresent()) {
            Assignment assignment = optional.get();
            assignment.resetQueue();
            assignmentRepo.save(assignment);
            return new ResponseEntity<>(assignment, HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    /**
     * A POST endpoint to assign graders to an assignment
     * @param id The ID of the assignment.
     * @param personIds A list of person IDs to be assigned as graders.
     * @return A response indicating success or failure.
     */
    @PostMapping("/assignGraders/{id}")
    public ResponseEntity<?> assignGradersToAssignment( @PathVariable Long id, @RequestBody List<Long> personIds ) {
        Optional<Assignment> assignmentOptional = assignmentRepo.findById(id);
        if (!assignmentOptional.isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Assignment not found");
        }

        Assignment assignment = assignmentOptional.get();
        List<Person> persons = personRepo.findAllById(personIds);

        assignment.setAssignedGraders(persons);

        assignmentRepo.save(assignment);

        return ResponseEntity.ok("Persons assigned successfully");
    }
    
    /**
     * A POST endpoint to sign up for a team teach assignment.
     * @param userDetails The authenticated user details.
     * @param id The ID of the assignment.
     * @return A response indicating success or failure.
     */
    @PostMapping("/teamteach/signup/{id}")
    @Transactional
    public ResponseEntity<?> signupForTeamteach(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You must be a logged in user to do this");
        }
        String uid = userDetails.getUsername();
        Person user = personRepo.findByUid(uid);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You must be a logged in user to do this");
        }
        
        Optional<Assignment> assignmentOptional = assignmentRepo.findById(id);
        if (!assignmentOptional.isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Assignment not found");
        }

        Assignment assignment = assignmentOptional.get();
        if (!assignment.getType().equals("teamteach")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("This assignment is not a team teach assignment");
        }

        // Check if user is already assigned to the assignment
        if (assignment.getAssignedGraders().stream().anyMatch(assignedGrader -> assignedGrader.getId().equals(user.getId()))) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("You are already signed up for this team teach");
        }

        List<Person> assignedGraders = assignment.getAssignedGraders();
        if (assignedGraders == null) {
            assignedGraders = new ArrayList<>();
        }
        assignedGraders.add(user);
        assignment.setAssignedGraders(assignedGraders);
        assignmentRepo.save(assignment);

        return ResponseEntity.ok("You have successfully signed up for the team teach assignment");        
    }

    /**
     * A GET endpoint to retrieve the assigned graders for an assignment.
     * @param id The ID of the assignment.
     * @return A list of assigned graders for the assignment.
     */
    @GetMapping("/assignedGraders/{id}")
    @Transactional
    public ResponseEntity<?> getAssignedGraders(@PathVariable Long id) {
        Optional<Assignment> assignmentOptional = assignmentRepo.findById(id);
        if (!assignmentOptional.isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Assignment not found");
        }

        Assignment assignment = assignmentOptional.get();
        List<Person> assignedGraders = assignment.getAssignedGraders();
        
        // Return just the IDs of assigned persons
        List<PersonSubmissionDto> assignedGraderIds = assignedGraders.stream()
            .map(PersonSubmissionDto::new)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(assignedGraderIds);
    }
    
    /**
     * A GET endpoint to retrieve all assignments that the logged-in user is assigned to grade.
     * @param userDetails The authenticated user details.
     * @return A list of AssignmentDto objects representing the assignments assigned to the user.
     * If the user is not logged in, returns a 404 error.
     */
    @Transactional
    @GetMapping("/assigned")
    public ResponseEntity<?> getAssignedAssignments(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        String uid = userDetails.getUsername();
        Person user = personRepo.findByUid(uid);
        if (user == null) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "You must be a logged in user to do this"
            );
        }

        List<Assignment> assignments = assignmentRepo.findByAssignedGraders(user);
        List<AssignmentSubmission> submissions = submissionRepo.findByAssignedGraders(user);

        List<AssignmentDto> formattedAssignments = new ArrayList<>();
        for (Assignment a : assignments) {
            formattedAssignments.add(new AssignmentDto(a));
        }
        for (AssignmentSubmission s: submissions) {
            formattedAssignments.add(new AssignmentDto(s.getAssignment()));
        }

        return ResponseEntity.ok(formattedAssignments);
    }

    /**
     * A GET endpoint to bulk extract all assignments for backups.
     * @return A list of AssignmentDto objects representing all assignments.
     */
    @GetMapping("/bulk/extract")
    public ResponseEntity<List<AssignmentDto>> bulkExtractAssignments() {
        // Fetch all Assignment entities from the database
        List<Assignment> assignment = assignmentRepo.findAll();

        // Map Assignment entities to Assignment objects
        List<AssignmentDto> assignmentDtos = new ArrayList<>();
        for (Assignment assignment2 : assignment) {
            AssignmentDto assignmentDto = new AssignmentDto(assignment2);
            
            assignmentDtos.add(assignmentDto);
        }
        // Return the list of Assignment objects
        return new ResponseEntity<>(assignmentDtos, HttpStatus.OK);
    }

    /**
     * Bulk create Assignment entities from a list of AssignmentDto objects.
     * 
     * @param assignmentDtos A list of AssignmentDto objects to be created.
     * @return A ResponseEntity containing the result of the bulk creation.
     */
    @PostMapping("/bulk/create")
    public ResponseEntity<Object> bulkCreateAssignments(@RequestBody List<AssignmentDto> assignmentDtos) {
        List<String> createdAssignments = new ArrayList<>();
        List<String> duplicateAssignments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        
        for (AssignmentDto assignmentDto : assignmentDtos) {
            try {
                // Check if assignment with the same name already exists
                Assignment existingAssignment = assignmentRepo.findByName(assignmentDto.name);
                if (existingAssignment != null) {
                    duplicateAssignments.add(assignmentDto.name);
                    continue;
                }
                
                // Create a new Assignment entity from the DTO
                Assignment newAssignment = new Assignment(
                    assignmentDto.name, 
                    assignmentDto.type, 
                    assignmentDto.description, 
                    assignmentDto.points, 
                    assignmentDto.dueDate,
                    assignmentDto.assignmentType
                );
                
                // Save the new assignment
                Assignment savedAssignment = assignmentRepo.save(newAssignment);
                createdAssignments.add(savedAssignment.getName());
                
            } catch (Exception e) {
                // Handle exceptions
                errors.add("Exception occurred for assignment: " + assignmentDto.name + " - " + e.getMessage());
            }
        }
        
        // Prepare the response
        Map<String, Object> response = new HashMap<>();
        response.put("created", createdAssignments);
        response.put("duplicates", duplicateAssignments);
        response.put("errors", errors);
        
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
    
    /**
     * A POST endpoint to randomize peer graders for an assignment.
     * This method shuffles the submissions and assigns each submission a random grader from the pool of submissions.
     * @param id The ID of the assignment for which to randomize peer graders.
     * @return A response indicating success or failure.
     * If the assignment is not found, returns a 404 error.
     */
    @PostMapping("/randomizeGraders/{id}")
    @Transactional
    public ResponseEntity<?> randomizePeerGraders(@PathVariable Long id) {
        Optional<Assignment> assignmentOptional = assignmentRepo.findById(id);
        if (!assignmentOptional.isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Assignment not found");
        }

        List<AssignmentSubmission> submissions = submissionRepo.findByAssignmentId(id);
    
        if (submissions.isEmpty()) {
            return ResponseEntity.badRequest().body("No submissions found for this assignment");
        }
        
        if (submissions.size() == 1) {
            return ResponseEntity.badRequest().body("Only one submission found for this assignment, can't really do peer grading");
        }

        Collections.shuffle(submissions);

        for (int i = 0; i < submissions.size(); i++) {
            AssignmentSubmission currentSubmission = submissions.get(i);
            
            // grader whos not the asme persoon
            List<AssignmentSubmission> possibleGraders = submissions.stream()
                .filter(submission -> Collections.disjoint(
                    submission.getSubmitter().getMembers(), 
                    currentSubmission.getSubmitter().getMembers()
                )) // ensure no overlap between the members of the two groups
                .collect(Collectors.toList());
    
            if (possibleGraders.isEmpty()) {
                System.out.println("FATAL: No possible graders found for submission by: " + 
                    currentSubmission.getSubmitter().getMembers().stream()
                        .map(Person::getName)
                        .collect(Collectors.joining(", ")));

                // TODO: implement a better randomization strategy. In theory, it is possible that all submissions are from various groups which all include one same person, therefore causing this issue.
                continue; 
            }
    
            // Randomly select
            AssignmentSubmission graderSubmission = possibleGraders.get(
                (int)(Math.random() * possibleGraders.size())
            );
    
            // Assign graders to the current submission
            // Create a new list instead of sharing the existing one
            currentSubmission.setAssignedGraders(graderSubmission.getSubmitter().getMembers());
        }

    
        submissionRepo.saveAll(submissions);
        // test debug
        // for (AssignmentSubmission sub : uniqueSubmissions) {
        //     System.out.println("Submission by: " + sub.getStudents().get(0).getName() + 
        //                     " is graded by: " + 
        //                     sub.getAssignedGraders().stream()
        //                         .map(Person::getName)
        //                         .collect(Collectors.joining(", ")));
        // }
    
        return ResponseEntity.ok("Graders randomized successfully!");
    }


    /**
     * A GET endpoint to extract an assignment by its ID.
     * This endpoint retrieves the assignment details and returns them as an AssignmentDto object.
     * If the assignment is not found, it returns a 404 error.
     * @param id The ID of the assignment to extract.
     * @return A ResponseEntity containing the AssignmentDto object if found, or a 404 error if not found.
     */
    @GetMapping("/extract/{id}")
    public ResponseEntity<AssignmentDto> extractAssignment(@PathVariable Long id) {
        Optional<Assignment> curAssignment = assignmentRepo.findById(id);
        Assignment assignment = curAssignment.get();
        AssignmentDto assignmentDto = new AssignmentDto(assignment);
        return new ResponseEntity<>(assignmentDto, HttpStatus.OK);
    }

    private Person requireTeacherOrAdmin(UserDetails userDetails) {
        if (userDetails == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authentication required");
        }
        Person user = personRepo.findByUid(userDetails.getUsername());
        if (user == null || !(user.hasRoleWithName("ROLE_TEACHER") || user.hasRoleWithName("ROLE_ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher or admin role required");
        }
        return user;
    }

    private String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private String escapeYaml(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }

    private String extractResourceUploader(Assignment assignment) {
        String path = assignment.getResourceStoragePath();
        if (path == null || path.isBlank()) {
            return "unknown";
        }
        int slash = path.indexOf('/');
        if (slash <= 0) {
            return "unknown";
        }
        return path.substring(0, slash);
    }
}
