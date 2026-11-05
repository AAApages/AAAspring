package com.open.spring.mvc.sprintretro;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.sprintretro.SprintRetroService.CalendarSprint;
import com.open.spring.mvc.sprintretro.SprintRetroService.InvalidRetroException;
import com.open.spring.mvc.sprintretro.SprintRetroService.Progress;
import com.open.spring.mvc.sprintretro.SprintRetroService.ReflectionView;
import com.open.spring.mvc.sprintretro.SprintRetroService.RosterRow;
import com.open.spring.mvc.sprintretro.SprintRetroService.SprintInUseException;
import com.open.spring.mvc.sprintretro.SprintRetroService.SprintView;
import com.open.spring.mvc.sprintretro.SprintRetroService.StudentPart;
import com.open.spring.mvc.sprintretro.SprintRetroService.TeacherPart;
import com.open.spring.mvc.sprintretro.SprintRetroTemplate.Template;

/**
 * REST API for sprint reflections under /api/sprint-retros.
 *
 * Everyone signed in lists sprints, reads the Ground 0 template, and reads and saves their own
 * reflection and growth; the account comes from the JWT cookie. Teachers and admins also create,
 * edit and delete sprints, list students per sprint, read any student's reflection and growth,
 * and save their ranks and comments on it.
 */
@RestController
@RequestMapping("/api/sprint-retros")
public class SprintRetroApiRestController {

    /** Response of GET /api/sprint-retros; canManage tells the page to show the teacher tools. */
    public record SprintList(boolean canManage, List<SprintView> sprints) {
    }

    /** Body of POST and PUT /api/sprint-retros; endDate is optional. */
    public record SprintRequest(String name, LocalDate startDate, LocalDate endDate) {
    }

    /** Body of PUT /api/sprint-retros/calendar: the sprints in the school calendar. */
    public record CalendarRequest(List<CalendarSprint> sprints) {
    }

    private final SprintRetroService service;
    private final PersonJpaRepository personRepository;

    public SprintRetroApiRestController(SprintRetroService service, PersonJpaRepository personRepository) {
        this.service = service;
        this.personRepository = personRepository;
    }

    /** GET /api/sprint-retros - every sprint in date order. */
    @GetMapping
    public SprintList sprints(@AuthenticationPrincipal UserDetails user) {
        return new SprintList(SprintRetroService.isTeacher(caller(user)), service.sprints());
    }

    /** GET /api/sprint-retros/template - the Ground 0 checklist, items, rank scales and questions. */
    @GetMapping("/template")
    public Template template() {
        return SprintRetroTemplate.ground0();
    }

    /** POST /api/sprint-retros - creates a sprint; teachers and admins only, 400 on invalid input. */
    @PostMapping
    public SprintView create(@AuthenticationPrincipal UserDetails user, @RequestBody SprintRequest request) {
        return service.createSprint(teacher(user), request.name(), request.startDate(), request.endDate());
    }

    /** PUT /api/sprint-retros/calendar - makes the stored sprints match the school calendar; teachers and admins only. */
    @PutMapping("/calendar")
    public List<SprintView> syncCalendar(@AuthenticationPrincipal UserDetails user, @RequestBody CalendarRequest request) {
        return service.syncCalendar(teacher(user), request.sprints());
    }

    /** PUT /api/sprint-retros/{id} - renames or re-dates a sprint; teachers and admins only. */
    @PutMapping("/{id}")
    public ResponseEntity<SprintView> update(@AuthenticationPrincipal UserDetails user, @PathVariable Long id,
            @RequestBody SprintRequest request) {
        teacher(user);
        return found(service.updateSprint(id, request.name(), request.startDate(), request.endDate()));
    }

    /** DELETE /api/sprint-retros/{id} - 204 when deleted, 409 when students have reflected on it; teachers and admins only. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserDetails user, @PathVariable Long id) {
        teacher(user);
        return new ResponseEntity<>(service.deleteSprint(id) ? HttpStatus.NO_CONTENT : HttpStatus.NOT_FOUND);
    }

    /** GET /api/sprint-retros/{id}/reflection - the caller's reflection on a sprint, with the teacher's feedback. */
    @GetMapping("/{id}/reflection")
    public ResponseEntity<ReflectionView> myReflection(@AuthenticationPrincipal UserDetails user, @PathVariable Long id) {
        return found(service.reflection(id, caller(user)));
    }

    /** PUT /api/sprint-retros/{id}/reflection - saves the caller's ranks, writing and checklist. */
    @PutMapping("/{id}/reflection")
    public ResponseEntity<ReflectionView> saveMine(@AuthenticationPrincipal UserDetails user, @PathVariable Long id,
            @RequestBody StudentPart part) {
        return found(service.saveStudentPart(id, caller(user), part));
    }

    /** GET /api/sprint-retros/progress - the caller's ranks and writing across every sprint. */
    @GetMapping("/progress")
    public Progress myProgress(@AuthenticationPrincipal UserDetails user) {
        return service.progress(caller(user));
    }

    /** GET /api/sprint-retros/{id}/reflections - every student and whether they submitted; teachers and admins only. */
    @GetMapping("/{id}/reflections")
    public ResponseEntity<List<RosterRow>> roster(@AuthenticationPrincipal UserDetails user, @PathVariable Long id) {
        teacher(user);
        return found(service.roster(id));
    }

    /** GET /api/sprint-retros/{id}/reflections/{personId} - a student's reflection; teachers and admins only. */
    @GetMapping("/{id}/reflections/{personId}")
    public ResponseEntity<ReflectionView> studentReflection(@AuthenticationPrincipal UserDetails user,
            @PathVariable Long id, @PathVariable Long personId) {
        teacher(user);
        return found(service.reflection(id, person(personId)));
    }

    /** PUT /api/sprint-retros/{id}/reflections/{personId}/feedback - saves the teacher's ranks and comments. */
    @PutMapping("/{id}/reflections/{personId}/feedback")
    public ResponseEntity<ReflectionView> saveFeedback(@AuthenticationPrincipal UserDetails user,
            @PathVariable Long id, @PathVariable Long personId, @RequestBody TeacherPart part) {
        Person teacher = teacher(user);
        return found(service.saveTeacherPart(id, person(personId), teacher, part));
    }

    /** GET /api/sprint-retros/progress/{personId} - a student's growth; teachers and admins only. */
    @GetMapping("/progress/{personId}")
    public Progress studentProgress(@AuthenticationPrincipal UserDetails user, @PathVariable Long personId) {
        teacher(user);
        return service.progress(person(personId));
    }

    @ExceptionHandler(InvalidRetroException.class)
    public ResponseEntity<Map<String, String>> invalid(InvalidRetroException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(SprintInUseException.class)
    public ResponseEntity<Map<String, String>> inUse(SprintInUseException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    private static <T> ResponseEntity<T> found(Optional<T> value) {
        return value.map(ResponseEntity::ok).orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }

    private Person person(Long personId) {
        return personRepository.findById(personId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such student"));
    }

    private Person teacher(UserDetails user) {
        Person person = caller(user);
        if (!SprintRetroService.isTeacher(person)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only teachers can do this");
        }
        return person;
    }

    private Person caller(UserDetails user) {
        Person person = user == null ? null : personRepository.findByUid(user.getUsername());
        if (person == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to use sprint reflections");
        }
        return person;
    }
}
