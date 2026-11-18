package com.open.spring.mvc.sprintretro;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Ground 0 sprint reflection, from the Onboarding Challenge page in Pages
 * (_notebooks/Foundation/2025-08-22-sprint1_.objectives.ipynb): the ranked Technical and Professional evaluation items, a summary for each section, and the
 * retrospective questions. Every sprint uses it, so a student's ranks line up across the year.
 *
 * The page renders whatever this returns, so item ids are the only thing it relies on. Never
 * reuse or renumber an id: stored reflections are keyed by them.
 */
public final class SprintRetroTemplate {

    /** One ranked area, such as T03 Git / GitHub. */
    public record Item(String id, String area) {
    }

    /** A group of items with one written summary; the summary is stored under the section key. */
    public record Section(String key, String title, String prompt, List<Item> items) {
    }

    /** What a rank means. */
    public record Level(int rank, String name, String description) {
    }

    /** A written question, stored under its key. */
    public record Prompt(String key, String label) {
    }

    /** One of the two evaluations: its sections, rank scale and closing questions. */
    public record Evaluation(String key, String title, List<Section> sections, List<Level> levels, List<Prompt> prompts) {
    }

    /** The whole reflection. maxRank is the top of both rank scales; 0 means not entered. */
    public record Template(String name, int maxRank, List<Evaluation> evaluations) {
    }

    public static final int MAX_RANK = 5;

    private static final Template GROUND_0 = new Template("Ground 0", MAX_RANK,
            List.of(
                    new Evaluation("technical", "Technical & Learning Evaluation",
                            List.of(
                                    new Section("tools", "Tools & Development Environment",
                                            "Describe how your ability to use your development environment changed during the sprint. Include a specific setup problem, tool, workflow, or technical process that you learned to manage.",
                                            List.of(new Item("T01", "Development Environment"), new Item("T02", "VS Code"),
                                                    new Item("T03", "Git / GitHub"), new Item("T04", "Portfolio / GitHub Pages"))),
                                    new Section("creation", "Development & Creation",
                                            "Describe something you built, modified, customized, or extended during the sprint. Explain a tool, technique, coding challenge, design decision, or AI orchestration process that helped you create or improve your work.",
                                            List.of(new Item("T05", "Tools & Equipment Hacks"), new Item("T06", "Portfolio / Blogging Hacks"),
                                                    new Item("T07", "Theme / Style / Layout"), new Item("T08", "JavaScript / Coding Challenges"),
                                                    new Item("T09", "AI Orchestration"))),
                                    new Section("awareness", "Technical Awareness & Communication",
                                            "Describe how your technical understanding and communication developed during the sprint. Include a specific example of something you learned, a mistake that changed your understanding, or a technical idea you can now explain more clearly.",
                                            List.of(new Item("T10", "Tech / Cyber Growth"), new Item("T11", "Learning Through Mistakes"),
                                                    new Item("T12", "Tech / Cyber Talk")))),
                            List.of(new Level(1, "Explore", "I have encountered or experimented with this technology or skill."),
                                    new Level(2, "Understand", "I can explain it and perform basic tasks with support."),
                                    new Level(3, "Apply", "I can use it independently in my project work."),
                                    new Level(4, "Analyze / Evaluate", "I can troubleshoot problems, compare approaches, and improve my solution."),
                                    new Level(5, "Create / Extend", "I can independently customize, extend, or create solutions using the skill.")),
                            List.of(new Prompt("techRetro", "What can I do now as a developer that I could not do at the beginning of this sprint?"),
                                    new Prompt("techProud", "The technical evidence I am most proud of"),
                                    new Prompt("techProblem", "The biggest technical problem I solved"),
                                    new Prompt("techImprove", "The technical skill I most need to improve"),
                                    new Prompt("techGoal", "My next technical learning goal"))),
                    new Evaluation("professional", "Professional Evaluation",
                            List.of(
                                    new Section("core", "Core Behaviors",
                                            "Describe how you used your class time, responded to expectations, and demonstrated responsibility during the sprint. Include a specific example of a success, difficulty, or change you made.",
                                            List.of(new Item("P01", "Attendance / Tardy"), new Item("P02", "Work Habits"),
                                                    new Item("P03", "Integrity"))),
                                    new Section("collaboration", "Collaboration",
                                            "Describe how you communicated, sought help, helped others, and participated in your team. Include a specific interaction or example.",
                                            List.of(new Item("P04", "Communication"), new Item("P05", "Help Seeking"),
                                                    new Item("P06", "Mentoring / Advocacy"))),
                                    new Section("skills", "Professional Skills",
                                            "Describe how you managed your time, persisted through difficulty, stayed organized, and remained engaged. Include a specific example of how you responded when the work became difficult or frustrating.",
                                            List.of(new Item("P07", "Timeliness"), new Item("P08", "Persistence"),
                                                    new Item("P09", "Organization"), new Item("P10", "Engagement")))),
                            List.of(new Level(1, "Beginning", "I am beginning to recognize and practice this behavior."),
                                    new Level(2, "Developing", "I demonstrate this behavior sometimes, usually with reminders or support."),
                                    new Level(3, "Consistent", "I regularly demonstrate this behavior independently."),
                                    new Level(4, "Strong", "I consistently demonstrate this behavior and adjust when circumstances change."),
                                    new Level(5, "Model", "I consistently demonstrate this behavior and help others develop it.")),
                            List.of(new Prompt("proRetro", "What did I learn about myself as a developer and teammate that I could not have learned from a grade alone?"),
                                    new Prompt("commitBehavior", "One professional behavior I will improve"),
                                    new Prompt("commitSkill", "One technical skill I will improve"),
                                    new Prompt("commitTeam", "One way I will contribute more effectively to my team"),
                                    new Prompt("commitEvidence", "Evidence I will collect during the next sprint")))));

    /** Item id to item, in template order. */
    static final Map<String, Item> ITEMS;

    /** Item id to the title of its section. */
    static final Map<String, String> SECTION_OF;

    /** Keys that hold written text: section summaries and prompts. */
    static final Set<String> TEXT_KEYS;

    static {
        Map<String, Item> items = new LinkedHashMap<>();
        Map<String, String> sectionOf = new LinkedHashMap<>();
        Set<String> textKeys = new LinkedHashSet<>();
        for (Evaluation evaluation : GROUND_0.evaluations()) {
            for (Section section : evaluation.sections()) {
                textKeys.add(section.key());
                for (Item item : section.items()) {
                    items.put(item.id(), item);
                    sectionOf.put(item.id(), section.title());
                }
            }
            evaluation.prompts().forEach(prompt -> textKeys.add(prompt.key()));
        }
        ITEMS = Map.copyOf(items);
        SECTION_OF = Map.copyOf(sectionOf);
        TEXT_KEYS = Set.copyOf(textKeys);
    }

    private SprintRetroTemplate() {
    }

    public static Template ground0() {
        return GROUND_0;
    }

    /** Item ids in template order. */
    static List<String> itemIds() {
        return GROUND_0.evaluations().stream()
                .flatMap(evaluation -> evaluation.sections().stream())
                .flatMap(section -> section.items().stream())
                .map(Item::id)
                .toList();
    }
}
