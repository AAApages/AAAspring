package com.open.spring.mvc.analytics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/ocs-analytics")
@CrossOrigin(origins = {"http://localhost:8080", "http://localhost:4000", "http://127.0.0.1:4100", "http://localhost:4100", "https://open-coding-society.github.io"}, allowCredentials = "true")
public class OCSAnalyticsController {

    @Autowired
    private OCSAnalyticsRepository analyticsRepository;

    @Autowired
    private PersonJpaRepository personRepository;

    /**
     * Save analytics data from frontend
     * POST /api/ocs-analytics/save
     */
    @PostMapping("/save")
    public ResponseEntity<?> saveAnalytics(
            @RequestBody OCSAnalyticsDTO dto,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        try {
            // Try to get person from authentication first
            Person person = null;
            
            if (userDetails != null) {
                // User is authenticated - use their UID from auth
                person = personRepository.findByUid(userDetails.getUsername());
            } else if (dto.getUid() != null && !dto.getUid().isEmpty()) {
                // Fallback: use UID from DTO if provided
                person = personRepository.findByUid(dto.getUid());
            }
            
            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("User not found. Please provide valid uid or be authenticated.");
            }

            // Create analytics record
            OCSAnalytics analytics = new OCSAnalytics();
            analytics.setPerson(person);
            analytics.setSessionStartTime(dto.getSessionStartTime());
            analytics.setSessionEndTime(dto.getSessionEndTime());
            analytics.setSessionDurationSeconds(dto.getSessionDurationSeconds());
            
            // Content info
            analytics.setQuestName(dto.getQuestName());
            analytics.setModuleName(dto.getModuleName());
            analytics.setLessonNumber(dto.getLessonNumber());
            analytics.setPageTitle(dto.getPageTitle());
            analytics.setPageUrl(dto.getPageUrl());
            
            // User actions
            analytics.setLessonsViewed(dto.getLessonsViewed() != null ? dto.getLessonsViewed() : 0);
            analytics.setLessonsCompleted(dto.getLessonsCompleted() != null ? dto.getLessonsCompleted() : 0);
            analytics.setModulesViewed(dto.getModulesViewed() != null ? dto.getModulesViewed() : 0);
            analytics.setVideosWatched(dto.getVideosWatched() != null ? dto.getVideosWatched() : 0);
            analytics.setVideosCompleted(dto.getVideosCompleted() != null ? dto.getVideosCompleted() : 0);
            analytics.setCodeExecutions(dto.getCodeExecutions() != null ? dto.getCodeExecutions() : 0);
            analytics.setCopyPasteAttempts(dto.getCopyPasteAttempts() != null ? dto.getCopyPasteAttempts() : 0);
            analytics.setQuestionsAnswered(dto.getQuestionsAnswered() != null ? dto.getQuestionsAnswered() : 0);
            analytics.setQuestionsCorrect(dto.getQuestionsCorrect() != null ? dto.getQuestionsCorrect() : 0);
            analytics.calculateAccuracy();
            analytics.setExercisesAttempted(dto.getExercisesAttempted() != null ? dto.getExercisesAttempted() : 0);
            analytics.setExercisesCompleted(dto.getExercisesCompleted() != null ? dto.getExercisesCompleted() : 0);
            analytics.setAssessmentsAttempted(dto.getAssessmentsAttempted() != null ? dto.getAssessmentsAttempted() : 0);
            analytics.setAssessmentAverageScore(dto.getAssessmentAverageScore() != null ? dto.getAssessmentAverageScore() : 0.0);
            
            // Progression
            analytics.setModuleCompleted(dto.getModuleCompleted() != null ? dto.getModuleCompleted() : false);
            analytics.setProgressPercentage(dto.getProgressPercentage() != null ? dto.getProgressPercentage() : 0);
            
            // Engagement
            analytics.setScrollDepthPercentage(dto.getScrollDepthPercentage() != null ? dto.getScrollDepthPercentage() : 0);
            analytics.setInteractionPercentage(dto.getInteractionPercentage() != null ? dto.getInteractionPercentage() : 0.0);
            analytics.setHoverEventsCount(dto.getHoverEventsCount() != null ? dto.getHoverEventsCount() : 0);
            analytics.setKeyboardInputEvents(dto.getKeyboardInputEvents() != null ? dto.getKeyboardInputEvents() : 0);
            analytics.setMouseClicksCount(dto.getMouseClicksCount() != null ? dto.getMouseClicksCount() : 0);
            
            // Performance
            analytics.setPageLoadTimeMs(dto.getPageLoadTimeMs() != null ? dto.getPageLoadTimeMs() : 0.0);
            analytics.setTimeoutErrors(dto.getTimeoutErrors() != null ? dto.getTimeoutErrors() : 0);
            analytics.setValidationErrors(dto.getValidationErrors() != null ? dto.getValidationErrors() : 0);
            
            // Metadata
            analytics.setUserAgent(dto.getUserAgent());
            analytics.setReferrer(dto.getReferrer());
            
            // Save
            OCSAnalytics saved = analyticsRepository.save(analytics);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error saving analytics: " + e.getMessage());
        }
    }

    /**
     * Get all analytics for current user
     * GET /api/ocs-analytics/user
     */
    @GetMapping("/user")
    public ResponseEntity<?> getUserAnalytics(
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            Person person = personRepository.findByUid(userDetails.getUsername());
            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
            }

            List<OCSAnalytics> analytics = analyticsRepository.findByPersonOrderBySessionStartTimeDesc(person);
            return ResponseEntity.ok(analytics);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching analytics: " + e.getMessage());
        }
    }

    /**
     * Get analytics summary for current user
     * GET /api/ocs-analytics/user/summary
     */
    @GetMapping("/user/summary")
    public ResponseEntity<?> getUserSummary(
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            Person person = personRepository.findByUid(userDetails.getUsername());
            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
            }

            Map<String, Object> summary = new HashMap<>();
            
            // Total metrics
            Long totalSeconds = analyticsRepository.getTotalTimeSpentSeconds(person).orElse(0L);
            summary.put("totalTimeSpentSeconds", totalSeconds);
            summary.put("totalTimeFormatted", formatSeconds(totalSeconds));
            
            Double avgDuration = analyticsRepository.getAverageSessionDuration(person).orElse(0.0);
            summary.put("averageSessionDurationSeconds", avgDuration);
            
            Integer totalLessons = analyticsRepository.getTotalLessonsViewed(person).orElse(0);
            summary.put("totalLessonsViewed", totalLessons);
            
            Integer totalLessonsCompleted = analyticsRepository.getTotalLessonsCompleted(person).orElse(0);
            summary.put("totalLessonsCompleted", totalLessonsCompleted);
            
            Integer totalModules = analyticsRepository.getTotalModulesViewed(person).orElse(0);
            summary.put("totalModulesViewed", totalModules);
            
            Integer totalCopyPaste = analyticsRepository.getTotalCopyPasteAttempts(person).orElse(0);
            summary.put("totalCopyPasteAttempts", totalCopyPaste);
            
            Integer totalCodeExecutions = analyticsRepository.getTotalCodeExecutions(person).orElse(0);
            summary.put("totalCodeExecutions", totalCodeExecutions);
            
            Double avgInteraction = analyticsRepository.getAverageInteractionPercentage(person).orElse(0.0);
            summary.put("interactionPercentage", avgInteraction);
            
            Double avgScrollDepth = analyticsRepository.getAverageScrollDepth(person).orElse(0.0);
            summary.put("averageScrollDepth", avgScrollDepth);
            
            Double avgAccuracy = analyticsRepository.getAverageAccuracy(person).orElse(0.0);
            summary.put("averageAccuracyPercentage", avgAccuracy);
            
            List<String> quests = analyticsRepository.getEngagedQuests(person);
            summary.put("engagedQuests", quests);
            
            List<OCSAnalytics> bathroomSessions = analyticsRepository.findByPersonAndQuestName(person, "Bathroom Pass");
            Long bathroomSeconds = bathroomSessions.stream()
                    .mapToLong(a -> a.getSessionDurationSeconds() != null ? a.getSessionDurationSeconds() : 0)
                    .sum();
            summary.put("bathroomTimeSeconds", bathroomSeconds);
            summary.put("bathroomTimeFormatted", formatSeconds(bathroomSeconds));
            
            return ResponseEntity.ok(summary);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching summary: " + e.getMessage());
        }
    }

    /**
     * Get analytics for a specific quest
     * GET /api/ocs-analytics/user/quest/{questName}
     */
    @GetMapping("/user/quest/{questName}")
    public ResponseEntity<?> getQuestAnalytics(
            @PathVariable String questName,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            Person person = personRepository.findByUid(userDetails.getUsername());
            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
            }

            List<OCSAnalytics> analytics = analyticsRepository.findByPersonAndQuestName(person, questName);
            
            Map<String, Object> response = new HashMap<>();
            response.put("questName", questName);
            response.put("sessions", analytics);
            response.put("totalSessions", analytics.size());
            
            Long totalSeconds = analytics.stream()
                    .mapToLong(OCSAnalytics::getSessionDurationSeconds)
                    .sum();
            response.put("totalTimeSpentSeconds", totalSeconds);
            response.put("totalTimeFormatted", formatSeconds(totalSeconds));
            
            Integer totalLessons = analytics.stream()
                    .mapToInt(a -> a.getLessonsViewed() != null ? a.getLessonsViewed() : 0)
                    .sum();
            response.put("totalLessonsViewed", totalLessons);
            
            Integer totalCopyPaste = analytics.stream()
                    .mapToInt(a -> a.getCopyPasteAttempts() != null ? a.getCopyPasteAttempts() : 0)
                    .sum();
            response.put("totalCopyPasteAttempts", totalCopyPaste);
            
            boolean completed = analyticsRepository.isQuestCompleted(person, questName);
            response.put("questCompleted", completed);
            
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching quest analytics: " + e.getMessage());
        }
    }

    /**
     * Get detailed analytics for a specific module/lesson
     * GET /api/ocs-analytics/user/quest/{questName}/module/{moduleName}
     */
    @GetMapping("/user/quest/{questName}/module/{moduleName}")
    public ResponseEntity<?> getModuleAnalytics(
            @PathVariable String questName,
            @PathVariable String moduleName,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            Person person = personRepository.findByUid(userDetails.getUsername());
            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
            }

            List<OCSAnalytics> analytics = analyticsRepository
                    .findByPersonAndQuestNameAndModuleName(person, questName, moduleName);
            
            Map<String, Object> response = new HashMap<>();
            response.put("questName", questName);
            response.put("moduleName", moduleName);
            response.put("sessions", analytics);
            response.put("sessionCount", analytics.size());
            
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching module analytics: " + e.getMessage());
        }
    }

    /**
     * Get analytics for a date range
     * GET /api/ocs-analytics/user/range?start=2025-01-01&end=2025-01-31
     */
    @GetMapping("/user/range")
    public ResponseEntity<?> getAnalyticsRange(
            @RequestParam String start,
            @RequestParam String end,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            Person person = personRepository.findByUid(userDetails.getUsername());
            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
            }

            LocalDateTime startTime = LocalDateTime.parse(start + "T00:00:00");
            LocalDateTime endTime = LocalDateTime.parse(end + "T23:59:59");

            List<OCSAnalytics> analytics = analyticsRepository
                    .findByPersonAndSessionStartTimeGreaterThanAndSessionStartTimeLessThan(person, startTime, endTime);
            
            return ResponseEntity.ok(analytics);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching analytics range: " + e.getMessage());
        }
    }

    /**
     * Get detailed per-lesson analytics for current user
     * GET /api/ocs-analytics/user/detailed
     */
    @GetMapping("/user/detailed")
    public ResponseEntity<?> getUserDetailedAnalytics(
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            Person person = personRepository.findByUid(userDetails.getUsername());
            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
            }

            // Get all analytics sessions ordered by start time (most recent first)
            List<OCSAnalytics> sessions = analyticsRepository
                    .findByPersonOrderBySessionStartTimeDesc(person);
            
            return ResponseEntity.ok(sessions);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching detailed analytics: " + e.getMessage());
        }
    }

    /**
     * Get admin analytics for a specific user
     * GET /api/ocs-analytics/admin/user/{userId}
     */
    @GetMapping("/admin/user/{userId}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getAdminUserAnalytics(
            @PathVariable Long userId,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            // Verify user is admin
            Person admin = personRepository.findByUid(userDetails.getUsername());
            if (admin == null || !admin.hasRole("ROLE_ADMIN")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Only admins can access this endpoint");
            }
            
            Person person = personRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            List<OCSAnalytics> analytics = analyticsRepository.findByPersonOrderBySessionStartTimeDesc(person);
            return ResponseEntity.ok(analytics);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching admin analytics: " + e.getMessage());
        }
    }

    /**
     * Get summary analytics for a specific user (admin view)
     * GET /api/ocs-analytics/admin/user/{userId}/summary
     */
    @GetMapping("/admin/user/{userId}/summary")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getAdminUserSummary(
            @PathVariable Long userId,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            Person person = personRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Map<String, Object> summary = new HashMap<>();
            summary.put("userId", person.getId());
            summary.put("userName", person.getName());
            summary.put("userEmail", person.getEmail());
            summary.put("userUid", person.getUid());
            
            // Total metrics
            Long totalSeconds = analyticsRepository.getTotalTimeSpentSeconds(person).orElse(0L);
            summary.put("totalTimeSpentSeconds", totalSeconds);
            summary.put("totalTimeFormatted", formatSeconds(totalSeconds));
            
            Double avgDuration = analyticsRepository.getAverageSessionDuration(person).orElse(0.0);
            summary.put("averageSessionDurationSeconds", avgDuration);
            
            Integer totalLessons = analyticsRepository.getTotalLessonsViewed(person).orElse(0);
            summary.put("totalLessonsViewed", totalLessons);
            
            Integer totalLessonsCompleted = analyticsRepository.getTotalLessonsCompleted(person).orElse(0);
            summary.put("totalLessonsCompleted", totalLessonsCompleted);
            
            Integer totalModules = analyticsRepository.getTotalModulesViewed(person).orElse(0);
            summary.put("totalModulesViewed", totalModules);
            
            Integer totalCopyPaste = analyticsRepository.getTotalCopyPasteAttempts(person).orElse(0);
            summary.put("totalCopyPasteAttempts", totalCopyPaste);
            
            Integer totalCodeExecutions = analyticsRepository.getTotalCodeExecutions(person).orElse(0);
            summary.put("totalCodeExecutions", totalCodeExecutions);
            
            Double avgInteraction = analyticsRepository.getAverageInteractionPercentage(person).orElse(0.0);
            summary.put("interactionPercentage", avgInteraction);
            
            Double avgScrollDepth = analyticsRepository.getAverageScrollDepth(person).orElse(0.0);
            summary.put("averageScrollDepth", avgScrollDepth);
            
            Double avgAccuracy = analyticsRepository.getAverageAccuracy(person).orElse(0.0);
            summary.put("averageAccuracyPercentage", avgAccuracy);
            
            List<String> quests = analyticsRepository.getEngagedQuests(person);
            summary.put("engagedQuests", quests);
            
            // Session count
            List<OCSAnalytics> allAnalytics = analyticsRepository.findByPersonOrderBySessionStartTimeDesc(person);
            summary.put("totalSessions", allAnalytics.size());
            
            return ResponseEntity.ok(summary);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching user summary: " + e.getMessage());
        }
    }

    /**
     * Get all users with their basic analytics summary
     * GET /api/ocs-analytics/admin/all-users-summary
     */
    @GetMapping("/admin/all-users-summary")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getAllUsersSummary(
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            // Verify user is admin
            Person admin = personRepository.findByUid(userDetails.getUsername());
            if (admin == null || !admin.hasRole("ROLE_ADMIN")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Only admins can access this endpoint");
            }
            
            List<Person> allUsers = personRepository.findAll();
            List<Map<String, Object>> userSummaries = new ArrayList<>();
            
            for (Person person : allUsers) {
                Map<String, Object> summary = new HashMap<>();
                summary.put("id", person.getId());
                summary.put("name", person.getName());
                summary.put("email", person.getEmail());
                summary.put("uid", person.getUid());
                
                Long totalSeconds = analyticsRepository.getTotalTimeSpentSeconds(person).orElse(0L);
                summary.put("totalTimeSpentSeconds", totalSeconds);
                summary.put("totalTimeFormatted", formatSeconds(totalSeconds));
                
                Integer totalLessons = analyticsRepository.getTotalLessonsViewed(person).orElse(0);
                summary.put("totalLessonsViewed", totalLessons);
                
                Integer totalLessonsCompleted = analyticsRepository.getTotalLessonsCompleted(person).orElse(0);
                summary.put("totalLessonsCompleted", totalLessonsCompleted);
                
                Integer totalCodeExecutions = analyticsRepository.getTotalCodeExecutions(person).orElse(0);
                summary.put("totalCodeExecutions", totalCodeExecutions);
                
                Double avgInteraction = analyticsRepository.getAverageInteractionPercentage(person).orElse(0.0);
                summary.put("interactionPercentage", avgInteraction);
                
                List<OCSAnalytics> allAnalytics = analyticsRepository.findByPersonOrderBySessionStartTimeDesc(person);
                summary.put("totalSessions", allAnalytics.size());
                
                Double avgAccuracy = analyticsRepository.getAverageAccuracy(person).orElse(0.0);
                summary.put("averageAccuracyPercentage", avgAccuracy);
                
                Long bathroomSeconds = analyticsRepository.getTotalBathroomTimeSpentSeconds(person).orElse(0L);
                summary.put("bathroomTimeSeconds", bathroomSeconds);
                summary.put("bathroomTimeFormatted", formatSeconds(bathroomSeconds));
                
                userSummaries.add(summary);
            }
            
            return ResponseEntity.ok(userSummaries);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching all users summary: " + e.getMessage());
        }
    }

    /**
     * Get aggregated analytics statistics across all users
     * GET /api/ocs-analytics/admin/global-stats
     */
    @GetMapping("/admin/global-stats")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getGlobalStats(
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            // Verify user is admin
            Person admin = personRepository.findByUid(userDetails.getUsername());
            if (admin == null || !admin.hasRole("ROLE_ADMIN")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Only admins can access this endpoint");
            }
            
            List<Person> allUsers = personRepository.findAll();
            List<OCSAnalytics> allAnalytics = analyticsRepository.findAll();
            
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalUsers", allUsers.size());
            stats.put("totalAnalyticsRecords", allAnalytics.size());
            
            Long totalTimeSpent = allAnalytics.stream()
                    .mapToLong(a -> a.getSessionDurationSeconds() != null ? a.getSessionDurationSeconds() : 0)
                    .sum();
            stats.put("globalTotalTimeSpent", formatSeconds(totalTimeSpent));
            stats.put("globalTotalTimeSpentSeconds", totalTimeSpent);
            
            Integer totalLessonsViewed = allAnalytics.stream()
                    .mapToInt(a -> a.getLessonsViewed() != null ? a.getLessonsViewed() : 0)
                    .sum();
            stats.put("globalTotalLessonsViewed", totalLessonsViewed);
            
            Integer totalCodeExecutions = allAnalytics.stream()
                    .mapToInt(a -> a.getCodeExecutions() != null ? a.getCodeExecutions() : 0)
                    .sum();
            stats.put("globalTotalCodeExecutions", totalCodeExecutions);
            
            Double avgInteraction = allAnalytics.stream()
                    .mapToDouble(a -> a.getInteractionPercentage() != null ? a.getInteractionPercentage() : 0)
                    .average()
                    .orElse(0.0);
            stats.put("globalAverageInteraction", avgInteraction);
            
            Double avgAccuracy = allAnalytics.stream()
                    .mapToDouble(a -> a.getAccuracyPercentage() != null ? a.getAccuracyPercentage() : 0)
                    .average()
                    .orElse(0.0);
            stats.put("globalAverageAccuracy", avgAccuracy);
            
            // Users with analytics data
            Set<Long> userIds = new HashSet<>();
            for (OCSAnalytics a : allAnalytics) {
                try {
                    Person p = a.getPerson();
                    if (p != null && p.getId() != null) {
                        userIds.add(p.getId());
                    }
                } catch (Exception e) {
                    // Skip records with missing Person references (deleted users)
                }
            }
            stats.put("usersWithAnalytics", userIds.size());
            
            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching global stats: " + e.getMessage());
        }
    }

    /**
     * Get quest-level statistics across all users
     * GET /api/ocs-analytics/admin/quest-stats
     */
    @GetMapping("/admin/quest-stats")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getQuestStats(
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            // Verify user is admin
            Person admin = personRepository.findByUid(userDetails.getUsername());
            if (admin == null || !admin.hasRole("ROLE_ADMIN")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Only admins can access this endpoint");
            }
            
            List<OCSAnalytics> allAnalytics = analyticsRepository.findAll();
            Map<String, Object> questStats = new HashMap<>();
            
            // Group by quest name
            Map<String, List<OCSAnalytics>> byQuest = new HashMap<>();
            for (OCSAnalytics a : allAnalytics) {
                if (a.getQuestName() != null && !a.getQuestName().isEmpty()) {
                    byQuest.computeIfAbsent(a.getQuestName(), k -> new ArrayList<>()).add(a);
                }
            }
            
            for (Map.Entry<String, List<OCSAnalytics>> entry : byQuest.entrySet()) {
                String questName = entry.getKey();
                List<OCSAnalytics> questData = entry.getValue();
                
                Map<String, Object> questInfo = new HashMap<>();
                questInfo.put("questName", questName);
                questInfo.put("totalSessions", questData.size());
                questInfo.put("uniqueUsers", questData.stream()
                        .filter(a -> a.getPerson() != null)
                        .map(a -> a.getPerson().getId())
                        .distinct()
                        .count());
                
                Long totalTime = questData.stream()
                        .mapToLong(a -> a.getSessionDurationSeconds() != null ? a.getSessionDurationSeconds() : 0)
                        .sum();
                questInfo.put("totalTimeSpent", formatSeconds(totalTime));
                questInfo.put("totalTimeSpentSeconds", totalTime);
                
                Integer totalLessons = questData.stream()
                        .mapToInt(a -> a.getLessonsViewed() != null ? a.getLessonsViewed() : 0)
                        .sum();
                questInfo.put("totalLessonsViewed", totalLessons);
                
                Long completions = questData.stream()
                        .filter(a -> a.getModuleCompleted() != null && a.getModuleCompleted())
                        .count();
                questInfo.put("totalCompletions", completions);
                
                questStats.put(questName, questInfo);
            }
            
            return ResponseEntity.ok(questStats);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching quest stats: " + e.getMessage());
        }
    }

    /**
     * Helper method to format seconds into human-readable format
     */
    private String formatSeconds(Long totalSeconds) {
        if (totalSeconds == null || totalSeconds == 0) return "0m";
        
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        
        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
