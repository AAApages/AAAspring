package com.open.spring.mvc.extract;

import java.util.*;
import java.time.LocalDate;

import lombok.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.Convert;

import com.vladmihalcea.hibernate.type.json.JsonType;

///// entity classes
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.assignments.Assignment;
import com.open.spring.mvc.assignments.AssignmentQueue;
import com.open.spring.mvc.calendar.CalendarEventEntity;

///// repositories
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.assignments.AssignmentJpaRepository;
import com.open.spring.mvc.calendar.CalendarEventJpaRepository;



@Controller
@RequestMapping("mvc/extract")
public class ExtractionViewController {
/////////////////////////////////////////
/// Autowired Jpa Repositories

@Autowired
private PersonJpaRepository personJpaRepository;

@Autowired
private GroupsJpaRepository groupsJpaRepository;

@Autowired
private AssignmentJpaRepository assignmentJpaRepository;

@Autowired
private CalendarEventJpaRepository calendarEventRepository;

/////////////////////////////////////////
/// Export Objects


//person class based on person table schema (no relationships)
    @Data
    @AllArgsConstructor
    @Convert(attributeName = "person", converter = JsonType.class)
    public class PersonEmpty {
        private Long id;
        private String uid;
        private String password;
        private String email;
        private String name;
        private String pfp;
        private String sid;
        private Boolean kasmServerNeeded;
        private Map<String, Map<String, Object>> stats;
    }

//group class based on group table schema (no relationships)
    @Data
    @AllArgsConstructor
    @Convert(attributeName = "group", converter = JsonType.class)
    public class GroupEmpty {
        private Long id;
        private String name;
        private String period;
    }

//assignment class based on assignment table schema (no relationships)
    @Data
    @NoArgsConstructor
    @Convert(attributeName = "assignment", converter = JsonType.class)
    public class AssignmentEmpty {
        private Long id;
        private String name;
        private String type;
        private String description;
        private String dueDate;
        private String timestamp;
        private Double points;
        private Long presentationLength;
        private AssignmentQueue assignmentQueue;

        public AssignmentEmpty(Long id, String name, String type, String description, String dueDate, String timestamp, Double points, Long presentationLength, AssignmentQueue assignmentQueue) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.description = description;
            this.dueDate = dueDate;
            this.timestamp = timestamp;
            this.points = points;
            this.presentationLength = presentationLength;
            this.assignmentQueue = assignmentQueue;
        }
    }

//calendar event class based on calendar event table schema (no relationships)
    @Data
    @NoArgsConstructor
    @Convert(attributeName = "calendarEvent", converter = JsonType.class)
    public class CalendarEventEmpty {
        private Long id;
        private LocalDate date;
        private String title;
        private String description;
        private String type;
        private String period;

        public CalendarEventEmpty(Long id, LocalDate date, String title, String description, String type, String period) {
            this.id = id;
            this.date = date;
            this.title = title;
            this.description = description;
            this.type = type;
            this.period = period;
        }
    }


/////////////////////////////////////////
/// Single Extracts

    
    @GetMapping("/person/{id}")
    public ResponseEntity<PersonEmpty> extractPersonById(@PathVariable("id") long id){
        if(!personJpaRepository.existsById(id)){
            return new ResponseEntity<PersonEmpty>(HttpStatus.NOT_FOUND);
        }
        Person person = personJpaRepository.findById(id).get();
        PersonEmpty personEmpty = new PersonEmpty(
            person.getId(), 
            person.getUid(), 
            person.getPassword(), 
            person.getEmail(), 
            person.getName(), 
            person.getPfp(), 
            person.getSid(),  
            person.getKasmServerNeeded(), 
            person.getStats());
        return new ResponseEntity<PersonEmpty>(personEmpty,HttpStatus.OK);
    }

    @GetMapping("/group/{id}")
    public ResponseEntity<PersonEmpty> extractGroupById(@PathVariable("id") long id){
        if(!personJpaRepository.existsById(id)){
            return new ResponseEntity<PersonEmpty>(HttpStatus.NOT_FOUND);
        }
        Person person = personJpaRepository.findById(id).get();
        PersonEmpty personEmpty = new PersonEmpty(
            person.getId(), 
            person.getUid(), 
            person.getPassword(), 
            person.getEmail(), 
            person.getName(), 
            person.getPfp(), 
            person.getSid(), 
            person.getKasmServerNeeded(), 
            person.getStats());
        return new ResponseEntity<PersonEmpty>(personEmpty,HttpStatus.OK);
    }
   
