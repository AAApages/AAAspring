package com.open.spring.mvc.assignments;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.aiconnections.AiHouseModelClient;
import com.open.spring.mvc.aiconnections.AiHouseSettingsRepository;
import com.open.spring.mvc.aiconnections.AiKeyCipher;
import com.open.spring.mvc.aiconnections.AiProviderFailure;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Night grading status, a manual start, and the admin settings for the self-hosted model.
 * Anyone signed in sees the status; teachers and admins can start a run and read the settings;
 * only admins change them or test a server. The API key is never returned.
 */
@RestController
@RequestMapping("/api/night-grading")
public class NightGradingApiRestController {

    /** Test connection request; the key is optional (blank uses the saved one). */
    public record ConnectionTest(String url, String apiKey) {
        @Override public String toString() { return "ConnectionTest[" + url + ", apiKey redacted]"; }
    }

    private final AssignmentNightGradingService nightGrading;
    private final AiHouseSettingsRepository settings;
    private final AiHouseModelClient house;
    private final AiKeyCipher cipher;
    private final AssignmentAuthorizationService authorization;
    private final PersonJpaRepository personRepo;

    public NightGradingApiRestController(AssignmentNightGradingService nightGrading,
            AiHouseSettingsRepository settings, AiHouseModelClient house, AiKeyCipher cipher,
            AssignmentAuthorizationService authorization, PersonJpaRepository personRepo) {
        this.nightGrading = nightGrading;
        this.settings = settings;
        this.house = house;
        this.cipher = cipher;
        this.authorization = authorization;
        this.personRepo = personRepo;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(@AuthenticationPrincipal UserDetails userDetails) {
        return GradingResponse.signedIn(personRepo, userDetails,
                person -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(nightGrading.status()));
    }

    @PostMapping("/run-now")
    public ResponseEntity<?> runNow(@AuthenticationPrincipal UserDetails userDetails) {
        return GradingResponse.signedIn(personRepo, userDetails, person -> {
            if (!authorization.isTeacherOrAdmin(person)) {
                return forbidden("Only teachers and admins can start night grading.");
            }
            AssignmentNightGradingService.StartResult result = nightGrading.startNow();
            HttpStatus status = switch (result.status()) {
                case "started" -> HttpStatus.ACCEPTED;
                case "busy" -> HttpStatus.CONFLICT;
                default -> HttpStatus.OK; // No house model: nothing graded, the counts are reported.
            };
            return ResponseEntity.status(status).body(result);
        });
    }

    @GetMapping("/settings")
    public ResponseEntity<?> getSettings(@AuthenticationPrincipal UserDetails userDetails) {
        return GradingResponse.signedIn(personRepo, userDetails, person -> {
            if (!authorization.isTeacherOrAdmin(person)) {
                return forbidden("Only teachers and admins can view night grading settings.");
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settingsBody(person));
        });
    }

    @PutMapping("/settings")
    public ResponseEntity<?> saveSettings(@RequestBody AiHouseSettingsRepository.Changes changes,
            @AuthenticationPrincipal UserDetails userDetails) {
        return GradingResponse.signedIn(personRepo, userDetails, person -> {
            if (!isAdmin(person)) {
                return forbidden("Only admins can change night grading settings.");
            }
            settings.save(changes, person.getUid());
            house.resetBreaker();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settingsBody(person));
        });
    }

    @DeleteMapping("/settings")
    public ResponseEntity<?> resetSettings(@AuthenticationPrincipal UserDetails userDetails) {
        return GradingResponse.signedIn(personRepo, userDetails, person -> {
            if (!isAdmin(person)) {
                return forbidden("Only admins can change night grading settings.");
            }
            settings.reset(person.getUid());
            house.resetBreaker();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settingsBody(person));
        });
    }

    /** Lists the models a server offers, to fill the model dropdowns. Nothing is saved. */
    @PostMapping("/settings/test-connection")
    public ResponseEntity<?> testConnection(@RequestBody ConnectionTest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return GradingResponse.signedIn(personRepo, userDetails, person -> {
            if (!isAdmin(person)) {
                return forbidden("Only admins can test a model server.");
            }
            try {
                return ResponseEntity.ok(house.listModels(request == null ? null : request.url(),
                        request == null ? null : request.apiKey()));
            } catch (AiProviderFailure e) {
                return unreachable(e);
            }
        });
    }

    /** Sends a tiny prompt to the saved model and reports how long it took. */
    @PostMapping("/settings/test-prompt")
    public ResponseEntity<?> testPrompt(@AuthenticationPrincipal UserDetails userDetails) {
        return GradingResponse.signedIn(personRepo, userDetails, person -> {
            if (!isAdmin(person)) {
                return forbidden("Only admins can test a model server.");
            }
            house.resetBreaker();
            long started = System.nanoTime();
            try {
                AiHouseModelClient.Reply reply = house.generate("Reply with only this JSON object: {\"ok\": true}");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("ok", reply.text().contains("ok"));
                body.put("model", reply.model());
                body.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
                return ResponseEntity.ok(body);
            } catch (AiProviderFailure e) {
                return unreachable(e);
            }
        });
    }

    private Map<String, Object> settingsBody(Person person) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("settings", settings.current());
        body.put("presets", AiHouseSettingsRepository.presets());
        body.put("canEdit", isAdmin(person));
        body.put("storageAvailable", settings.isStorageAvailable());
        body.put("keyEncryptionAvailable", cipher.isAvailable());
        return body;
    }

    private static boolean isAdmin(Person person) {
        return person.hasRole(AssignmentAuthorizationService.ROLE_ADMIN);
    }

    private static ResponseEntity<?> forbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "NOT_PERMITTED", "error", message));
    }

    private static ResponseEntity<?> unreachable(AiProviderFailure e) {
        // Application-owned message only; never the provider's body.
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("code", "HOUSE_MODEL_FAILED", "error", e.getMessage()));
    }
}
