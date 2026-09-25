package com.open.spring.mvc.assignments;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;

import lombok.RequiredArgsConstructor;

/** Synchronizes assignment course metadata with the canonical seeded course groups. */
@Service
@RequiredArgsConstructor
public class AssignmentCourseSyncService {

    public static class UnknownCourseException extends RuntimeException {
        private final List<String> unknownCourseCodes;

        public UnknownCourseException(List<String> unknownCourseCodes) {
            super("Unknown course code(s): " + String.join(", ", unknownCourseCodes));
            this.unknownCourseCodes = List.copyOf(unknownCourseCodes);
        }

        public List<String> getUnknownCourseCodes() {
            return unknownCourseCodes;
        }
    }

    private final GroupsJpaRepository groupsRepository;
    private final CourseGroupProperties courseGroupProperties;

    public List<String> normalizeCourseCodes(List<String> rawCourseCodes) {
        if (rawCourseCodes == null) {
            return List.of();
        }
        if (rawCourseCodes.isEmpty()) {
            throw new IllegalArgumentException("courseCodes must contain at least one course");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String rawCourseCode : rawCourseCodes) {
            if (rawCourseCode == null || rawCourseCode.isBlank()) {
                throw new IllegalArgumentException("courseCodes must contain only non-empty course names");
            }
            normalized.add(rawCourseCode.trim().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    /** Resolve every course before mutating an assignment, keeping synchronization atomic. */
    public List<Groups> resolveCourseGroups(List<String> normalizedCourseCodes) {
        Set<String> configured = new LinkedHashSet<>(courseGroupProperties.getGroupNames());
        List<Groups> resolved = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        for (String courseCode : normalizedCourseCodes) {
            if (!configured.contains(courseCode)) {
                unknown.add(courseCode);
                continue;
            }
            groupsRepository.findByName(courseCode)
                .ifPresentOrElse(resolved::add, () -> unknown.add(courseCode));
        }

        if (!unknown.isEmpty()) {
            throw new UnknownCourseException(unknown);
        }
        return resolved;
    }

    public boolean applyCourseGroups(Assignment assignment, List<Groups> courseGroups) {
        Set<String> desired = courseGroups.stream()
            .map(Groups::getName)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> current = new LinkedHashSet<>(courseCodesOf(assignment));
        if (current.equals(desired)) {
            return false;
        }
        assignment.getCourseGroups().clear();
        assignment.getCourseGroups().addAll(courseGroups);
        return true;
    }

    /** Return course codes in the canonical configured order. */
    public List<String> courseCodesOf(Assignment assignment) {
        if (assignment == null || assignment.getCourseGroups() == null) {
            return List.of();
        }
        Set<String> assigned = assignment.getCourseGroups().stream()
            .map(Groups::getName)
            .map(name -> name.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return courseGroupProperties.getGroupNames().stream()
            .filter(assigned::contains)
            .toList();
    }
}