/////////////////////////////////////////
/// Multi Extracts


    @GetMapping("all/person")
    public ResponseEntity<List<PersonEmpty>> extractAllPerson(){
        List<Person> personlList = personJpaRepository.findAll();
        ArrayList<PersonEmpty> personEmpties = new ArrayList<PersonEmpty>(0);
        personlList.stream().forEach(person ->{
            personEmpties.add(new PersonEmpty(
            person.getId(), 
            person.getUid(), 
            person.getPassword(), 
            person.getEmail(), 
            person.getName(), 
            person.getPfp(), 
            person.getSid(), 
            person.getKasmServerNeeded(), 
            person.getStats()));
        });
        return new ResponseEntity<List<PersonEmpty>>(personEmpties,HttpStatus.OK);
    }

    @GetMapping("/assignment/{id}")
    public ResponseEntity<AssignmentEmpty> extractAssignmentById(@PathVariable("id") long id){
        if(!assignmentJpaRepository.existsById(id)){
            return new ResponseEntity<AssignmentEmpty>(HttpStatus.NOT_FOUND);
        }
        Assignment assignment = assignmentJpaRepository.findById(id).get();
        AssignmentEmpty assignmentEmpty = new AssignmentEmpty(
            assignment.getId(),
            assignment.getName(),
            assignment.getType(),
            assignment.getDescription(),
            assignment.getDueDate(),
            assignment.getTimestamp(),
            assignment.getPoints(),
            assignment.getPresentationLength(),
            assignment.getAssignmentQueue()
        );
        return new ResponseEntity<AssignmentEmpty>(assignmentEmpty, HttpStatus.OK);
    }

    @GetMapping("all/assignment")
    public ResponseEntity<List<AssignmentEmpty>> extractAllAssignment(){
        List<Assignment> assignmentList = assignmentJpaRepository.findAll();
        ArrayList<AssignmentEmpty> assignmentEmpties = new ArrayList<AssignmentEmpty>(0);
        assignmentList.stream().forEach(assignment -> {
            assignmentEmpties.add(new AssignmentEmpty(
                assignment.getId(),
                assignment.getName(),
                assignment.getType(),
                assignment.getDescription(),
                assignment.getDueDate(),
                assignment.getTimestamp(),
                assignment.getPoints(),
                assignment.getPresentationLength(),
                assignment.getAssignmentQueue()
            ));
        });
        return new ResponseEntity<List<AssignmentEmpty>>(assignmentEmpties, HttpStatus.OK);
    }

    @GetMapping("/calendar-event/{id}")
    public ResponseEntity<CalendarEventEmpty> extractCalendarEventById(@PathVariable("id") long id){
        if(!calendarEventRepository.existsById(id)){
            return new ResponseEntity<CalendarEventEmpty>(HttpStatus.NOT_FOUND);
        }
        CalendarEventEntity calendarEvent = calendarEventRepository.findById(id).get();
        CalendarEventEmpty calendarEventEmpty = new CalendarEventEmpty(
            calendarEvent.getId(),
            calendarEvent.getDate(),
            calendarEvent.getTitle(),
            calendarEvent.getDescription(),
            calendarEvent.getType(),
            calendarEvent.getPeriod()
        );
        return new ResponseEntity<CalendarEventEmpty>(calendarEventEmpty, HttpStatus.OK);
    }

    @GetMapping("all/calendar-event")
    public ResponseEntity<List<CalendarEventEmpty>> extractAllCalendarEvent(){
        List<CalendarEventEntity> calendarEventList = calendarEventRepository.findAll();
        ArrayList<CalendarEventEmpty> calendarEventEmpties = new ArrayList<CalendarEventEmpty>(0);
        calendarEventList.stream().forEach(calendarEvent -> {
            calendarEventEmpties.add(new CalendarEventEmpty(
                calendarEvent.getId(),
                calendarEvent.getDate(),
                calendarEvent.getTitle(),
                calendarEvent.getDescription(),
                calendarEvent.getType(),
                calendarEvent.getPeriod()
            ));
        });
        return new ResponseEntity<List<CalendarEventEmpty>>(calendarEventEmpties, HttpStatus.OK);
    }
}
