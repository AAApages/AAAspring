package com.open.spring.mvc.assignments;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.synergy.SynergyGrade;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
@Entity(name = "AssignmentEntity")
@Table(name = "assignment")
@Getter
@Setter
@NoArgsConstructor
public class Assignment {
    public static final String DEFAULT_AI_RUBRIC = """
 Score 4 — Strong / Exceptional
The submission is clear, well-organized, and substantively complete. It directly addresses the core requirement and goes further by including specific, relevant details (examples, data, edge cases, or context) that demonstrate genuine understanding. Reasoning is explicit and logically connects the details to the conclusion — a reader does not need to infer missing steps. Minor imperfections are acceptable, but nothing essential is missing.

Indicators:
 Fully addresses the prompt/requirement, including secondary or implied aspects
 Uses specific, relevant supporting details rather than generic statements
 Reasoning is explained, not just asserted
 Well-structured and easy to follow

Score 3 — Adequate
The submission addresses the main requirement and is generally correct, but is noticeably thinner than a 4. It may rely on general statements rather than specific details, skip minor aspects of the prompt, or leave some reasoning implicit. A reader can follow it without major confusion, but it lacks the depth, precision, or supporting evidence of a top submission.

Indicators:
 Core requirement is met; response is accurate
 Some relevant detail or context present, but not comprehensive
 Reasoning is present but may be brief or partially assumed
 Minor gaps that don't undermine the overall response

 Score 2 — Limited
The submission is partial or shallow. It may address only part of the requirement, provide information without explaining its relevance, or offer a conclusion with little to no supporting reasoning. Important details are missing or vague, and the response reads as incomplete or underdeveloped rather than simply concise.

Indicators:
 Only partially addresses the requirement
 Reasoning is shallow, generic, or largely missing
 Lacks specific supporting detail or context
 Reader is left with unanswered questions about how the conclusion was reached

 Score 1 — Insufficient
The submission fails to meaningfully address the requirement. It may be off-topic, factually incorrect, too vague to evaluate, or missing entirely. There is little to no relevant reasoning or detail present.

Indicators:
 Does not address the core requirement
 No meaningful reasoning or supporting detail
 Response is largely irrelevant, incorrect, or absent
            """;

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(unique=false)
    @NotEmpty
    private String name;

    @NotEmpty
    private String type;

    private String assignmentType;

    private String description;

    /**
     * The lesson page this assignment was auto-created for, canonicalized via
     * {@link AssignmentContentUrls#canonicalize(String)}. Indexed (see
     * AssignmentContentUrlMigration) so /api/assignments/auto-create can look an
     * existing assignment up directly instead of scanning every row's description.
     */
    private String contentUrl;

    @Column(columnDefinition = "TEXT")
    private String aiRubric;

    @NotEmpty
    private String dueDate;

    @NotEmpty
    private String timestamp;

    @OneToMany(mappedBy="assignment", cascade=CascadeType.ALL, orphanRemoval=true)
    @JsonIgnore
    private List<AssignmentSubmission> submissions;

    @ManyToMany
    @JoinTable(
        name = "assignment_person",
        joinColumns = @JoinColumn(name = "assignment_id"),
        inverseJoinColumns = @JoinColumn(name = "person_id")
    )
    private List<Person> assignedGraders;

    /**
     * People who own this assignment and may manage its submissions.
     *
     * Deliberately separate from assignedGraders: a grader is assigned work on an
     * assignment, a creator owns it. The list is synchronized from Pages frontmatter
     * (assignment_creator_uids) and is never cascaded to Person, so removing a creator
     * only drops the join row. Excluded from equals/hashCode because Person inherits
     * identity equality from Submitter, which makes whole-entity comparison unreliable
     * and would force the lazy collection to load.
     */
    @ManyToMany
    @JoinTable(
        name = "assignment_creators",
        joinColumns = @JoinColumn(name = "assignment_id"),
        inverseJoinColumns = @JoinColumn(name = "person_id")
    )
    @JsonIgnore
    @EqualsAndHashCode.Exclude
    private List<Person> creators = new ArrayList<>();

    @OneToMany(mappedBy="assignment", cascade=CascadeType.ALL, orphanRemoval=true)
    @JsonIgnore
    private List<SynergyGrade> grades;

    @NotNull
    private Double points;

