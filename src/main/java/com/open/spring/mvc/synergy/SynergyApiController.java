package com.open.spring.mvc.synergy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.assignments.Assignment;
import com.open.spring.mvc.assignments.AssignmentJpaRepository;
import com.open.spring.mvc.assignments.AssignmentSubmissionJPA;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.groups.Submitter;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import jakarta.validation.Valid;
import lombok.Getter;

@RestController
@RequestMapping("/api/synergy")
public class SynergyApiController {

    private final GroupsJpaRepository groupsJpaRepository;
    @Autowired
    private SynergyGradeJpaRepository gradeRepository;

    @Autowired
    private SynergyGradeRequestJpaRepository gradeRequestRepository;

    @Autowired
    private AssignmentJpaRepository assignmentRepository;

    @Autowired
    private PersonJpaRepository personRepository;

    @Autowired
    private GradebookService gradebookService;

    @Autowired
    private AssignmentSubmissionJPA submissionRepository;

    /**
     * A data transfer object that stores information about a grade request.
     */
    @Getter
    public static class SynergyGradeRequestDto {
        private Long submitterId;
        private Boolean isGroup;
        private Long assignmentId;
        private Double gradeSuggestion;
        private String explanation;
    }

    /**
     * A data transfer object that stores the id of a grade request.
     */
    @Getter
    public static class SynergyGradeRequestIdDto {
        private Long requestId;
    }

    /**
     * A data transfer object that stores information about a grade.
     */
    @Getter
    public static class SynergyGradeDto {
        private Long id;
        private Double grade;
        private Long assignmentId;
        private Long studentId;

        public SynergyGradeDto(SynergyGrade grade) {
            this.id = grade.getId();
            this.grade = grade.getGrade();
            this.assignmentId = grade.getAssignment().getId();
            this.studentId = grade.getStudent().getId();
        }
    }

    SynergyApiController(GroupsJpaRepository groupsJpaRepository) {
        this.groupsJpaRepository = groupsJpaRepository;
    }

    private static boolean isStaff(Person person) {
        return person.hasRole("ROLE_TEACHER") || person.hasRole("ROLE_ADMIN");
    }

