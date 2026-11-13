package com.open.spring.mvc.assignments;

import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Generates a thorough, assignment-specific AI grading rubric from the assignment's
 * own page content, instead of every assignment relying on the generic
 * {@link Assignment#DEFAULT_AI_RUBRIC}. Failures always fall back to the caller using
 * that default — rubric generation is a best-effort enhancement, never a hard
 * dependency of assignment creation.
 */
@Service
public class AssignmentRubricService {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentRubricService.class);
    private static final String DEFAULT_GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent";
    private static final String FALLBACK_GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent";
    private static final int MAX_PAGE_CONTENT_CHARS = 24000;
    private static final Duration GEMINI_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern TIER_SCORE = Pattern.compile("(?im)^[\\s#*_]*Score\\s+([0-9]+(?:\\.[0-9]+)?)\\b");
    // "Hack #1 (Weight: 60%)" as it appears in the page text (markdown heading, HTML stripped).
    private static final Pattern WEIGHTED_HACK = Pattern.compile(
            "(?i)\\bHack\\s*#?\\s*(\\d+)\\s*[(\\[]?\\s*(?:weight|worth)\\s*[:=-]?\\s*(\\d+(?:\\.\\d+)?)\\s*%?\\s*[)\\]]?");
    private static final int MAX_HACK_INSTRUCTION_CHARS = 600;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String geminiApiKey;
    private final String geminiApiUrl;

    public AssignmentRubricService(
            ObjectMapper objectMapper,
            @Value("${gemini.api.key:}") String geminiApiKey,
            @Value("${gemini.api.url:" + DEFAULT_GEMINI_API_URL + "}") String geminiApiUrl) {
        this.objectMapper = objectMapper;
        this.geminiApiKey = geminiApiKey;
        this.geminiApiUrl = geminiApiUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * @param assignmentName the assignment's name/title
     * @param description    the assignment's short description, if any
     * @param pageContent    the full text of the assignment/lesson page this assignment
     *                       was created from — the primary grounding for the rubric
     * @param maxScore       the assignment's configured maximum score
     * @return a tiered grading rubric, or {@code null} if generation isn't possible
     *         (no API key configured, empty page content, or the AI call failed)
     */
    public String generateRubric(
            String assignmentName,
            String description,
            String pageContent,
            Double maxScore) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            logger.warn("Skipping assignment rubric generation: GEMINI_API_KEY is not configured");
            return null;
        }
        if (maxScore == null || !Double.isFinite(maxScore) || maxScore <= 0) {
            logger.warn("Skipping assignment rubric generation for '{}': invalid maximum score {}",
                    assignmentName, maxScore);
            return null;
        }

        String sourceContent = pageContent == null || pageContent.isBlank()
                ? (description == null || description.isBlank() ? assignmentName : description)
                : pageContent;
        if (sourceContent == null || sourceContent.isBlank()) {
            logger.warn("Skipping assignment rubric generation: assignment has no name, description, or page content");
            return null;
        }

        String prompt = buildPrompt(assignmentName, description, sourceContent, maxScore);

        try {
            JsonNode response = objectMapper.readTree(callGemini(prompt));
            String rubric = response.path("candidates").path(0).path("content").path("parts").path(0)
                    .path("text").asText("").trim();
            if (!isAiRubricReady(rubric, maxScore)) {
                logger.warn("Discarding generated rubric for '{}': its tiers are missing or not scaled to {}",
                        assignmentName, formatScore(aiTopScore(maxScore)));
                return null;
            }
            return rubric;
        } catch (Exception e) {
            logger.warn("Assignment rubric generation failed using {}: {}", geminiApiUrl, e.getMessage());
            return null;
        }
    }

    /** The scale grades are stored on: the assignment's points, or 1 when points aren't set. */
    public static double maxScore(Double points) {
        return points == null || !Double.isFinite(points) || points <= 0 ? 1.0 : points;
    }

    /**
     * The highest score the AI may give: 90% of the points. Full marks are rare and left
     * for the teacher to award by hand.
     */
    public static double aiTopScore(double maxScore) {
        return Math.round(maxScore * 0.9 * 100.0) / 100.0;
    }

    /**
     * Whether {@code rubric} is an assignment-specific AI rubric that grading can trust: not the
     * built-in default, with at least two "Score N" tiers, all at or below {@link #aiTopScore},
     * and the highest one at it. Older rubrics written on a fixed 4-point scale fail this unless
     * the assignment happens to be on that scale, so they get regenerated instead of producing
     * grades on the wrong scale (e.g. 10/100 for a "Score 2" submission).
     */
    public static boolean isAiRubricReady(String rubric, Double points) {
        if (rubric == null || rubric.isBlank() || rubric.strip().equals(Assignment.DEFAULT_AI_RUBRIC.strip())) {
            return false;
        }
        double max = aiTopScore(maxScore(points));
        Matcher matcher = TIER_SCORE.matcher(rubric);
        int tiers = 0;
        double highest = 0;
        while (matcher.find()) {
            double tier = Double.parseDouble(matcher.group(1));
            if (tier < 0 || tier > max) {
                return false;
            }
            highest = Math.max(highest, tier);
            tiers++;
        }
        return tiers >= 2 && max - highest <= Math.max(0.01, max * 0.01);
    }

    /** Submission types the submit endpoints understand (Assignment#assignmentType). */
    public static final List<String> SUBMISSION_TYPES = List.of("text", "link", "github_issue", "code", "file");

    private static final Pattern DUE_DATE = Pattern.compile("^(0?[1-9]|1[0-2])/(0?[1-9]|[12][0-9]|3[01])/\\d{4}$");

    /**
     * One result of {@link #runChecks}: {@code ok} false means the assignment has that problem, and
     * {@code fix} says how the tracker's Fix button repairs it (see {@link Fix}).
     */
    public record Check(String check, boolean ok, String message, Fix fix) {
    }

    /**
     * How a failed check is fixed from the tracker. EDIT_* open the edit form on that field,
     * REGENERATE_RUBRIC asks the AI for a new rubric, REGRADE_OUT_OF_RANGE re-grades submissions
     * above the points, and NONE means there is nothing to do but wait (the AI queue).
     */
    public enum Fix {
        EDIT_NAME, EDIT_POINTS, EDIT_DUE_DATE, EDIT_SUBMISSION_TYPE, EDIT_CONTENT_URL, REGENERATE_RUBRIC,
        REGRADE_OUT_OF_RANGE, NONE
    }

    /**
     * Health checks for the tracker's "Run checks" tool: flags missing or invalid values that make
     * an assignment fail to accept submissions or grade correctly.
     */
    public static List<Check> runChecks(Assignment assignment, List<AssignmentSubmission> submissions) {
        List<Check> checks = new java.util.ArrayList<>();
        String name = assignment.getName();
        checks.add(new Check("Name", name != null && !name.isBlank(), "Assignment name is blank", Fix.EDIT_NAME));
        Double points = assignment.getPoints();
        checks.add(new Check("Points", points != null && points > 0, "Points must be greater than 0 (got " + points + ")",
                Fix.EDIT_POINTS));
        String due = assignment.getDueDate();
        checks.add(new Check("Due date", due != null && DUE_DATE.matcher(due.trim()).matches(),
                "Due date must be MM/DD/YYYY (got " + due + ")", Fix.EDIT_DUE_DATE));
        String type = assignment.getAssignmentType();
        checks.add(new Check("Submission type",
                type != null && SUBMISSION_TYPES.contains(type.trim().toLowerCase(java.util.Locale.ROOT)),
                "Submission type must be one of " + SUBMISSION_TYPES + " (got " + type + ")", Fix.EDIT_SUBMISSION_TYPE));
        String contentUrl = assignment.getContentUrl();
        checks.add(new Check("Content URL", contentUrl != null && !contentUrl.isBlank(),
                "No content URL, so the frontend page can't find this assignment", Fix.EDIT_CONTENT_URL));
        checks.add(new Check("AI rubric", isAiRubricReady(assignment.getAiRubric(), points),
                "AI rubric is missing or not on this assignment's points scale; grading falls back to the default rubric",
                Fix.REGENERATE_RUBRIC));
        double max = maxScore(points);
        long overPoints = submissions.stream()
                .filter(s -> s.getGrade() != null && s.getGrade() > max + 0.000001).count();
        checks.add(new Check("Grades in range", overPoints == 0, overPoints + " submission(s) graded above the points",
                Fix.REGRADE_OUT_OF_RANGE));
        long queued = submissions.stream().filter(s -> Boolean.TRUE.equals(s.getNeedsAiRegrade())).count();
        checks.add(new Check("AI queue", queued == 0, queued + " submission(s) still waiting for an AI grade", Fix.NONE));
        return checks;
    }

    /**
     * One weighted hack from the assignment page. {@code weight} is as written on the page
     * (60 for "60%"); grading normalizes by the total so weights needn't add up to 100.
     */
    public record HackWeight(String hack, double weight, String instructions) {
    }

    /**
     * Finds the page's weighted hacks, each with the instructions that follow its heading.
     * Fewer than two weighted hacks means there is nothing to weight, so the list is empty.
     */
    public static List<HackWeight> parseHackWeights(String pageContent) {
        if (pageContent == null || pageContent.isBlank()) {
            return List.of();
        }
        List<java.util.regex.MatchResult> headings = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Matcher matcher = WEIGHTED_HACK.matcher(pageContent);
        while (matcher.find()) {
            // The first heading for each hack number wins; later mentions are just text.
            if (Double.parseDouble(matcher.group(2)) > 0 && seen.add(matcher.group(1))) {
                headings.add(matcher.toMatchResult());
            }
        }
        if (headings.size() < 2) {
            return List.of();
        }
        List<HackWeight> hacks = new java.util.ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            java.util.regex.MatchResult heading = headings.get(i);
            int end = i + 1 < headings.size() ? headings.get(i + 1).start() : pageContent.length();
            String instructions = pageContent.substring(heading.end(), end).replaceAll("\\s+", " ").strip();
            if (instructions.length() > MAX_HACK_INSTRUCTION_CHARS) {
                instructions = instructions.substring(0, MAX_HACK_INSTRUCTION_CHARS) + "…";
            }
            hacks.add(new HackWeight("Hack #" + heading.group(1), Double.parseDouble(heading.group(2)), instructions));
        }
        return hacks;
    }

    /** {@link #parseHackWeights} as the JSON stored in {@link Assignment#getHackWeights}, or null when there are none. */
    public static String hackWeightsJson(String pageContent) {
        List<HackWeight> hacks = parseHackWeights(pageContent);
        if (hacks.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(hacks);
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads {@link Assignment#getHackWeights}; empty when unset or unreadable. */
    public static List<HackWeight> readHackWeights(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(JSON.readValue(json, HackWeight[].class));
        } catch (Exception e) {
            logger.warn("Ignoring unreadable hack weights: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildPrompt(String assignmentName, String description, String pageContent, double maxScore) {
        List<HackWeight> hacks = parseHackWeights(pageContent);
        String hackWeighting = "";
        if (!hacks.isEmpty()) {
            StringBuilder weights = new StringBuilder(
                    "\nThis assignment's hacks are weighted; the grade is the weighted average of a score for each hack:\n");
            double total = hacks.stream().mapToDouble(HackWeight::weight).sum();
            for (HackWeight hack : hacks) {
                weights.append(" ").append(hack.hack()).append(": ")
                        .append(formatScore(Math.round(hack.weight() / total * 1000.0) / 10.0)).append("%\n");
            }
            weights.append("Each tier's indicators must cover every hack, with emphasis in proportion to its weight.\n");
            hackWeighting = weights.toString();
        }
        return """
                You are writing a grading rubric for a student assignment. Base the rubric on the ACTUAL assignment content below — be specific to what this assignment asks for, not generic.

                ASSIGNMENT NAME: %s
                ASSIGNMENT DESCRIPTION: %s
                ASSIGNMENT MAXIMUM SCORE: %s
                %s

                ASSIGNMENT PAGE CONTENT:
                %s

                Write a thorough, detailed grading rubric with exactly four tiers. Scale every tier to the assignment maximum score above; do not use a fixed 4-point or 5-point scale. Full marks are reserved for the teacher, so the highest tier must be exactly %s (90%% of the maximum), and every score must be numeric and use the same units as the assignment. For example, when the maximum is 1, valid tier scores could be values such as 0.9, 0.8, 0.77, and 0.55. Choose sensible score thresholds for this assignment rather than copying those example values. Mirror this structure and tone, replacing the indicators with specifics grounded in the assignment content:

                Score %s — Strong / Exceptional
                <2-4 sentences describing what a submission that fully and impressively meets THIS assignment's requirements looks like>

                Indicators:
                 <specific indicator 1 for this assignment>
                 <specific indicator 2>
                 <specific indicator 3>
                 <specific indicator 4>

                Score <score below the strong tier> — Adequate
                <2-4 sentences describing a submission that meets the core requirement but lacks depth>

                Indicators:
                 <specific indicator 1>
                 <specific indicator 2>
                 <specific indicator 3>

                Score <score below the adequate tier> — Limited
                <2-4 sentences describing a partial or shallow submission for THIS assignment>

                Indicators:
                 <specific indicator 1>
                 <specific indicator 2>
                 <specific indicator 3>

                Score <lowest meaningful score> — Insufficient
                <2-4 sentences describing a submission that fails to address THIS assignment's core requirement>

                Indicators:
                 <specific indicator 1>
                 <specific indicator 2>

                Return ONLY the rubric text in the structure above — no preamble, no JSON, no markdown code fences.
                """.formatted(
                assignmentName == null ? "" : assignmentName,
                description == null ? "" : description,
                formatScore(maxScore),
                hackWeighting,
                limitPageContent(pageContent),
                formatScore(aiTopScore(maxScore)),
                formatScore(aiTopScore(maxScore)));
    }

    private String limitPageContent(String pageContent) {
        if (pageContent.length() <= MAX_PAGE_CONTENT_CHARS) {
            return pageContent;
        }
        return pageContent.substring(0, MAX_PAGE_CONTENT_CHARS)
                + "\n\n[Remaining page content omitted for rubric generation.]";
    }

    private static String formatScore(double score) {
        return java.math.BigDecimal.valueOf(score).stripTrailingZeros().toPlainString();
    }

    private String callGemini(String prompt) throws Exception {
        try {
            return callGeminiEndpoint(geminiApiUrl, prompt);
        } catch (GeminiHttpException exception) {
            if (exception.statusCode() != 404
                    || geminiApiUrl.equals(FALLBACK_GEMINI_API_URL)) {
                throw exception;
            }
            logger.warn("Gemini model endpoint returned 404; retrying rubric generation with {}",
                    FALLBACK_GEMINI_API_URL);
            return callGeminiEndpoint(FALLBACK_GEMINI_API_URL, prompt);
        }
    }

    private String callGeminiEndpoint(String endpoint, String prompt) throws Exception {
        Map<String, Object> part = Map.of("text", prompt);
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", List.of(part))),
                "generationConfig", Map.of("temperature", 0.3));
        String requestBodyJson = objectMapper.writeValueAsString(requestBody);
        String separator = endpoint.contains("?") ? "&" : "?";
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + separator + "key=" + geminiApiKey))
                .timeout(GEMINI_REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String providerMessage = response.body() == null ? "" : response.body().replaceAll("\\s+", " ").trim();
            if (providerMessage.length() > 300) {
                providerMessage = providerMessage.substring(0, 300);
            }
            throw new GeminiHttpException(response.statusCode(),
                    "Gemini returned HTTP " + response.statusCode()
                        + (providerMessage.isBlank() ? "" : ": " + providerMessage));
        }
        return response.body();
    }

    private static final class GeminiHttpException extends Exception {
        private final int statusCode;

        private GeminiHttpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }
}
