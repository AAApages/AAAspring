package com.open.spring.mvc.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

/**
 * The statement Flask signs at login: a verified mentor, or not, for one uid, until a time.
 * The example token was made by Flask (model/role_attestation.py, scripts/verify_role_attestation.py), so these
 * tests prove the two backends sign and check the same bytes.
 */
public class MentorRoleStatementTest {

    private static final String SECRET = "vector-secret";
    /** Made by Flask: {"exp":1900000000,"granted":true,"role":"ROLE_MENTOR","uid":"vector"} signed with SECRET. */
    private static final String FLASK_TOKEN = "eyJleHAiOjE5MDAwMDAwMDAsImdyYW50ZWQiOnRydWUsInJvbGUiOiJST0xFX01FTlRPUiIsInVpZCI6InZlY3RvciJ9.rxAWW97MDKO93UjLhg4roFpmjGRP2KP4eEOfiVjqWrY";

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A statement signed here, for the cases Flask's example does not cover. */
    private static String signed(String json, String secret, String label) throws Exception {
        String body = b64(json.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return body + "." + b64(mac.doFinal((label + body).getBytes(StandardCharsets.UTF_8)));
    }

    private static final String GOOD_JSON = "{\"exp\":1900000000,\"granted\":true,\"role\":\"ROLE_MENTOR\",\"uid\":\"someone\"}";

    @Test
    public void aStatementFlaskSignedIsAccepted() {
        Optional<MentorRoleStatement> statement = MentorRoleStatement.verify(FLASK_TOKEN, SECRET, 1_800_000_000L);
        assertTrue(statement.isPresent());
        assertEquals("vector", statement.get().getUid());
        assertEquals("ROLE_MENTOR", statement.get().getRole());
        assertTrue(statement.get().isGranted());
        assertEquals(1_900_000_000L, statement.get().getExpiresAt());
    }

    @Test
    public void theSameRulesAcceptOneSignedHere() throws Exception {
        assertTrue(MentorRoleStatement.verify(signed(GOOD_JSON, SECRET, "mentor-role-v1:"), SECRET, 1L).isPresent());
    }

    @Test
    public void aNotGrantedStatementIsAcceptedToo() throws Exception {
        String json = "{\"exp\":1900000000,\"granted\":false,\"role\":\"ROLE_MENTOR\",\"uid\":\"someone\"}";
        Optional<MentorRoleStatement> statement = MentorRoleStatement.verify(signed(json, SECRET, "mentor-role-v1:"), SECRET, 1L);
        assertTrue(statement.isPresent());
        assertFalse(statement.get().isGranted());
    }

    @Test
    public void anotherSecretIsRefused() {
        assertFalse(MentorRoleStatement.verify(FLASK_TOKEN, "another-secret", 1L).isPresent());
    }

    @Test
    public void aChangedPayloadIsRefused() throws Exception {
        String[] parts = FLASK_TOKEN.split("\\.");
        String forged = b64("{\"exp\":1900000000,\"granted\":true,\"role\":\"ROLE_ADMIN\",\"uid\":\"vector\"}".getBytes(StandardCharsets.UTF_8));
        assertFalse(MentorRoleStatement.verify(forged + "." + parts[1], SECRET, 1L).isPresent());
    }

    @Test
    public void aChangedSignatureIsRefused() {
        String bad = FLASK_TOKEN.substring(0, FLASK_TOKEN.length() - 2) + (FLASK_TOKEN.endsWith("AA") ? "BB" : "AA");
        assertFalse(MentorRoleStatement.verify(bad, SECRET, 1L).isPresent());
    }

    @Test
    public void aStatementPastItsEndIsRefused() {
        assertTrue(MentorRoleStatement.verify(FLASK_TOKEN, SECRET, 1_900_000_000L).isPresent() == false);
        assertTrue(MentorRoleStatement.verify(FLASK_TOKEN, SECRET, 1_899_999_999L).isPresent());
    }

    @Test
    public void aSignatureMadeForAnotherPurposeIsRefused() throws Exception {
        // a password-reset token signs "uid.exp.nonce"; nothing signed without this statement's label counts
        assertFalse(MentorRoleStatement.verify(signed(GOOD_JSON, SECRET, ""), SECRET, 1L).isPresent());
        assertFalse(MentorRoleStatement.verify(signed(GOOD_JSON, SECRET, "password-reset:"), SECRET, 1L).isPresent());
    }

    @Test
    public void nothingUsableIsRefusedWithoutAnError() throws Exception {
        for (String token : new String[] { null, "", "   ", "nodot", "a.b.c", ".", "!!!.???", "e30." + b64("x".getBytes(StandardCharsets.UTF_8)) }) {
            assertFalse(MentorRoleStatement.verify(token, SECRET, 1L).isPresent(), "token " + token);
        }
        assertFalse(MentorRoleStatement.verify(FLASK_TOKEN, null, 1L).isPresent());
        assertFalse(MentorRoleStatement.verify(FLASK_TOKEN, "", 1L).isPresent());
    }

    @Test
    public void aSignedPayloadMissingAFieldOrWithTheWrongTypeIsRefused() throws Exception {
        for (String json : new String[] {
                "{\"exp\":1900000000,\"granted\":true,\"role\":\"ROLE_MENTOR\"}",
                "{\"exp\":1900000000,\"granted\":true,\"uid\":\"someone\"}",
                "{\"exp\":1900000000,\"role\":\"ROLE_MENTOR\",\"uid\":\"someone\"}",
                "{\"granted\":true,\"role\":\"ROLE_MENTOR\",\"uid\":\"someone\"}",
                "{\"exp\":\"soon\",\"granted\":true,\"role\":\"ROLE_MENTOR\",\"uid\":\"someone\"}",
                "{\"exp\":1900000000,\"granted\":\"yes\",\"role\":\"ROLE_MENTOR\",\"uid\":\"someone\"}",
                "{\"exp\":1900000000,\"granted\":true,\"role\":7,\"uid\":\"someone\"}",
                "{\"exp\":1900000000,\"granted\":true,\"role\":\"ROLE_MENTOR\",\"uid\":\"\"}",
                "[1,2,3]", "not json" }) {
            assertFalse(MentorRoleStatement.verify(signed(json, SECRET, "mentor-role-v1:"), SECRET, 1L).isPresent(), json);
        }
    }
}
