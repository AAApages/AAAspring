package com.open.spring.system;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.announcement.Announcement;
import com.open.spring.mvc.announcement.AnnouncementJPA;
import com.open.spring.mvc.assignments.Assignment;
import com.open.spring.mvc.assignments.AssignmentJpaRepository;
import com.open.spring.mvc.assignments.AssignmentSubmission;
import com.open.spring.mvc.assignments.AssignmentSubmissionJPA;
import com.open.spring.mvc.assignments.AssignmentSyncAccountProvisioner;
import com.open.spring.mvc.bank.BankJpaRepository;
import com.open.spring.mvc.bank.BankService;
import com.open.spring.mvc.bathroom.BathroomQueue;
import com.open.spring.mvc.bathroom.BathroomQueueJPARepository;
import com.open.spring.mvc.bathroom.Issue;
import com.open.spring.mvc.bathroom.IssueJPARepository;
import com.open.spring.mvc.bathroom.Teacher;
import com.open.spring.mvc.bathroom.TeacherJpaRepository;
import com.open.spring.mvc.bathroom.TinkleJPARepository;
import com.open.spring.mvc.comment.Comment;
import com.open.spring.mvc.comment.CommentJPA;
import com.open.spring.mvc.hardAssets.HardAssetsRepository;
import com.open.spring.mvc.jokes.Jokes;
import com.open.spring.mvc.jokes.JokesJpaRepository;
import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.media.MediaJpaRepository;
import com.open.spring.mvc.media.Score;
import com.open.spring.mvc.note.Note;
import com.open.spring.mvc.note.NoteJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonDetailsService;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.person.PersonRole;
import com.open.spring.mvc.person.PersonRoleJpaRepository;
import com.open.spring.mvc.quiz.QuizScore;
import com.open.spring.mvc.quiz.QuizScoreRepository;
import com.open.spring.mvc.resume.Resume;
import com.open.spring.mvc.resume.ResumeJpaRepository;
import com.open.spring.mvc.rpg.adventure.Adventure;
import com.open.spring.mvc.rpg.adventure.AdventureJpaRepository;
import com.open.spring.mvc.rpg.games.Game;
import com.open.spring.mvc.rpg.games.UnifiedGameRepository;
import com.open.spring.mvc.stats.Stats; // curators - stats api
import com.open.spring.mvc.stats.StatsRepository;
import com.open.spring.mvc.student.StudentQueue;
import com.open.spring.mvc.student.StudentQueueJPARepository;
import com.open.spring.mvc.synergy.SynergyGrade;
import com.open.spring.mvc.synergy.SynergyGradeJpaRepository;


@Component
@Configuration // Scans Application for ModelInit Bean, this detects CommandLineRunner
public class ModelInit {
    @Autowired JokesJpaRepository jokesRepo;
    @Autowired HardAssetsRepository hardAssetsRepository;
    @Autowired NoteJpaRepository noteRepo;
    @Autowired PersonRoleJpaRepository roleJpaRepository;
    @Autowired PersonDetailsService personDetailsService;
    @Autowired PersonJpaRepository personJpaRepository;
    @Autowired AnnouncementJPA announcementJPA;
    @Autowired CommentJPA CommentJPA;
    @Autowired TinkleJPARepository tinkleJPA;
    @Autowired BathroomQueueJPARepository queueJPA;
    @Autowired TeacherJpaRepository teacherJPARepository;
    @Autowired IssueJPARepository issueJPARepository;
    @Autowired
    DataSource dataSource;
    @Autowired
    AdventureJpaRepository adventureJpaRepository;
    @Autowired
    UnifiedGameRepository gameJpaRepository;
    
    @Autowired AssignmentJpaRepository assignmentJpaRepository;
    @Autowired AssignmentSubmissionJPA submissionJPA;
    @Autowired AssignmentSyncAccountProvisioner assignmentSyncAccountProvisioner;
    @Autowired SynergyGradeJpaRepository gradeJpaRepository;
    @Autowired StudentQueueJPARepository studentQueueJPA;
    @Autowired BankJpaRepository bankJpaRepository;
    @Autowired BankService bankService;
    
