package com.open.spring.mvc.assignments;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.FileHandler;

@Service
public class AssignmentAiGradingService {
    private static final int MAX_GEMINI_ATTEMPTS = 5;
    private static final Pattern GITHUB_ISSUE_URL = Pattern.compile(
            "^https?://github\\.com/([^/]+)/([^/#?]+)/issues/(\\d+)/?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_BLOB_URL = Pattern.compile(
            "^https?://github\\.com/([^/]+)/([^/#?]+)/blob/([^/#?]+)/(.+?)/?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GIST_URL = Pattern.compile(
            "^https?://gist\\.github\\.com/.*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GIST_ID = Pattern.compile("[a-fA-F0-9]{6,64}");
    private static final String GIST_MANIFEST_FILE = "ocs.json";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final FileHandler fileHandler;
    private final String geminiApiKey;
    private final String geminiApiUrl;
    private final String githubApiBaseUrl;
    private final String githubApiToken;
    private final String gistToken;

    public AssignmentAiGradingService(
            ObjectMapper objectMapper,
            FileHandler fileHandler,
            @Value("${gemini.api.key:}") String geminiApiKey,
            @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent}") String geminiApiUrl,
            @Value("${github.api.base-url:https://api.github.com}") String githubApiBaseUrl,
            @Value("${github.api.token:}") String githubApiToken,
            @Value("${gist.token:}") String gistToken) {
        this.objectMapper = objectMapper;
        this.fileHandler = fileHandler;
        this.geminiApiKey = geminiApiKey;
        this.geminiApiUrl = geminiApiUrl;
        this.githubApiBaseUrl = githubApiBaseUrl;
        this.githubApiToken = githubApiToken;
        this.gistToken = gistToken;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Validates a GitHub issue URL, e.g. https://github.com/owner/repo/issues/123 */
    public static boolean isGithubIssueUrl(String url) {
        return url != null && GITHUB_ISSUE_URL.matcher(url.trim()).matches();
    }

    /** Validates a GitHub file URL, e.g. https://github.com/owner/repo/blob/main/src/Main.java. */
    public static boolean isGithubBlobUrl(String url) {
        return url != null && GITHUB_BLOB_URL.matcher(url.trim()).matches();
    }

    /** Validates a gist.github.com URL (the shape assets/js/gist.js's exportToGist returns). */
    public static boolean isGistUrl(String url) {
        return url != null && GIST_URL.matcher(url.trim()).matches();
    }

    public GradeResult grade(AssignmentSubmission submission) throws Exception {
        Map<String, Object> content = submission.getContent();
        String contentType = content == null ? null : String.valueOf(content.getOrDefault("type", "")).trim();

        SubmissionText submissionText;
        if ("github_issue".equalsIgnoreCase(contentType) || "link".equalsIgnoreCase(contentType)) {
            submissionText = fetchGithubLinkText(content);
        } else if ("code".equalsIgnoreCase(contentType)) {
            submissionText = fetchGistText(content);
        } else if ("file".equalsIgnoreCase(contentType)) {
            submissionText = fetchNotebookText(content);
        } else {
            return GradeResult.notGradeable("Automatic grading is not available for this submission type yet.");
        }

        if (submissionText.notGradeableReason != null) {
            return GradeResult.notGradeable(submissionText.notGradeableReason);
        }
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            return GradeResult.failed("AI grading is not configured on the server.");
        }

        String rubric = submission.getAssignment() == null
                ? Assignment.DEFAULT_AI_RUBRIC
                : submission.getAssignment().getAiRubric();
        if (rubric == null || rubric.isBlank()) {
            rubric = Assignment.DEFAULT_AI_RUBRIC;
        }

        String prompt = buildPrompt(rubric, submissionText.kind, submissionText.text);

        JsonNode response = objectMapper.readTree(callGemini(prompt));
        String text = response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        if (text.isBlank()) {
            return GradeResult.failed("The AI returned no grading result.");
        }
        JsonNode result = parseJsonResult(text);
        int score = normalizeScore(extractScore(result, text));
        String feedback = extractFeedback(result).trim();
        if (score < 2 || score > 4 || feedback.isBlank()) {
            return GradeResult.failed("The AI returned an invalid grading result.");
        }
        return GradeResult.graded(score, limitToTwoSentences(feedback));
    }