    private Person currentPerson(UserDetails userDetails) {
        Person person = userDetails == null ? null : personRepository.findByUid(userDetails.getUsername());
        if (person == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return person;
    }

    /**
     * Gradebook cards for one course (or every class group when {@code course} is blank),
     * filtered by name. Teachers and admins only.
     */
    @GetMapping("/gradebook/students")
    public ResponseEntity<?> gradebookStudents(
        @AuthenticationPrincipal UserDetails userDetails,
        @RequestParam(required = false) String course,
        @RequestParam(required = false) String q
    ) {
        if (!isStaff(currentPerson(userDetails))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher or admin role required");
        }
        try {
            return ResponseEntity.ok(gradebookService.roster(course, q));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** One student's card. Students may only load their own. */
    @GetMapping("/gradebook/students/{personId}")
    public ResponseEntity<?> gradebookCard(
        @AuthenticationPrincipal UserDetails userDetails,
        @PathVariable Long personId
    ) {
        Person current = currentPerson(userDetails);
        if (!isStaff(current) && !current.getId().equals(personId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only view your own grades");
        }
        Person person = personRepository.findById(personId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));
        return ResponseEntity.ok(gradebookService.card(person));
    }

    /**
     * A POST endpoint to save a single grade to the database.
     * @param grade The parameters for a Grade POJO, passed in as JSON.
     * @return A JSON object confirming that the grade was saved.
     */
    @PostMapping("/grade")
    public ResponseEntity<Map<String, String>> updateGrade(@RequestBody SynergyGrade grade) {
        gradeRepository.save(grade);
        return ResponseEntity.ok(Map.of("message", "Successfully saved this grade."));
    }
    
    /**
     * A GET endpoint to retrieve all the grades.
     * @return A JSON object containing all the grades.
     */
    @GetMapping("/grades")
    public ResponseEntity<?> getGrades() {
        List<SynergyGradeDto> grades = new ArrayList<>();
        for (SynergyGrade grade : gradeRepository.findAll()) {
            grades.add(new SynergyGradeDto(grade));
        }
        return ResponseEntity.ok(grades);
    }
    
    /**
     * A POST endpoint to update many grades in bulk.
     * @param grades A formdata which is a map of strings of format grades[ASSIGNMENT_ID][STUDENT_ID] to numerical grades (or empty strings if there is no grade yet)
     * @return A JSON object confirming that the grades were updated
     */
    @PostMapping("/grades")
    public ResponseEntity<Map<String, String>> updateAllGrades(@RequestParam Map<String, String> grades) throws ResponseStatusException {
        for (String key : grades.keySet()) {
            // only actually look at relevant parameters
            if (!key.matches("grades\\[\\d+\\]\\[\\d+\\]")) {
                continue;
            }
        
            String[] ids = key.replace("grades[", "").replace("]", "").split("\\[");
            Long assignmentId = Long.parseLong(ids[0]);
            Long studentId = Long.parseLong(ids[1]);
            String gradeValueStr = grades.get(key);

            if (isNumeric(gradeValueStr)) { // otherwise, we have an empty string so ignore it
                Double gradeValue = Double.parseDouble(gradeValueStr);
                SynergyGrade grade = gradeRepository.findByAssignmentIdAndStudentId(assignmentId, studentId).orElse(null);

                if (grade == null) {
                    Assignment assignment = assignmentRepository.findById(assignmentId).orElseThrow(() -> 
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid assignment ID passed")
                    );
                    Person student = personRepository.findById(studentId).orElseThrow(() -> 
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid student ID passed")
                    );
    
                    grade = new SynergyGrade();
                    grade.setAssignment(assignment);
                    grade.setStudent(student);
                }
                grade.setGrade(gradeValue);
                gradeRepository.save(grade);
            }
        }

        return ResponseEntity.ok(Map.of("message", "Successfully updated the grades."));
    }
    
    /**
     * A POST endpoint to create a grade request.
     * @param userDetails The information about the logged in user. Automatically passed in by thymeleaf.
     * @param requestData The JSON data passed in, of the format studentId: Long, assignmentId: Long,
     *                    gradeSuggestion: Double, explanation: String
     * @return A JSON object signifying that the request was created.
     */
    @PostMapping("/grades/requests")
    public ResponseEntity<Map<String, String>> createGradeRequest(
        @AuthenticationPrincipal UserDetails userDetails, 
        @RequestBody SynergyGradeRequestDto requestData
    ) throws ResponseStatusException {
        String uid = userDetails.getUsername();
        Person grader = personRepository.findByUid(uid);
        if (grader == null) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "You must be a logged in user to do this"
            );
        }

        boolean isGroup = Boolean.TRUE.equals(requestData.getIsGroup());
        if (requestData.getSubmitterId() == null || requestData.getAssignmentId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "submitterId and assignmentId are required");
        }
        if (!isStaff(grader)) {
            // Students may only request a change to their own (or their group's) submitted work.
            List<Long> ownSubmitterIds = new ArrayList<>(List.of(grader.getId()));
            groupsJpaRepository.findGroupsByPersonId(grader.getId()).forEach(group -> ownSubmitterIds.add(group.getId()));
            boolean own = isGroup
                ? !requestData.getSubmitterId().equals(grader.getId()) && ownSubmitterIds.contains(requestData.getSubmitterId())
                : grader.getId().equals(requestData.getSubmitterId());
            if (!own) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only request grade changes for yourself");
            }
            boolean submitted = ownSubmitterIds.stream()
                .flatMap(submitterId -> submissionRepository.findBySubmitterId(submitterId).stream())
                .anyMatch(submission -> submission.getAssignment() != null
                    && requestData.getAssignmentId().equals(submission.getAssignment().getId()));
            if (!submitted) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You haven't submitted this assignment");
            }
        }

        Submitter submitter;
        if (isGroup) {
            // If the submitter is a group, we need to find the group by ID
            submitter = groupsJpaRepository.findById(requestData.getSubmitterId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid group ID passed"));
        } else {
            submitter = personRepository.findById(requestData.getSubmitterId()).orElseThrow(() -> 
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid student ID passed")
            );
        }
        List<Person> students = submitter.getMembers();

        Assignment assignment = assignmentRepository.findById(requestData.getAssignmentId()).orElseThrow(() -> 
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid assignment ID passed")
        );
        
        for (Person student : students) {
            // Check if a grade request already exists for this student and assignment
            SynergyGradeRequest request = new SynergyGradeRequest(assignment, student, grader, requestData.getExplanation(), requestData.getGradeSuggestion());
            gradeRequestRepository.save(request);
        }

        return ResponseEntity.ok(Map.of("message", "Successfully created the grade requests."));
    }
    
    /**
     * A POST endpoint to accept a grade request.
     * @param body The JSON data passed in, of the format requestId: Long
     * @return A JSON object signifying that the request was accepted.
     */
    @PostMapping("/grade/requests/accept")
    public ResponseEntity<Map<String, String>> acceptRequest(@Valid @RequestBody SynergyGradeRequestIdDto body) throws ResponseStatusException {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request body");
        }

        SynergyGradeRequest request = gradeRequestRepository.findById((long) body.getRequestId()).orElse(null);
        if (request == null) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Grade request not found"
            );
        }
        else if (request.isAccepted()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Grade request was already accepted before"
            );
        }

        Assignment assignment = request.getAssignment();
        Person student = request.getStudent();
        SynergyGrade grade = gradeRepository.findByAssignmentAndStudent(assignment, request.getStudent());
        if (grade == null) {
            grade = new SynergyGrade();
            grade.setAssignment(assignment);
            grade.setStudent(student);
        }
        grade.setGrade(request.getGradeSuggestion());
        gradeRepository.save(grade);

        request.accept();
        gradeRequestRepository.save(request);

        return ResponseEntity.ok(Map.of("message", "Successfully accepted the grade request."));
    }

    /**
     * Rejects a grade request.
     * @param body The JSON data passed in, of the format requestId: Long
     * @return A JSON object signifying that the request was rejected.
     * @see SynergyGradeRequest
     */
    @PostMapping("/grade/requests/reject")
    public ResponseEntity<Map<String, String>> rejectRequest(@RequestBody SynergyGradeRequestIdDto body) throws ResponseStatusException {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request body.");
        }
    
        SynergyGradeRequest request = gradeRequestRepository.findById((long) body.getRequestId()).orElse(null);
        if (request == null) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Grade request not found."
            );
        }
        else if (request.isAccepted()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Grade request was already accepted before."
            );
        }

        request.reject();
        gradeRequestRepository.save(request);

        return ResponseEntity.ok(Map.of("message", "Successfully rejected the grade request."));
    }

    /**
     * Returns a map of all the grades for a student, where the key is the assignment ID and the value is the grade.
     * This is useful for displaying grades in a table format.
     * @param userId The ID of the student whose grades we want to retrieve.
     * @throws ResponseStatusException if the student is not found.
     * @return A map of assignment IDs to grades for the student.
     * @see SynergyGrade
     * @see SynergyGradeJpaRepository
     */
    @GetMapping("/grades/map/{userId}")
    public ResponseEntity<Map<Long, Double>> getStudentGradesAsMap(@PathVariable Long userId) {
        Person student = personRepository.findById(userId).orElseThrow(() -> 
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found")
        );
        
        List<SynergyGrade> studentGrades = gradeRepository.findByStudent(student);
        
        Map<Long, Double> assignmentGrades = studentGrades.stream()
            .collect(Collectors.toMap(
                grade -> grade.getAssignment().getId(),
                SynergyGrade::getGrade,
                (existing, replacement) -> existing
            ));
        
        return new ResponseEntity<>(assignmentGrades, HttpStatus.OK);
    }

    /**
     * Returns whether or not a string is numeric or not (can be a decimal)
     * @param str A string
     * @return A boolean indicating that the string is or is not numeric
     */
    private boolean isNumeric(String str) {
        return str.matches("-?\\d+(\\.\\d+)?");
    }

}