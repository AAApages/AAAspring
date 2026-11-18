package com.open.spring.mvc.synergy;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Gradebook pages. They render a shell and load their data from {@code /api/synergy/gradebook/**}
 * and {@code /api/submissions/ai-queue}, so no page loads every student or assignment up front.
 */
@Controller
@RequestMapping("/mvc/synergy")
public class SynergyViewController {
    @Autowired
    private SynergyGradeRequestJpaRepository gradeRequestRepository;

    @Autowired
    private PersonJpaRepository personRepository;

    @Autowired
    private GradebookService gradebookService;

    private Person currentPerson(UserDetails userDetails) {
        Person user = userDetails == null ? null : personRepository.findByUid(userDetails.getUsername());
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You must be a logged in user to view this");
        }
        return user;
    }

    private static boolean isStaff(Person person) {
        return person.hasRole("ROLE_TEACHER") || person.hasRole("ROLE_ADMIN");
    }

    /**
     * Teachers and admins get the course card grid; everyone else goes straight to their own card.
     */
    @GetMapping("/gradebook")
    public String gradebook(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        Person user = currentPerson(userDetails);
        if (!isStaff(user)) {
            return "redirect:/mvc/synergy/gradebook/student/" + user.getId();
        }
        model.addAttribute("courses", gradebookService.courses());
        return "synergy/gradebook";
    }

    /** One student's card. Students may only open their own. */
    @GetMapping("/gradebook/student/{personId}")
    public String studentCard(@PathVariable Long personId, Model model, @AuthenticationPrincipal UserDetails userDetails) {
        Person user = currentPerson(userDetails);
        boolean staff = isStaff(user);
        if (!staff && !user.getId().equals(personId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only view your own grades");
        }
        Person student = personRepository.findById(personId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));
        model.addAttribute("personId", student.getId());
        model.addAttribute("studentName", student.getName());
        model.addAttribute("isStaff", staff);
        return "synergy/student_card";
    }

    /** How long until the user's submitted work is AI graded. */
    @GetMapping("/ai-queue")
    public String aiQueue(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        model.addAttribute("isStaff", isStaff(currentPerson(userDetails)));
        return "synergy/ai_queue";
    }

    /**
     * A page to view grade requests.
     * @param model The parameters for the webpage
     * @return The template for viewing grade requests
     */
    @GetMapping("/view-grade-requests")
    public String viewRequests(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        if (!isStaff(currentPerson(userDetails))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher or admin role required");
        }
        List<SynergyGradeRequest> requests = gradeRequestRepository.findAll();
        model.addAttribute("requests", requests);
        return "synergy/view_grade_requests";
    }

    /**
     * A page to create grade requests. Staff search for any student; students only see their
     * own submitted assignments. {@code assignmentId} pre-selects an assignment (from the card view).
     */
    @GetMapping("/create-grade-request")
    public String createGradeRequest(Model model, @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) Long assignmentId) {
        Person user = currentPerson(userDetails);
        model.addAttribute("isStaff", isStaff(user));
        model.addAttribute("currentPersonId", user.getId());
        model.addAttribute("currentName", user.getName());
        model.addAttribute("preselectAssignmentId", assignmentId);
        return "synergy/create_grade_request";
    }
}
