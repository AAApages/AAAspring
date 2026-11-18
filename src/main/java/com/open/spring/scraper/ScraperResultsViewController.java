package com.open.spring.scraper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/** Serves the teacher/admin scraper results dashboard page; data comes from ScrapedResultController. */
@Controller
public class ScraperResultsViewController {

    @Autowired
    private PersonJpaRepository personRepo;

    @GetMapping("/mvc/scraper/results")
    public String resultsDashboard(@AuthenticationPrincipal UserDetails userDetails) {
        String uid = userDetails == null ? null : userDetails.getUsername();
        Person user = uid == null ? null : personRepo.findByUid(uid);

        if (user == null || (!user.hasRole("ROLE_TEACHER") && !user.hasRole("ROLE_ADMIN"))) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "You must be a teacher or admin to view the scraper results dashboard"
            );
        }

        return "scraper/results_dashboard";
    }
}