    @Autowired MediaJpaRepository mediaJpaRepository;
    @Autowired GroupsJpaRepository groupsJpaRepository;
    @Autowired CourseGroupProperties courseGroupProperties;
    @Autowired QuizScoreRepository quizScoreRepository;
    @Autowired ResumeJpaRepository resumeJpaRepository;
    @Autowired StatsRepository statsRepository; // curators - stats

    @Bean
    @Transactional
    CommandLineRunner run() {
        return args -> {
            // Ensure unified manual tables exist before any seeding.
            if (dataSource != null) {
                try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
                    String createAdventure = buildAdventureTableSql(conn);
                    st.execute(createAdventure);
                    System.out.println("Ensured 'adventure' table exists");

                    try {
                        st.execute("ALTER TABLE adventure ADD COLUMN details TEXT;");
                        System.out.println("Added 'details' column to 'adventure' table");
                    } catch (SQLException ignore) {
                        // column may already exist; ignore
                    }

                    try {
                        st.execute("ALTER TABLE assignment ADD COLUMN ai_rubric TEXT;");
                        System.out.println("Added 'ai_rubric' column to 'assignment' table");
                    } catch (SQLException ignore) {
                        // column may already exist; ignore
                    }
                    String defaultAssignmentRubric = com.open.spring.mvc.assignments.Assignment.DEFAULT_AI_RUBRIC.replace("'", "''");
                    st.executeUpdate("UPDATE assignment SET ai_rubric = '" + defaultAssignmentRubric
                            + "' WHERE ai_rubric IS NULL OR TRIM(ai_rubric) = ''");

                    try {
                        Iterable<Adventure> all = adventureJpaRepository.findAll();
                        for (Adventure adv : all) {
                            if (adv.getDetails() == null || adv.getDetails().trim().isEmpty()) {
                                String choiceText = adv.getChoiceText();
                                String answerContent = adv.getAnswerContent();
                                String rubricCriteria = adv.getRubricCriteria();
                                String rubricRuid = adv.getRubricRuid();
                                StringBuilder sb = new StringBuilder();
                                sb.append('{');
                                sb.append("\"choiceId\":").append(adv.getChoiceId() == null ? "null" : adv.getChoiceId()).append(',');
                                sb.append("\"choiceText\":").append(choiceText == null ? "null" : ("\"" + choiceText.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" )).append(',');
                                sb.append("\"choiceIsCorrect\":").append(adv.getChoiceIsCorrect() == null ? "null" : adv.getChoiceIsCorrect()).append(',');
                                sb.append("\"answerIsCorrect\":").append(adv.getAnswerIsCorrect() == null ? "null" : adv.getAnswerIsCorrect()).append(',');
                                sb.append("\"answerContent\":").append(answerContent == null ? "null" : ("\"" + answerContent.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" )).append(',');
                                sb.append("\"chatScore\":").append(adv.getChatScore() == null ? "null" : adv.getChatScore()).append(',');
                                sb.append("\"rubricRuid\":").append(rubricRuid == null ? "null" : ("\"" + rubricRuid.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" )).append(',');
                                sb.append("\"rubricCriteria\":").append(rubricCriteria == null ? "null" : ("\"" + rubricCriteria.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" ));
                                sb.append('}');
                                adv.setDetails(sb.toString());
                                try { adventureJpaRepository.save(adv); } catch (Exception ignored) {}
                            }
                        }
                        System.out.println("Migrated Adventure rows into 'details' JSON where missing");
                    } catch (Exception ignore) {
                    }

                    String createGames = buildGamesTableSql(conn);
                    st.execute(createGames);
                    System.out.println("Ensured 'games' table exists");

                    try {
                        long gameCount = 0L;
                        try { gameCount = gameJpaRepository.count(); } catch (Exception ignore) { gameCount = 0L; }
                        if (gameCount == 0L) {
                            Game[] defaults = Game.init();
                            for (Game g : defaults) {
                                try { gameJpaRepository.save(g); } catch (Exception ignored) {}
                            }
                            System.out.println("Seeded default Game rows via Game.init()");
                        }
                    } catch (Throwable t) {
                    }
                } catch (SQLException e) {
                    System.err.println("Failed to ensure manual tables: " + e.getMessage());
                }
            }

            if (new File("volumes/.skip-modelinit").exists()) {
                System.out.println("Skip flag detected, ModelInit will not run");
                return;
            }

            // Capture the count before provisioning so a bot created in an empty database
            // does not prevent the normal sample users from being initialized.
            long personCount = personJpaRepository.count();
            assignmentSyncAccountProvisioner.provisionIfConfigured();
            if (personCount > 0) {
                System.out.println("Database already contains " + personCount + " persons. Skipping ModelInit...");
                return;
            }
        
            System.out.println("Loading default sample data...");
            Person[] personArray = Person.init();
            for (Person person : personArray) {
                List<Person> personFound = personDetailsService.list(person.getName(), person.getEmail());
                if (personFound.isEmpty()) { 
                    List<PersonRole> updatedRoles = new ArrayList<>();
                    for (PersonRole role : person.getRoles()) {
                        PersonRole roleFound = roleJpaRepository.findByName(role.getName());
                        if (roleFound == null) {
                            roleJpaRepository.save(role);
                            roleFound = role;
                        }
                        updatedRoles.add(roleFound);
                    }
                    person.setRoles(updatedRoles);
                    
                    // Ensure password is not null or empty
                    if (person.getPassword() == null || person.getPassword().isEmpty()) {
                        person.setPassword("DefaultPassword123!"); // Must satisfy Person.checkPassword()
                    }
                    
                    personDetailsService.save(person);
                    
                    String text = "Test " + person.getEmail();
                    Note n = new Note(text, person);
                    noteRepo.save(n);
                }
            }

            for (String groupName : courseGroupProperties.getGroupNames()) {
                if (groupsJpaRepository.findByName(groupName).isEmpty()) {
                    Groups group = new Groups();
                    group.setName(groupName);
                    group.setCourse(courseGroupProperties.courseFor(groupName));
                    group.setPeriod(courseGroupProperties.periodFor(groupName));
                    groupsJpaRepository.save(group);
                }
            }
            
            List<Announcement> announcements = Announcement.init();
            for (Announcement announcement : announcements) {
                Announcement announcementFound = announcementJPA.findByAuthor(announcement.getAuthor());  
                if (announcementFound == null) {
                    announcementJPA.save(new Announcement(announcement.getAuthor(), announcement.getTitle(), announcement.getBody(), announcement.getTags())); // JPA save
                }
            }
            // Adventure sub-APIs have been merged into a single Adventure table/entity.



            
            List<Comment> Comments = Comment.init();
            for (Comment Comment : Comments) {
                List<Comment> CommentFound = CommentJPA.findByAssignment(Comment.getAssignment()); 
                if (CommentFound.isEmpty()) {
                    CommentJPA.save(new Comment(Comment.getAssignment(), Comment.getAuthor(), Comment.getText())); // JPA save
                }
            }


            String[] jokesArray = Jokes.init();
            for (String joke : jokesArray) {
                List<Jokes> jokeFound = jokesRepo.findByJokeIgnoreCase(joke);  // JPA lookup
                if (jokeFound.size() == 0) {
                    jokesRepo.save(new Jokes(null, joke, 0, 0)); // JPA save
                }
            }

            // Tinkle[] tinkleArray = Tinkle.init(personArray);
            // for(Tinkle tinkle: tinkleArray) {
            //     // List<Tinkle> tinkleFound = 
            //     Optional<Tinkle> tinkleFound = tinkleJPA.findByPersonName(tinkle.getPersonName());
            //     if(tinkleFound.isEmpty()) {
            //         tinkleJPA.save(tinkle);
            //     }
            // }

            BathroomQueue[] queueArray = BathroomQueue.init();
            for(BathroomQueue queue: queueArray) {
                Optional<BathroomQueue> queueFound = queueJPA.findByTeacherEmail(queue.getTeacherEmail());
                if(queueFound.isEmpty()) {
                    queueJPA.save(queue);
                }
            }

            StudentQueue[] studentQueueArray = StudentQueue.init();
            for(StudentQueue queue: studentQueueArray) {
                Optional<StudentQueue> queueFound = studentQueueJPA.findByTeacherEmail(queue.getTeacherEmail());
                if(queueFound.isEmpty()) {
                    studentQueueJPA.save(queue);
                }
            }

            // Teacher API is populated with starting announcements
            List<Teacher> teachers = Teacher.init();
            for (Teacher teacher : teachers) {
            List<Teacher> existTeachers = teacherJPARepository.findByFirstnameIgnoreCaseAndLastnameIgnoreCase(teacher.getFirstname(), teacher.getLastname());
                if(existTeachers.isEmpty())
               teacherJPARepository.save(teacher); // JPA save
            }
            
            // Issue database initialization
            Issue[] issueArray = Issue.init();
            for (Issue issue : issueArray) {
                List<Issue> issueFound = issueJPARepository.findByIssueAndBathroomIgnoreCase(issue.getIssue(), issue.getBathroom());
                if (issueFound.isEmpty()) {
                    issueJPARepository.save(issue);
                }
            }
            
            // Assignment database is populated with sample assignments
            Assignment[] assignmentArray = Assignment.init();
            for (Assignment assignment : assignmentArray) {
                Assignment assignmentFound = assignmentJpaRepository.findByName(assignment.getName());
                if (assignmentFound == null) { // if the assignment doesn't exist
                    Assignment newAssignment = new Assignment(assignment.getName(), assignment.getType(), assignment.getDescription(), assignment.getPoints(), assignment.getDueDate(), assignment.getAssignmentType());
                    assignmentJpaRepository.save(newAssignment);
                }
            }

            // Now call the non-static init() method
            String[][] gradeArray = SynergyGrade.init();
            for (String[] gradeInfo : gradeArray) {
                Double gradeValue = Double.parseDouble(gradeInfo[0]);
                Assignment assignment = assignmentJpaRepository.findByName(gradeInfo[1]);
                Person student = personJpaRepository.findByUid(gradeInfo[2]);

                if (assignment == null || student == null) {
                    System.out.println("Skipping SynergyGrade seed: missing assignment or student for " + gradeInfo[1] + " / " + gradeInfo[2]);
                    continue;
                }

                SynergyGrade gradeFound = gradeJpaRepository.findByAssignmentAndStudent(assignment, student);
                if (gradeFound == null) { // If the grade doesn't exist
                    SynergyGrade newGrade = new SynergyGrade(gradeValue, assignment, student);
                    gradeJpaRepository.save(newGrade);
                }
            }


            //Media Bias Table

            List<Score> scores = new ArrayList<>();
            scores.add(new Score("Thomas Edison", 0));
            for (Score score : scores) {
                List<Score> existingPlayers = mediaJpaRepository.findByPersonName(score.getPersonName());

                if (existingPlayers.isEmpty()) {
                    mediaJpaRepository.save(score);
                }
            }

            // Quiz Score initialization (guarded in case the table doesn't exist yet)
            try {
                QuizScore[] quizScoreArray = QuizScore.init();
                for (QuizScore quizScore : quizScoreArray) {
                    List<QuizScore> existingScores = quizScoreRepository
                        .findByUsernameIgnoreCaseOrderByScoreDesc(quizScore.getUsername());

                    boolean scoreExists = existingScores.stream()
                        .anyMatch(s -> s.getScore() == quizScore.getScore());

                    if (!scoreExists) {
                        quizScoreRepository.save(quizScore);
                    }
                }
            } catch (Exception ignored) {
                // If the quiz_scores table is missing or unavailable at startup, skip seeding
            }

            // Resume initialization via static init on Resume class (guard missing table)
            try {
                Resume[] resumes = Resume.init();
                for (Resume resume : resumes) {
                    Optional<Resume> existing = resumeJpaRepository.findByUsername(resume.getUsername());
                    if (existing.isEmpty()) {
                        resumeJpaRepository.save(resume);
                    }
                }
            } catch (Exception ignored) {
            }

            try { // initialize Stats data
                Stats[] statsArray = {
                    new Stats(null, "tobytest", "frontend", 1, Boolean.TRUE, 185.0, .92),
                    new Stats(null, "tobytest", "backend", 1, Boolean.FALSE, 0.0, null),
                    new Stats(null, "tobytest", "ai", 2, Boolean.TRUE, 240.5, .95),
                    new Stats(null, "hoptest", "data", 1, Boolean.TRUE, 142.3, .88),
                    new Stats(null, "hoptest", "resume", 3, Boolean.FALSE, 15.2, null),
                    new Stats(null, "curietest", "frontend", 2, Boolean.TRUE, 98.6, 0.90),
                    new Stats(null, "curietest", "backend", 2, Boolean.FALSE, 35.4, null),
                };

                for (Stats stats : statsArray) {
                    Optional<Stats> statsFound = statsRepository.findByUsernameAndModuleAndSubmodule(
                            stats.getUsername(), stats.getModule(), stats.getSubmodule());
                    if (statsFound.isEmpty()) {
                        statsRepository.save(stats);
                    }
                }
            } catch (Exception e) {
                // Handle exception, e.g., log it, but don't stop startup
                System.err.println("Error initializing Stats data: " + e.getMessage());
            }
        };
    }

    private boolean isSqliteDatabase() {
        if (dataSource == null) {
            return false;
        }

        try (Connection connection = dataSource.getConnection()) {
            String jdbcUrl = connection.getMetaData().getURL();
            return jdbcUrl != null && jdbcUrl.startsWith("jdbc:sqlite:");
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean isMySqlDatabase(Connection connection) throws SQLException {
        String jdbcUrl = connection.getMetaData().getURL();
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:mysql:");
    }

    private String quoteIdentifier(Connection connection, String identifier) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            return identifier;
        }

        String safeIdentifier = identifier.replace(quote, quote + quote);
        return quote + safeIdentifier + quote;
    }

    private String buildAdventureTableSql(Connection connection) throws SQLException {
        String idColumn = isMySqlDatabase(connection)
            ? quoteIdentifier(connection, "id") + " BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"
            : quoteIdentifier(connection, "id") + " INTEGER PRIMARY KEY AUTOINCREMENT";

        return "CREATE TABLE IF NOT EXISTS " + quoteIdentifier(connection, "adventure") + " ("
                + idColumn + ","
                + quoteIdentifier(connection, "person_id") + " INTEGER,"
                + quoteIdentifier(connection, "person_uid") + " TEXT,"
                + quoteIdentifier(connection, "question_id") + " INTEGER,"
                + quoteIdentifier(connection, "question_title") + " TEXT,"
                + quoteIdentifier(connection, "question_content") + " TEXT,"
                + quoteIdentifier(connection, "question_category") + " TEXT,"
                + quoteIdentifier(connection, "question_points") + " INTEGER,"
                + quoteIdentifier(connection, "choice_id") + " INTEGER,"
                + quoteIdentifier(connection, "choice_text") + " TEXT,"
                + quoteIdentifier(connection, "choice_is_correct") + " INTEGER,"
                + quoteIdentifier(connection, "answer_is_correct") + " INTEGER,"
                + quoteIdentifier(connection, "answer_content") + " TEXT,"
                + quoteIdentifier(connection, "chat_score") + " INTEGER,"
                + quoteIdentifier(connection, "rubric_ruid") + " TEXT,"
                + quoteIdentifier(connection, "rubric_criteria") + " TEXT,"
                + quoteIdentifier(connection, "balance") + " REAL,"
                + quoteIdentifier(connection, "created_at") + " TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ");";
    }

    private String buildGamesTableSql(Connection connection) throws SQLException {
        String idColumn = isMySqlDatabase(connection)
            ? quoteIdentifier(connection, "id") + " BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"
            : quoteIdentifier(connection, "id") + " INTEGER PRIMARY KEY AUTOINCREMENT";

        return "CREATE TABLE IF NOT EXISTS " + quoteIdentifier(connection, "games") + " ("
                + idColumn + ","
                + quoteIdentifier(connection, "person_id") + " INTEGER,"
                + quoteIdentifier(connection, "person_uid") + " TEXT,"
                + quoteIdentifier(connection, "type") + " TEXT,"
                + quoteIdentifier(connection, "tx_id") + " TEXT,"
                + quoteIdentifier(connection, "bet_amount") + " REAL,"
                + quoteIdentifier(connection, "amount") + " REAL,"
                + quoteIdentifier(connection, "balance") + " REAL,"
                + quoteIdentifier(connection, "result") + " TEXT,"
                + quoteIdentifier(connection, "success") + " INTEGER,"
                + quoteIdentifier(connection, "details") + " TEXT,"
                + quoteIdentifier(connection, "created_at") + " TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ");";
    }
}
