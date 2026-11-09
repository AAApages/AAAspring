package com.open.spring.mvc.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.extract.ExtractionViewController.GroupEmpty;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.PersonJpaRepository;

/** Exporting one group reads the groups table and sends back the group, never a person. */
public class ExtractGroupByIdTest {

    @Mock private GroupsJpaRepository groupsJpaRepository;
    @Mock private PersonJpaRepository personJpaRepository;
    @InjectMocks private ExtractionViewController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void aGroupIsExportedWithItsNameAndPeriod() {
        Groups group = new Groups();
        ReflectionTestUtils.setField(group, "id", 7L);
        group.setName("Team Seven");
        group.setPeriod("3");
        when(groupsJpaRepository.findById(7L)).thenReturn(Optional.of(group));

        ResponseEntity<GroupEmpty> response = controller.extractGroupById(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(7L, response.getBody().getId());
        assertEquals("Team Seven", response.getBody().getName());
        assertEquals("3", response.getBody().getPeriod());
        verifyNoInteractions(personJpaRepository);
    }

    @Test
    public void aMissingGroupIsNotFound() {
        when(groupsJpaRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<GroupEmpty> response = controller.extractGroupById(99L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }
}
