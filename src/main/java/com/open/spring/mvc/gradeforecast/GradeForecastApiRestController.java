package com.open.spring.mvc.gradeforecast;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.gradeforecast.GradeForecastService.InvalidForecastException;
import com.open.spring.mvc.gradeforecast.GradeForecastService.Retrospective;
import com.open.spring.mvc.gradeforecast.GrowthGoalEntity.Outcome;
import com.open.spring.mvc.gradeforecast.GrowthGoalService.GoalConflictException;
import com.open.spring.mvc.gradeforecast.GrowthGoalService.GoalView;
import com.open.spring.mvc.gradeforecast.GrowthGoalService.Goals;
import com.open.spring.mvc.gradeforecast.GrowthGoalService.InvalidGoalException;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * REST API for grade forecasts, their retrospective and growth goals under /api/grade-forecasts.
 *
 * Students record, read and delete only their own forecasts and goals; the account comes from
 * the JWT cookie, never from the request. Teachers and admins can also read any student's
 * retrospective and goals. Access otherwise follows the shared /api/** rule in SecurityConfig.
 */
@RestController
@RequestMapping("/api/grade-forecasts")
public class GradeForecastApiRestController {

    /** Body of POST /api/grade-forecasts: the Grade Predictor's sliders, its result and the student's guess. */
    public record RecordRequest(
            Integer attendance,
            Integer workHabits,
            Integer behavior,
            Integer timeliness,
            Integer techSense,
            Integer techTalk,
            Integer techGrowth,
            Integer advocacy,
            Integer communication,
            Integer integrity,
            Integer organization,
            Double modelGrade,
            Double studentGuess) {

        GradeForecastService.Forecast toForecast() {
            Map<String, Integer> ratings = new LinkedHashMap<>();
            ratings.put("attendance", attendance);
            ratings.put("workHabits", workHabits);
            ratings.put("behavior", behavior);
            ratings.put("timeliness", timeliness);
            ratings.put("techSense", techSense);
            ratings.put("techTalk", techTalk);
            ratings.put("techGrowth", techGrowth);
            ratings.put("advocacy", advocacy);
            ratings.put("communication", communication);
            ratings.put("integrity", integrity);
            ratings.put("organization", organization);
            return new GradeForecastService.Forecast(ratings, modelGrade, studentGuess);
        }
    }

    /** DTO the API returns for one recorded forecast. */
    public record ForecastRecord(Long id, LocalDateTime createdAt, Double modelGrade, Double studentGuess,
            Double currentGrade) {

        static ForecastRecord of(GradeForecastEntity e) {
            return new ForecastRecord(e.getId(), e.getCreatedAt(), e.getModelGrade(), e.getStudentGuess(),
                    e.getCurrentGrade());
        }
    }

    /** Body of POST /api/grade-forecasts/goals; checkInDate is optional. */
    public record GoalRequest(String skill, String action, LocalDate checkInDate) {
    }

    /** Body of POST /api/grade-forecasts/goals/{id}/check-in. */
    public record CheckInRequest(Outcome outcome, String note) {
    }

    private final GradeForecastService forecastService;
    private final GrowthGoalService goalService;
    private final PersonJpaRepository personRepository;

    public GradeForecastApiRestController(GradeForecastService forecastService, GrowthGoalService goalService,
            PersonJpaRepository personRepository) {
        this.forecastService = forecastService;
        this.goalService = goalService;
        this.personRepository = personRepository;
    }

    /** POST /api/grade-forecasts - records the caller's forecast; 400 on invalid input. */
    @PostMapping
    public ForecastRecord record(@AuthenticationPrincipal UserDetails user, @RequestBody RecordRequest request) {
        return ForecastRecord.of(forecastService.record(caller(user), request.toForecast()));
    }

    /** GET /api/grade-forecasts/retrospective - the caller's forecast retrospective. */
    @GetMapping("/retrospective")
    public Retrospective myRetrospective(@AuthenticationPrincipal UserDetails user) {
        return forecastService.retrospective(caller(user));
    }

    /** DELETE /api/grade-forecasts/{id} - 204 when deleted, 404 when the caller has no forecast with that id. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserDetails user, @PathVariable Long id) {
        boolean deleted = forecastService.delete(caller(user), id);
        return new ResponseEntity<>(deleted ? HttpStatus.NO_CONTENT : HttpStatus.NOT_FOUND);
    }

    /** GET /api/grade-forecasts/goals - the caller's active goal, finished goals and totals. */
    @GetMapping("/goals")
    public Goals myGoals(@AuthenticationPrincipal UserDetails user) {
        return goalService.list(caller(user));
    }

    /** POST /api/grade-forecasts/goals - starts a goal; 400 on invalid input, 409 while another goal is active. */
    @PostMapping("/goals")
    public GoalView startGoal(@AuthenticationPrincipal UserDetails user, @RequestBody GoalRequest request) {
        return goalService.start(caller(user), request.skill(), request.action(), request.checkInDate());
    }

    /** POST /api/grade-forecasts/goals/{id}/check-in - finishes the caller's active goal; 404 when there is none with that id. */
    @PostMapping("/goals/{id}/check-in")
    public ResponseEntity<GoalView> checkIn(@AuthenticationPrincipal UserDetails user, @PathVariable Long id,
            @RequestBody CheckInRequest request) {
        return goalService.checkIn(caller(user), id, request.outcome(), request.note())
                .map(ResponseEntity::ok)
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }

    /** DELETE /api/grade-forecasts/goals/{id} - 204 when deleted, 404 when the caller has no goal with that id. */
    @DeleteMapping("/goals/{id}")
    public ResponseEntity<Void> deleteGoal(@AuthenticationPrincipal UserDetails user, @PathVariable Long id) {
        return new ResponseEntity<>(goalService.delete(caller(user), id) ? HttpStatus.NO_CONTENT : HttpStatus.NOT_FOUND);
    }

    /** GET /api/grade-forecasts/student/{personId}/retrospective - a student's retrospective; teachers and admins only. */
    @GetMapping("/student/{personId}/retrospective")
    public Retrospective studentRetrospective(@AuthenticationPrincipal UserDetails user, @PathVariable Long personId) {
        return forecastService.retrospective(studentForTeacher(user, personId));
    }

    /** GET /api/grade-forecasts/student/{personId}/goals - a student's goals; teachers and admins only. */
    @GetMapping("/student/{personId}/goals")
    public Goals studentGoals(@AuthenticationPrincipal UserDetails user, @PathVariable Long personId) {
        return goalService.list(studentForTeacher(user, personId));
    }

    @ExceptionHandler(InvalidForecastException.class)
    public ResponseEntity<Map<String, String>> invalid(InvalidForecastException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidGoalException.class)
    public ResponseEntity<Map<String, String>> invalidGoal(InvalidGoalException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(GoalConflictException.class)
    public ResponseEntity<Map<String, String>> goalConflict(GoalConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    private Person studentForTeacher(UserDetails user, Long personId) {
        Person teacher = caller(user);
        if (!teacher.hasRole("ROLE_TEACHER") && !teacher.hasRole("ROLE_ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only teachers can view another student's forecasts");
        }
        return personRepository.findById(personId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such student"));
    }

    private Person caller(UserDetails user) {
        Person person = user == null ? null : personRepository.findByUid(user.getUsername());
        if (person == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to use grade forecasts");
        }
        return person;
    }
}
