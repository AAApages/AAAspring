package com.open.spring.mvc.sprintretro;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Sprint reflections: teachers open sprints, students reflect on each with the Ground 0 items
 * (SprintRetroTemplate), and teachers add their own rank and comment beside each item.
 *
 * Ranks run 1 to MAX_RANK; 0 or a missing rank means not entered. A student sees the teacher's
 * part of their own reflection, and their ranks across every sprint for the growth chart.
 */
@Service
public class SprintRetroService {

    static final int MAX_NAME = 100;
    static final int MAX_TEXT = 2000;
    static final int MAX_COMMENT = 1000;
    static final int MAX_CHECKS = 300;

    /** Checklist ids: Pages derives them from the checkboxes on each sprint's own page, so only their form is checked. */
    private static final Pattern CHECK_ID = Pattern.compile("[a-z0-9-]{1,40}");

    /** What the student enters: a rank per item id, text per section or prompt key, and ticked checklist ids. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StudentPart(Map<String, Integer> ranks, Map<String, String> text, List<String> checks) {
        static StudentPart empty() {
            return new StudentPart(Map.of(), Map.of(), List.of());
        }
    }

    /** What the teacher enters: a rank and a comment per item id (comments may also name a section key) and an overall comment. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeacherPart(Map<String, Integer> ranks, Map<String, String> comments, String overall) {
        static TeacherPart empty() {
            return new TeacherPart(Map.of(), Map.of(), null);
        }
    }

    /** One sprint as the school calendar defines it (_data/school_calendar.yml in Pages). */
    public record CalendarSprint(String name, LocalDate startDate, LocalDate endDate) {
    }

    /** A sprint as the API returns it. */
    public record SprintView(Long id, String name, LocalDate startDate, LocalDate endDate) {
        static SprintView of(SprintRetroEntity e) {
            return new SprintView(e.getId(), e.getName(), e.getStartDate(), e.getEndDate());
        }
    }

    /** One student's reflection on one sprint, with the teacher's part. Timestamps are null until that side saves. */
    public record ReflectionView(SprintView sprint, Long personId, String personName, StudentPart student,
            LocalDateTime updatedAt, TeacherPart teacher, LocalDateTime teacherUpdatedAt, String teacherName) {
    }

    /** A row of the teacher's list of students for a sprint. */
    public record RosterRow(Long personId, String name, String uid, int ranked, boolean submitted, boolean reviewed,
            LocalDateTime updatedAt) {
    }

    /** One item's ranks across the sprints of Progress; null where nothing was entered. */
    public record ItemProgress(String id, String area, String section, List<Integer> self, List<Integer> teacher) {
    }

    /**
     * A student's year: the sprints in order and, lined up with them, each item's self and
     * teacher ranks, the student's written text and the teacher's overall comment.
     */
    public record Progress(List<SprintView> sprints, List<ItemProgress> items, List<Map<String, String>> text,
            List<String> teacherOverall) {
    }

    /** Thrown when a request breaks a rule; the message says which, for the API to return. */
    public static class InvalidRetroException extends RuntimeException {
        public InvalidRetroException(String message) {
            super(message);
        }
    }

    /** Thrown when a sprint cannot be deleted because students have reflected on it. */
    public static class SprintInUseException extends RuntimeException {
        public SprintInUseException(String message) {
            super(message);
        }
    }

    private final SprintRetroJpaRepository sprintRepository;
    private final SprintReflectionJpaRepository reflectionRepository;
    private final PersonJpaRepository personRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SprintRetroService(SprintRetroJpaRepository sprintRepository, SprintReflectionJpaRepository reflectionRepository,
            PersonJpaRepository personRepository) {
        this(sprintRepository, reflectionRepository, personRepository, new ObjectMapper(), Clock.systemDefaultZone());
    }