    // Optional assignment resource metadata linked to this assignment ID.
    private String resourceType;
    private String resourceUrl;
    private String resourceFilename;
    private String resourceStoragePath;

    private Long presentationLength;

    @Convert(converter = AssignmentQueueConverter.class)
    private AssignmentQueue assignmentQueue;

    private static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public void resetQueue() {
        assignmentQueue.reset();
    }

    // Initialize working list with all provided people
    public void initQueue(List<String> people, Long duration) {
        assignmentQueue.getWorking().addAll(people);
        presentationLength = duration;
    }

    // Add person to waiting and remove from working
    public void addQueue(String person) {
        assignmentQueue.getWorking().remove(person);
        assignmentQueue.getWaiting().add(person);
    }

    // Remove person from waiting and add to working
    public void removeQueue(String person) {
        assignmentQueue.getWaiting().remove(person);
        assignmentQueue.getWorking().add(person);
    }

    // Remove person from waiting and add to completed
    public void doneQueue(String person) {
        assignmentQueue.getWaiting().remove(person);
        assignmentQueue.getCompleted().add(person);
    }

    // Constructor.
    public Assignment(String name, String type, String description, Double points, String dueDate, String assignmentType) {
        this.name = name;
        this.type = type;
        this.assignmentType = (assignmentType == null || assignmentType.isBlank()) ? "File" : assignmentType;
        this.description = description;
        this.aiRubric = DEFAULT_AI_RUBRIC;
        this.points = points;
        this.dueDate = dueDate; 
        this.timestamp = LocalDateTime.now().format(formatter); // fixed formatting ahhh
        this.resourceType = "none";
        this.resourceUrl = null;
        this.resourceFilename = null;
        this.resourceStoragePath = null;
        // This line is not needed as converter will reset to null after it takes in an empty queue
        // this.assignmentQueue = new AssignmentQueue();
    }

    public void setUrlResource(String url) {
        this.resourceType = "url";
        this.resourceUrl = url;
        this.resourceFilename = null;
        this.resourceStoragePath = null;
    }

    public void setUrlResource(String url, String uploaderUid) {
        this.resourceType = "url";
        this.resourceUrl = url;
        this.resourceFilename = null;
        this.resourceStoragePath =
            (uploaderUid == null || uploaderUid.isBlank()) ? null : uploaderUid + "/url-resource";
    }

    public void setFileResource(String originalFilename, String storagePath) {
        this.resourceType = "file";
        this.resourceFilename = originalFilename;
        this.resourceStoragePath = storagePath;
        this.resourceUrl = null;
    }

    public static Assignment[] init() {
        return new Assignment[] {
            new Assignment("Assignment 1", "Class Homework", "Unit 1 Homework", 1.0, "10/25/2024", "File"),
            new Assignment("Sprint 1 Live Review", "Live Review", "The final review for sprint 1", 1.0, "11/2/2024", "File"),
            new Assignment("Seed", "Seed", "The student's seed grade", 1.0, "11/2/2080", "File"),
        };
    }

    public List<Person> getAssignedGraders() {
        return assignedGraders;
    }

    /**
     * Never returns null: assignments created before the creators join table existed
     * load with no collection at all, and callers treat "no creators" as legacy/unassigned.
     */
    public List<Person> getCreators() {
        if (creators == null) {
            creators = new ArrayList<>();
        }
        return creators;
    }

    public void setAssignedGraders(List<com.open.spring.mvc.person.Person> persons) {
        this.assignedGraders = persons;
    }

    @Override
    public String toString(){
        return this.name;
    }

    public Long getId() {
        return id;
    }

    
    public String getName() {
        return name;
    }
    
    public String getType() {
        return type;
    }

    public String getAssignmentType(){
        return assignmentType;
    }
    
    public String getDescription() {
        return description;
    }
    
    public String getDueDate() {
        return dueDate;
    }
    
    public String getTimestamp() {
        return timestamp;
    }
    
    public List<AssignmentSubmission> getSubmissions() {
        return submissions;
    }
    
    public List<SynergyGrade> getGrades() {
        return grades;
    }
    
    public Double getPoints() {
        return points;
    }
    
    public Long getPresentationLength() {
        return presentationLength;
    }
    
    public AssignmentQueue getAssignmentQueue() {
        return assignmentQueue;
    }

    public String formatTimestamp(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(date);
    }
    
}
