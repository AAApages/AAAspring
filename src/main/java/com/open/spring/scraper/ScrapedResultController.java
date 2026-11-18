package com.open.spring.scraper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.scraper.dto.ScrapeRequest;
import com.open.spring.scraper.dto.ScrapeResponse;

import lombok.Getter;
import lombok.Setter;

/**
 * Saves, lists, reads and deletes a student's own scrape runs - separate from
 * ScraperController, which only runs a scrape and never persists it (single
 * responsibility: ScraperController runs, this one stores and serves what was kept).
 *
 * A student's own results are scoped to them alone (save/list/get/delete). A teacher or
 * admin can additionally list every student's saved runs (GET /all), open any one of them
 * (GET /{id}), and delete any one of them - the moderation actions behind the dashboard at
 * /mvc/scraper/results.
 */
@RestController
@RequestMapping("/api/scraper/results")
public class ScrapedResultController {

    private static final String NOT_FOUND_MESSAGE = "Saved scrape not found";

    @Autowired
    private ScrapedResultJpaRepository resultRepo;

    @Autowired
    private PersonJpaRepository personRepo;

    @Autowired
    private ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<?> save(
            @RequestBody SaveScrapeResultRequest body,
            @AuthenticationPrincipal UserDetails userDetails) {
        Person person = getAuthenticatedPerson(userDetails);
        if (person == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        if (body == null || body.getLabel() == null || body.getLabel().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "label is required"));
        }
        if (body.getRequest() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "request is required"));
        }
        if (body.getResponse() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "response is required"));
        }

        ScrapedResult saved = new ScrapedResult();
        saved.setPersonId(person.getId());
        saved.setPersonUid(person.getUid());
        saved.setLabel(body.getLabel().trim());
        saved.setSourceUrl(body.getRequest().getUrl());
        saved.setItemCount(body.getResponse().getItems() == null ? 0 : body.getResponse().getItems().size());
        saved.setRequestJson(writeJsonOrNull(body.getRequest()));
        saved.setResultJson(writeJsonOrNull(body.getResponse()));
        saved.setCreatedAt(LocalDateTime.now());

        ScrapedResult persisted = resultRepo.save(saved);
        return new ResponseEntity<>(toSummaryDto(persisted), HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<?> listMine(@AuthenticationPrincipal UserDetails userDetails) {
        Person person = getAuthenticatedPerson(userDetails);
        if (person == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }

        List<ScrapedResultSummaryDto> summaries = new ArrayList<>();
        for (ScrapedResult result : resultRepo.findByPersonIdOrderByCreatedAtDesc(person.getId())) {
            summaries.add(toSummaryDto(result));
        }
        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        Person person = getAuthenticatedPerson(userDetails);
        if (person == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }

        ScrapedResult result = resultRepo.findById(id).orElse(null);
        // Same denial for "doesn't exist" and "exists but isn't yours" - a 403 vs 404 split
        // would let a student probe which ids belong to someone else. A teacher/admin (the
        // results dashboard) can open any result, same as the list below.
        boolean owns = result != null && person.getId().equals(result.getPersonId());
        if (result == null || !(owns || isTeacherOrAdmin(person))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", NOT_FOUND_MESSAGE));
        }

        return ResponseEntity.ok(toDetailDto(result));
    }

    /** Every student's saved runs, for the teacher/admin results dashboard (GET /mvc/scraper/results). */
    @GetMapping("/all")
    public ResponseEntity<?> listAll(@AuthenticationPrincipal UserDetails userDetails) {
        Person person = getAuthenticatedPerson(userDetails);
        if (person == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        if (!isTeacherOrAdmin(person)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Teacher or admin role required"));
        }

        List<ScrapedResultSummaryDto> summaries = new ArrayList<>();
        for (ScrapedResult result : resultRepo.findAllByOrderByCreatedAtDesc()) {
            summaries.add(toSummaryDto(result));
        }
        return ResponseEntity.ok(summaries);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        Person person = getAuthenticatedPerson(userDetails);
        if (person == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }

        ScrapedResult result = resultRepo.findById(id).orElse(null);
        // Same owns-it-or-is-staff check as getOne, so a teacher/admin can moderate from the
        // results dashboard; everyone else still only ever reaches their own.
        boolean owns = result != null && person.getId().equals(result.getPersonId());
        if (result == null || !(owns || isTeacherOrAdmin(person))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", NOT_FOUND_MESSAGE));
        }

        resultRepo.delete(result);
        return ResponseEntity.ok(Map.of("message", "Deleted"));
    }

    private Person getAuthenticatedPerson(UserDetails userDetails) {
        if (userDetails == null) {
            return null;
        }
        return personRepo.findByUid(userDetails.getUsername());
    }

    private boolean isTeacherOrAdmin(Person person) {
        return person != null && (person.hasRole("ROLE_TEACHER") || person.hasRole("ROLE_ADMIN"));
    }

    private String writeJsonOrNull(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private ScrapedResultSummaryDto toSummaryDto(ScrapedResult result) {
        ScrapedResultSummaryDto dto = new ScrapedResultSummaryDto();
        dto.setId(result.getId());
        dto.setPersonUid(result.getPersonUid());
        dto.setLabel(result.getLabel());
        dto.setSourceUrl(result.getSourceUrl());
        dto.setItemCount(result.getItemCount());
        dto.setCreatedAt(result.getCreatedAt());
        return dto;
    }

    private ScrapedResultDetailDto toDetailDto(ScrapedResult result) {
        ScrapedResultDetailDto dto = new ScrapedResultDetailDto();
        dto.setId(result.getId());
        dto.setPersonUid(result.getPersonUid());
        dto.setLabel(result.getLabel());
        dto.setSourceUrl(result.getSourceUrl());
        dto.setItemCount(result.getItemCount());
        dto.setCreatedAt(result.getCreatedAt());
        dto.setRequest(readJsonOrNull(result.getRequestJson(), ScrapeRequest.class));
        dto.setResponse(readJsonOrNull(result.getResultJson(), ScrapeResponse.class));
        return dto;
    }

    private <T> T readJsonOrNull(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    @Getter
    @Setter
    public static class SaveScrapeResultRequest {
        private String label;
        private ScrapeRequest request;
        private ScrapeResponse response;
    }

    @Getter
    @Setter
    public static class ScrapedResultSummaryDto {
        private Long id;
        private String personUid;
        private String label;
        private String sourceUrl;
        private Integer itemCount;
        private LocalDateTime createdAt;
    }

    @Getter
    @Setter
    public static class ScrapedResultDetailDto {
        private Long id;
        private String personUid;
        private String label;
        private String sourceUrl;
        private Integer itemCount;
        private LocalDateTime createdAt;
        private ScrapeRequest request;
        private ScrapeResponse response;
    }
}