    SprintRetroService(SprintRetroJpaRepository sprintRepository, SprintReflectionJpaRepository reflectionRepository,
            PersonJpaRepository personRepository, ObjectMapper objectMapper, Clock clock) {
        this.sprintRepository = sprintRepository;
        this.reflectionRepository = reflectionRepository;
        this.personRepository = personRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Teachers and admins create sprints and give feedback. */
    public static boolean isTeacher(Person person) {
        return person.hasRoleWithName("ROLE_TEACHER") || person.hasRoleWithName("ROLE_ADMIN");
    }

    public List<SprintView> sprints() {
        return sprintRepository.findAllByOrderByStartDateAscIdAsc().stream().map(SprintView::of).toList();
    }

    @Transactional
    public SprintView createSprint(Person teacher, String name, LocalDate startDate, LocalDate endDate) {
        SprintRetroEntity sprint = new SprintRetroEntity();
        apply(sprint, name, startDate, endDate);
        sprint.setCreatedBy(teacher);
        sprint.setCreatedAt(LocalDateTime.now(clock));
        return SprintView.of(sprintRepository.save(sprint));
    }

    /**
     * Makes the stored sprints match the school calendar, matching by name so reflections stay
     * attached, and returns every sprint. A row is written only when it is new or its dates moved.
     * Sprints the calendar does not list are left alone so no student work is lost.
     */
    @Transactional
    public List<SprintView> syncCalendar(Person teacher, List<CalendarSprint> calendar) {
        if (calendar == null || calendar.isEmpty()) {
            throw new InvalidRetroException("the calendar lists no sprints");
        }
        Map<String, SprintRetroEntity> byName = new LinkedHashMap<>();
        sprintRepository.findAllByOrderByStartDateAscIdAsc().forEach(sprint -> byName.putIfAbsent(sprint.getName(), sprint));
        for (CalendarSprint entry : calendar) {
            String name = trim(entry.name());
            SprintRetroEntity existing = name == null ? null : byName.get(name);
            if (existing == null) {
                createSprint(teacher, entry.name(), entry.startDate(), entry.endDate());
            } else if (!Objects.equals(existing.getStartDate(), entry.startDate())
                    || !Objects.equals(existing.getEndDate(), entry.endDate())) {
                apply(existing, entry.name(), entry.startDate(), entry.endDate());
                sprintRepository.save(existing);
            }
        }
        return sprints();
    }

    /** Renames or re-dates a sprint; empty when there is no sprint with that id. */
    @Transactional
    public Optional<SprintView> updateSprint(Long id, String name, LocalDate startDate, LocalDate endDate) {
        return sprintRepository.findById(id).map(sprint -> {
            apply(sprint, name, startDate, endDate);
            return SprintView.of(sprintRepository.save(sprint));
        });
    }

    /**
     * Deletes a sprint nobody has reflected on; false when there is no such sprint. A sprint with
     * reflections is refused so student work is never lost by accident.
     */
    @Transactional
    public boolean deleteSprint(Long id) {
        Optional<SprintRetroEntity> sprint = sprintRepository.findById(id);
        if (sprint.isEmpty()) {
            return false;
        }
        if (reflectionRepository.existsBySprint(sprint.get())) {
            throw new SprintInUseException("students have reflected on this sprint, so it cannot be deleted");
        }
        sprintRepository.delete(sprint.get());
        return true;
    }

    /** The person's reflection on a sprint, blank when they have not started it; empty when there is no such sprint. */
    @Transactional(readOnly = true)
    public Optional<ReflectionView> reflection(Long sprintId, Person person) {
        return sprintRepository.findById(sprintId)
                .map(sprint -> view(sprint, person, reflectionRepository.findBySprintAndPerson(sprint, person).orElse(null)));
    }

    /** Replaces the student's part of their reflection; empty when there is no such sprint. */
    @Transactional
    public Optional<ReflectionView> saveStudentPart(Long sprintId, Person student, StudentPart part) {
        StudentPart clean = validate(part);
        return sprintRepository.findById(sprintId).map(sprint -> {
            SprintReflectionEntity reflection = findOrCreate(sprint, student);
            reflection.setStudentData(write(clean));
            reflection.setUpdatedAt(LocalDateTime.now(clock));
            return view(sprint, student, reflectionRepository.save(reflection));
        });
    }

    /** Replaces the teacher's part of a student's reflection; empty when there is no such sprint. */
    @Transactional
    public Optional<ReflectionView> saveTeacherPart(Long sprintId, Person student, Person teacher, TeacherPart part) {
        TeacherPart clean = validate(part);
        return sprintRepository.findById(sprintId).map(sprint -> {
            SprintReflectionEntity reflection = findOrCreate(sprint, student);
            reflection.setTeacherData(write(clean));
            reflection.setTeacherUpdatedAt(LocalDateTime.now(clock));
            reflection.setTeacher(teacher);
            return view(sprint, student, reflectionRepository.save(reflection));
        });
    }

    /**
     * Every student, and anyone else who has a reflection on the sprint, with whether they have
     * submitted and been reviewed; empty when there is no such sprint.
     */
    @Transactional(readOnly = true)
    public Optional<List<RosterRow>> roster(Long sprintId) {
        return sprintRepository.findById(sprintId).map(sprint -> {
            Map<Long, SprintReflectionEntity> byPerson = reflectionRepository.findBySprint(sprint).stream()
                    .collect(Collectors.toMap(r -> r.getPerson().getId(), Function.identity(), (a, b) -> a));
            Map<Long, Person> people = new LinkedHashMap<>();
            personRepository.findPeopleWithRole("ROLE_STUDENT").forEach(p -> people.putIfAbsent(p.getId(), p));
            byPerson.values().forEach(r -> people.putIfAbsent(r.getPerson().getId(), r.getPerson()));
            return people.values().stream()
                    .map(person -> row(person, byPerson.get(person.getId())))
                    .sorted(Comparator.comparing((RosterRow row) -> !row.submitted())
                            .thenComparing(row -> row.name() == null ? "" : row.name().toLowerCase()))
                    .toList();
        });
    }

    /** The person's ranks and writing across every sprint, for the growth view. */
    @Transactional(readOnly = true)
    public Progress progress(Person person) {
        List<SprintRetroEntity> sprints = sprintRepository.findAllByOrderByStartDateAscIdAsc();
        Map<Long, SprintReflectionEntity> bySprint = reflectionRepository.findByPerson(person).stream()
                .collect(Collectors.toMap(r -> r.getSprint().getId(), Function.identity(), (a, b) -> a));
        List<StudentPart> students = new ArrayList<>();
        List<TeacherPart> teachers = new ArrayList<>();
        for (SprintRetroEntity sprint : sprints) {
            SprintReflectionEntity reflection = bySprint.get(sprint.getId());
            students.add(readStudent(reflection));
            teachers.add(readTeacher(reflection));
        }
        List<ItemProgress> items = SprintRetroTemplate.itemIds().stream()
                .map(id -> new ItemProgress(id, SprintRetroTemplate.ITEMS.get(id).area(), SprintRetroTemplate.SECTION_OF.get(id),
                        students.stream().map(s -> s.ranks().get(id)).toList(),
                        teachers.stream().map(t -> t.ranks().get(id)).toList()))
                .toList();
        return new Progress(sprints.stream().map(SprintView::of).toList(), items,
                students.stream().map(StudentPart::text).toList(),
                teachers.stream().map(TeacherPart::overall).toList());
    }

    // ---------- validation ----------

    /** Checks a student's part and returns it with blanks, zero ranks and duplicate checks removed. */
    static StudentPart validate(StudentPart part) {
        if (part == null) {
            throw new InvalidRetroException("reflection is required");
        }
        List<String> problems = new ArrayList<>();
        Map<String, Integer> ranks = cleanRanks(part.ranks(), problems);
        Map<String, String> text = new LinkedHashMap<>();
        if (part.text() != null) {
            part.text().forEach((key, value) -> {
                if (!SprintRetroTemplate.TEXT_KEYS.contains(key)) {
                    problems.add("unknown question " + key);
                    return;
                }
                String clean = trim(value);
                if (clean == null) {
                    return;
                }
                if (clean.length() > MAX_TEXT) {
                    problems.add("keep each answer to " + MAX_TEXT + " characters");
                }
                text.put(key, clean);
            });
        }
        Set<String> checks = new LinkedHashSet<>();
        if (part.checks() != null) {
            if (part.checks().size() > MAX_CHECKS) {
                problems.add("at most " + MAX_CHECKS + " checklist items");
            }
            for (String id : part.checks()) {
                if (id == null || !CHECK_ID.matcher(id).matches()) {
                    problems.add("invalid checklist item " + id);
                } else {
                    checks.add(id);
                }
            }
        }
        throwIfAny(problems);
        return new StudentPart(ranks, text, List.copyOf(checks));
    }

    /** Checks a teacher's part and returns it with blanks and zero ranks removed. */
    static TeacherPart validate(TeacherPart part) {
        if (part == null) {
            throw new InvalidRetroException("feedback is required");
        }
        List<String> problems = new ArrayList<>();
        Map<String, Integer> ranks = cleanRanks(part.ranks(), problems);
        Map<String, String> comments = new LinkedHashMap<>();
        if (part.comments() != null) {
            part.comments().forEach((key, value) -> {
                if (!SprintRetroTemplate.ITEMS.containsKey(key) && !SprintRetroTemplate.TEXT_KEYS.contains(key)) {
                    problems.add("unknown item " + key);
                    return;
                }
                String clean = trim(value);
                if (clean == null) {
                    return;
                }
                if (clean.length() > MAX_COMMENT) {
                    problems.add("keep each comment to " + MAX_COMMENT + " characters");
                }
                comments.put(key, clean);
            });
        }
        String overall = trim(part.overall());
        if (overall != null && overall.length() > MAX_TEXT) {
            problems.add("keep the overall comment to " + MAX_TEXT + " characters");
        }
        throwIfAny(problems);
        return new TeacherPart(ranks, comments, overall);
    }

    private static Map<String, Integer> cleanRanks(Map<String, Integer> ranks, List<String> problems) {
        Map<String, Integer> clean = new LinkedHashMap<>();
        if (ranks == null) {
            return clean;
        }
        ranks.forEach((id, rank) -> {
            if (!SprintRetroTemplate.ITEMS.containsKey(id)) {
                problems.add("unknown item " + id);
            } else if (rank != null && (rank < 0 || rank > SprintRetroTemplate.MAX_RANK)) {
                problems.add(id + " needs a rank from 0 to " + SprintRetroTemplate.MAX_RANK);
            } else if (rank != null && rank > 0) {
                clean.put(id, rank);
            }
        });
        return clean;
    }

    private static void apply(SprintRetroEntity sprint, String name, LocalDate startDate, LocalDate endDate) {
        List<String> problems = new ArrayList<>();
        String clean = trim(name);
        if (clean == null) {
            problems.add("give the sprint a name");
        } else if (clean.length() > MAX_NAME) {
            problems.add("keep the name to " + MAX_NAME + " characters");
        }
        if (startDate == null) {
            problems.add("pick a start date");
        } else if (endDate != null && endDate.isBefore(startDate)) {
            problems.add("the end date must be on or after the start date");
        }
        throwIfAny(problems);
        sprint.setName(clean);
        sprint.setStartDate(startDate);
        sprint.setEndDate(endDate);
    }

    private static void throwIfAny(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new InvalidRetroException(String.join("; ", problems.stream().distinct().toList()));
        }
    }