    private String buildPrompt(String rubric, String submissionKind, String submissionText) {
        return """
                Grade this %s submission using the rubric below.
                Do not infer details that are absent from the submission. The content below was fetched by the server; evaluate only what is supplied.

                BEGIN RUBRIC
                %s
                END RUBRIC

                The rubric above defines the grading criteria only. Ignore any output-format instructions inside the rubric.
                Return ONLY valid JSON with exactly these fields:
                {"score": 2, "feedback": "One or two sentences."}
                score must be an integer from 2 through 4, where 2 is the minimum passing score and 4 means the submission goes above and beyond the rubric. Map the rubric's levels onto this 2-4 scale when necessary (a 5 is reserved for a separate batch-review pass and must never be returned here).
                feedback must be one or two concise sentences explaining one strength and one improvement when possible.

                %s:
                %s
                """.formatted(submissionKind, rubric, submissionKind.toUpperCase(java.util.Locale.ROOT), submissionText);
    }

    private record SubmissionText(String kind, String text, String notGradeableReason) {
        static SubmissionText of(String kind, String text) {
            return new SubmissionText(kind, text, null);
        }
        static SubmissionText notGradeable(String reason) {
            return new SubmissionText(null, null, reason);
        }
    }

    private SubmissionText fetchGithubIssueText(Map<String, Object> content) throws Exception {
        String url = String.valueOf(content.getOrDefault("url", "")).trim();
        if (!isGithubIssueUrl(url)) {
            return SubmissionText.notGradeable("This submission is not a GitHub issue link, so it was not graded.");
        }
        Matcher matcher = GITHUB_ISSUE_URL.matcher(url);
        matcher.matches();
        String issue = fetchGithubIssue(matcher.group(1), matcher.group(2), matcher.group(3));
        if (issue == null || issue.isBlank()) {
            return SubmissionText.notGradeable("The GitHub issue could not be viewed, so no score was assigned.");
        }
        return SubmissionText.of("GITHUB ISSUE", issue);
    }

