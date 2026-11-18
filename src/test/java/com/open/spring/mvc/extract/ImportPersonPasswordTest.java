package com.open.spring.mvc.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.extract.ImportationViewController.PersonEmpty;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonDetailsService;
import com.open.spring.mvc.person.PersonJpaRepository;

/** Importing a person back from an extract keeps their password unless a new one is sent. */
public class ImportPersonPasswordTest {

    private static final String STORED_HASH = "$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234";

    @Mock private PersonJpaRepository personJpaRepository;
    @Mock private PersonDetailsService personDetailsService;
    @InjectMocks private ImportationViewController controller;

    private Person stored;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(controller, "defaultPassword", "Default1!");
        stored = new Person();
        stored.setUid("importme");
        stored.setPassword(STORED_HASH);
        when(personJpaRepository.existsById(5L)).thenReturn(true);
        when(personJpaRepository.findById(5L)).thenReturn(Optional.of(stored));
    }

    private PersonEmpty extractWithPassword(String password) {
        return new PersonEmpty(5L, "importme", password, null, null, null, null, null, null);
    }

    @Test
    public void theExtractedHashIsNotHashedAgain() {
        assertEquals(HttpStatus.OK, controller.importPersonById(5L, extractWithPassword(STORED_HASH)).getStatusCode());
        assertEquals(STORED_HASH, stored.getPassword());
        verify(personDetailsService).save(stored, true);
    }

    @Test
    public void aNewPasswordIsStillChanged() {
        assertEquals(HttpStatus.OK, controller.importPersonById(5L, extractWithPassword("NewPass1!")).getStatusCode());
        assertEquals("NewPass1!", stored.getPassword());
        verify(personDetailsService).save(stored, false);
    }
}