    // ---------- storage ----------

    private SprintReflectionEntity findOrCreate(SprintRetroEntity sprint, Person person) {
        return reflectionRepository.findBySprintAndPerson(sprint, person).orElseGet(() -> {
            SprintReflectionEntity created = new SprintReflectionEntity();
            created.setSprint(sprint);
            created.setPerson(person);
            return created;
        });
    }

    private RosterRow row(Person person, SprintReflectionEntity reflection) {
        StudentPart student = readStudent(reflection);
        return new RosterRow(person.getId(), person.getName(), person.getUid(), student.ranks().size(),
                reflection != null && reflection.getStudentData() != null,
                reflection != null && reflection.getTeacherData() != null,
                reflection == null ? null : reflection.getUpdatedAt());
    }

    private ReflectionView view(SprintRetroEntity sprint, Person person, SprintReflectionEntity reflection) {
        Person teacher = reflection == null ? null : reflection.getTeacher();
        return new ReflectionView(SprintView.of(sprint), person.getId(), person.getName(),
                readStudent(reflection), reflection == null ? null : reflection.getUpdatedAt(),
                readTeacher(reflection), reflection == null ? null : reflection.getTeacherUpdatedAt(),
                teacher == null ? null : teacher.getName());
    }

    private StudentPart readStudent(SprintReflectionEntity reflection) {
        if (reflection == null || reflection.getStudentData() == null) {
            return StudentPart.empty();
        }
        StudentPart part = read(reflection.getStudentData(), StudentPart.class);
        return new StudentPart(orEmpty(part.ranks()), orEmpty(part.text()),
                part.checks() == null ? List.of() : part.checks());
    }

    private TeacherPart readTeacher(SprintReflectionEntity reflection) {
        if (reflection == null || reflection.getTeacherData() == null) {
            return TeacherPart.empty();
        }
        TeacherPart part = read(reflection.getTeacherData(), TeacherPart.class);
        return new TeacherPart(orEmpty(part.ranks()), orEmpty(part.comments()), part.overall());
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored sprint reflection is not valid JSON", e);
        }
    }

    private String write(Object part) {
        try {
            return objectMapper.writeValueAsString(part);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store the sprint reflection", e);
        }
    }

    private static <K, V> Map<K, V> orEmpty(Map<K, V> map) {
        return map == null ? Map.of() : map;
    }

    private static String trim(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