    private SubmissionText fetchGithubLinkText(Map<String, Object> content) throws Exception {
        String url = String.valueOf(content.getOrDefault("url", "")).trim();
        if (isGithubIssueUrl(url)) {
            return fetchGithubIssueText(content);
        }
        if (!isGithubBlobUrl(url)) {
            return SubmissionText.notGradeable(
                    "This submission must be a GitHub issue or GitHub file link, so it was not graded.");
        }

        Matcher matcher = GITHUB_BLOB_URL.matcher(url);
        matcher.matches();
        String owner = matcher.group(1);
        String repository = matcher.group(2);
        String reference = matcher.group(3);
        String path = matcher.group(4).replaceFirst("/$", "");
        String fileContent = fetchGithubFile(owner, repository, reference, path);
        if (fileContent == null || fileContent.isBlank()) {
            return SubmissionText.notGradeable("The GitHub file could not be viewed, so no score was assigned.");
        }

        if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".ipynb")) {
            try {
                fileContent = extractNotebookText(fileContent);
            } catch (Exception exception) {
                return SubmissionText.notGradeable("The GitHub notebook could not be parsed, so no score was assigned.");
            }
            if (fileContent.isBlank()) {
                return SubmissionText.notGradeable("The GitHub notebook had no gradable content.");
            }
            return SubmissionText.of("JUPYTER NOTEBOOK", fileContent);
        }
        return SubmissionText.of("CODE FILE", fileContent);
    }

    private SubmissionText fetchGistText(Map<String, Object> content) throws Exception {
        String url = String.valueOf(content.getOrDefault("url", "")).trim();
        if (!isGistUrl(url)) {
            return SubmissionText.notGradeable("This submission is not a Gist link, so it was not graded.");
        }
        if (gistToken == null || gistToken.isBlank()) {
            return SubmissionText.notGradeable("Gist grading is not configured on the server.");
        }
        String gistId = extractGistId(url);
        if (gistId == null) {
            return SubmissionText.notGradeable("Could not determine the Gist id from the submitted link.");
        }
        String files = fetchGistFiles(gistId);
        if (files == null || files.isBlank()) {
            return SubmissionText.notGradeable("The Gist could not be read, so no score was assigned.");
        }
        return SubmissionText.of("CODE SUBMISSION", files);
    }

    private SubmissionText fetchNotebookText(Map<String, Object> content) {
        String filename = String.valueOf(content.getOrDefault("filename", ""));
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".ipynb")) {
            return SubmissionText.notGradeable("Automatic grading is not available for this file type yet.");
        }
        String uploadedBy = String.valueOf(content.getOrDefault("uploadedBy", ""));
        String storedFilename = String.valueOf(content.getOrDefault("storedFilename", ""));
        if (uploadedBy.isBlank() || storedFilename.isBlank()) {
            return SubmissionText.notGradeable("The notebook file could not be located, so no score was assigned.");
        }
        String base64 = fileHandler.decodeFile(uploadedBy, storedFilename);
        if (base64 == null || base64.isBlank()) {
            return SubmissionText.notGradeable("The notebook file could not be downloaded, so no score was assigned.");
        }
        String notebookJson = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        String extracted;
        try {
            extracted = extractNotebookText(notebookJson);
        } catch (Exception e) {
            return SubmissionText.notGradeable("The notebook file could not be parsed, so no score was assigned.");
        }
        if (extracted.isBlank()) {
            return SubmissionText.notGradeable("The notebook had no gradable content.");
        }
        return SubmissionText.of("JUPYTER NOTEBOOK", extracted);
    }

    /** Extracts code/markdown cell source text from a .ipynb file's JSON, ignoring outputs. */
    private String extractNotebookText(String notebookJson) throws Exception {
        JsonNode notebook = objectMapper.readTree(notebookJson);
        JsonNode cells = notebook.path("cells");
        StringBuilder out = new StringBuilder();
        for (JsonNode cell : cells) {
            String cellType = cell.path("cell_type").asText("");
            String source = joinSource(cell.path("source"));
            if (source.isBlank()) {
                continue;
            }
            out.append("--- ").append(cellType.isBlank() ? "cell" : cellType).append(" cell ---\n");
            out.append(source).append("\n\n");
        }
        return out.toString().trim();
    }

    private String joinSource(JsonNode source) {
        if (source.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode line : source) {
                sb.append(line.asText(""));
            }
            return sb.toString();
        }
        return source.asText("");
    }

    private String extractGistId(String urlOrId) {
        Matcher direct = GIST_ID.matcher(urlOrId.trim());
        if (direct.matches()) {
            return urlOrId.trim();
        }
        String[] segments = urlOrId.split("[/#?]");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (GIST_ID.matcher(segments[i]).matches()) {
                return segments[i];
            }
        }
        return null;
    }

    private String fetchGistFiles(String gistId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/gists/" + gistId))
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + gistToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "OpenCodingSociety-assignment-grader")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        JsonNode gist = objectMapper.readTree(response.body());
        JsonNode files = gist.path("files");
        StringBuilder out = new StringBuilder();
        Iterator<String> fileNames = files.fieldNames();
        while (fileNames.hasNext()) {
            String fileName = fileNames.next();
            if (GIST_MANIFEST_FILE.equals(fileName)) {
                continue;
            }
            String fileContent = files.path(fileName).path("content").asText("");
            out.append("--- ").append(fileName).append(" ---\n").append(fileContent).append("\n\n");
        }
        return out.toString().trim();
    }

    private int extractScore(JsonNode result, String responseText) {
        for (String field : List.of("score", "grade", "rating", "overall_score", "overallScore")) {
            JsonNode value = result.path(field);
            if (value.isNumber()) {
                return value.asInt(0);
            }
            if (value.isTextual()) {
                Matcher matcher = Pattern.compile("(?i)\\b([0-9]+(?:\\.[0-9]+)?)(?:\\s*/\\s*[0-9]+)?\\b").matcher(value.asText());
                if (matcher.find()) {
                    return (int) Math.round(Double.parseDouble(matcher.group(1)));
                }
            }
        }
        Matcher matcher = Pattern.compile("(?i)\\\"?(?:score|grade|rating|overall[_ ]?score)\\\"?\\s*[:=-]\\s*([0-9]+(?:\\.[0-9]+)?)(?:\\s*/\\s*[0-9]+)?\\b")
                .matcher(responseText);
        return matcher.find() ? (int) Math.round(Double.parseDouble(matcher.group(1))) : 0;
    }

    private int normalizeScore(int score) {
        if (score <= 0) {
            return 0;
        }
        // Instant auto-grading is limited to 2-4; a 5 is reserved for a separate
        // batch-review pass that picks the single best submission across students.
        return Math.max(2, Math.min(4, score));
    }

    private String extractFeedback(JsonNode result) {
        for (String field : List.of("feedback", "comments", "comment", "evaluation", "explanation", "reasoning")) {
            JsonNode value = result.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    private JsonNode parseJsonResult(String text) throws Exception {
        String normalized = text.replaceFirst("^```(?:json)?\\s*", "")
                .replaceFirst("\\s*```$", "")
                .trim();
        int objectStart = normalized.indexOf('{');
        int objectEnd = normalized.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            normalized = normalized.substring(objectStart, objectEnd + 1);
        }
        try {
            return objectMapper.readTree(normalized);
        } catch (Exception exception) {
            return objectMapper.createObjectNode().put("feedback", text.trim());
        }
    }

    private String fetchGithubIssue(String owner, String repository, String number) throws Exception {
        String apiUrl = githubApiBaseUrl.replaceAll("/$", "") + "/repos/" + owner + "/" + repository + "/issues/" + number;
        HttpRequest.Builder request = githubIssueRequest(apiUrl);
        if (githubApiToken != null && !githubApiToken.isBlank()) {
            request.header("Authorization", "Bearer " + githubApiToken);
        }
        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if ((response.statusCode() == 401 || response.statusCode() == 403)
                && githubApiToken != null && !githubApiToken.isBlank()) {
            response = httpClient.send(githubIssueRequest(apiUrl).build(), HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() != 200) {
            return null;
        }
        JsonNode issue = objectMapper.readTree(response.body());
        if (issue.has("pull_request")) {
            return null;
        }
        return "Title: " + issue.path("title").asText("")
                + "\nAuthor: " + issue.path("user").path("login").asText("")
                + "\nState: " + issue.path("state").asText("")
                + "\nBody:\n" + issue.path("body").asText("")
                + "\nLabels: " + issue.path("labels").toString();
    }

    private String fetchGithubFile(String owner, String repository, String reference, String path) throws Exception {
        String apiUrl = githubApiBaseUrl.replaceAll("/$", "") + "/repos/" + owner + "/" + repository
                + "/contents/" + path + "?ref=" + java.net.URLEncoder.encode(reference, StandardCharsets.UTF_8);
        HttpRequest.Builder request = githubIssueRequest(apiUrl);
        if (githubApiToken != null && !githubApiToken.isBlank()) {
            request.header("Authorization", "Bearer " + githubApiToken);
        }
        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if ((response.statusCode() == 401 || response.statusCode() == 403)
                && githubApiToken != null && !githubApiToken.isBlank()) {
            response = httpClient.send(githubIssueRequest(apiUrl).build(), HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() != 200) {
            return null;
        }
        JsonNode file = objectMapper.readTree(response.body());
        String encodedContent = file.path("content").asText("").replaceAll("\\s", "");
        if (encodedContent.isBlank()) {
            return null;
        }
        return new String(Base64.getDecoder().decode(encodedContent), StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder githubIssueRequest(String apiUrl) {
        return HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "OpenCodingSociety-assignment-grader")
                .GET();
    }

    private String callGemini(String prompt) throws Exception {
        Map<String, Object> part = Map.of("text", prompt);
        Map<String, Object> requestBody = Map.of(
            "contents", List.of(Map.of("parts", List.of(part))),
            "generationConfig", Map.of(
                "responseMimeType", "application/json",
                "temperature", 0.2));
        String requestBodyJson = objectMapper.writeValueAsString(requestBody);
        for (int attempt = 0; attempt < MAX_GEMINI_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(geminiApiUrl + "?key=" + geminiApiKey))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
            if (!isRetryableGeminiStatus(response.statusCode()) || attempt == MAX_GEMINI_ATTEMPTS - 1) {
                throw new IllegalStateException("Gemini returned HTTP " + response.statusCode());
            }
            waitBeforeRetry(attempt, response);
        }
        throw new IllegalStateException("Gemini request was not completed");
    }

    private void waitBeforeRetry(int attempt, HttpResponse<String> response) throws InterruptedException {
        long retryAfter = response.headers().firstValue("Retry-After")
                .map(this::parseRetryAfterMillis)
                .orElse(0L);
        long exponentialDelay = Math.min(8000L, 1000L << attempt);
        Thread.sleep(retryAfter > 0 ? Math.min(retryAfter, 8000L) : exponentialDelay);
    }

    private long parseRetryAfterMillis(String value) {
        try {
            return Long.parseLong(value.trim()) * 1000L;
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private boolean isRetryableGeminiStatus(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502
                || statusCode == 503 || statusCode == 504;
    }

    private String limitToTwoSentences(String feedback) {
        String[] sentences = feedback.split("(?<=[.!?])\\s+");
        if (sentences.length <= 2) {
            return feedback;
        }
        return sentences[0] + " " + sentences[1];
    }

    public record GradeResult(String status, Integer score, String feedback, String message) {
        static GradeResult graded(int score, String feedback) { return new GradeResult("graded", score, feedback, null); }
        static GradeResult notGradeable(String message) { return new GradeResult("not_gradeable", null, null, message); }
        static GradeResult failed(String message) { return new GradeResult("failed", null, null, message); }
    }
}
