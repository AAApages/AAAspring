package com.open.spring.mvc.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * POST /api/person/mentor/confirm: Spring gives a person ROLE_MENTOR, or takes it away, only on a statement
 * Flask signed about that same person.
 */
public class MentorConfirmTest {

    private static final String SECRET = "shared-test-secret";

    @Mock private PersonDetailsService personDetailsService;
    @Mock private Authentication authentication;

    private PersonApiController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        controller = new PersonApiController();
        ReflectionTestUtils.setField(controller, "personDetailsService", personDetailsService);
        ReflectionTestUtils.setField(controller, "mentorStatementSecret", SECRET);
        when(authentication.getName()).thenReturn("someone");
        when(personDetailsService.setRole(anyString(), anyString(), anyBoolean())).thenReturn(true);
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String statement(String uid, String role, boolean granted, long exp, String secret) throws Exception {
        String json = "{\"exp\":" + exp + ",\"granted\":" + granted + ",\"role\":\"" + role + "\",\"uid\":\"" + uid + "\"}";
        String body = b64(json.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return body + "." + b64(mac.doFinal(("mentor-role-v1:" + body).getBytes(StandardCharsets.UTF_8)));
    }

    private static long inAMinute() {
        return Instant.now().getEpochSecond() + 60;
    }

    private ResponseEntity<Object> confirm(String attestation) {
        PersonApiController.MentorConfirmDto dto = new PersonApiController.MentorConfirmDto();
        dto.setAttestation(attestation);
        return controller.confirmMentorRole(dto, authentication);
    }

    @Test
    public void aVerifiedMentorGetsTheRole() throws Exception {
        ResponseEntity<Object> response = confirm(statement("someone", "ROLE_MENTOR", true, inAMinute(), SECRET));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(personDetailsService).setRole("someone", "ROLE_MENTOR", true);
    }

    @Test
    public void aMentorWhoIsNotVerifiedAnyMoreLosesTheRole() throws Exception {
        ResponseEntity<Object> response = confirm(statement("someone", "ROLE_MENTOR", false, inAMinute(), SECRET));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(personDetailsService).setRole("someone", "ROLE_MENTOR", false);
    }

    @Test
    public void aStatementAboutSomeoneElseChangesNothing() throws Exception {
        ResponseEntity<Object> response = confirm(statement("somebody-else", "ROLE_MENTOR", true, inAMinute(), SECRET));
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(personDetailsService, never()).setRole(anyString(), anyString(), anyBoolean());
    }

    @Test
    public void aBadSignatureOrAnExpiredStatementChangesNothing() throws Exception {
        assertEquals(HttpStatus.FORBIDDEN, confirm(statement("someone", "ROLE_MENTOR", true, inAMinute(), "another-secret")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, confirm(statement("someone", "ROLE_MENTOR", true, Instant.now().getEpochSecond() - 5, SECRET)).getStatusCode());
        verify(personDetailsService, never()).setRole(anyString(), anyString(), anyBoolean());
    }

    @Test
    public void flaskCanOnlyEverSetTheMentorRole() throws Exception {
        for (String role : new String[] { "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_USER", "ROLE_STUDENT" }) {
            ResponseEntity<Object> response = confirm(statement("someone", role, true, inAMinute(), SECRET));
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), role);
        }
        verify(personDetailsService, never()).setRole(anyString(), anyString(), anyBoolean());
    }

    @Test
    public void withNoSharedSecretNothingIsAccepted() throws Exception {
        ReflectionTestUtils.setField(controller, "mentorStatementSecret", "");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, confirm(statement("someone", "ROLE_MENTOR", true, inAMinute(), SECRET)).getStatusCode());
        verify(personDetailsService, never()).setRole(anyString(), anyString(), anyBoolean());
    }

    @Test
    public void aMissingStatementIsABadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST, confirm(null).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, confirm("  ").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.confirmMentorRole(null, authentication).getStatusCode());
        verify(personDetailsService, never()).setRole(any(), any(), anyBoolean());
    }

    @Test
    public void aPersonSpringDoesNotHaveIsNotFound() throws Exception {
        when(personDetailsService.setRole(anyString(), anyString(), anyBoolean())).thenReturn(false);
        assertEquals(HttpStatus.NOT_FOUND, confirm(statement("someone", "ROLE_MENTOR", true, inAMinute(), SECRET)).getStatusCode());
    }
}
