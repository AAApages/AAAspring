package com.open.spring.mvc.person;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONObject;

import lombok.Getter;

/**
 * What Flask says about one person's mentor role: verified (granted) or not, until a time.
 *
 * Whether a Teacher or Admin has verified a mentor is kept in Flask, which signs a statement at login. The page
 * passes it to POST /api/person/mentor/confirm, and this class checks it. The same secret, RESET_TOKEN_SECRET,
 * signs password-reset tokens, so the signed text starts with a label, and has no dot in it, so a signature made
 * for any other purpose can never be accepted here.
 *
 *   token     = base64url(json) + "." + signature
 *   json      = {"exp": seconds, "granted": true or false, "role": "ROLE_MENTOR", "uid": uid}
 *   signature = base64url(HMAC-SHA256(secret, "mentor-role-v1:" + base64url(json)))
 *
 * Flask's side is model/role_attestation.py.
 */
@Getter
public final class MentorRoleStatement {

    private static final String LABEL = "mentor-role-v1:";

    private final String uid;
    private final String role;
    private final boolean granted;
    private final long expiresAt;

    private MentorRoleStatement(String uid, String role, boolean granted, long expiresAt) {
        this.uid = uid;
        this.role = role;
        this.granted = granted;
        this.expiresAt = expiresAt;
    }

    /**
     * The statement if the token is signed with this secret, well formed, and not past its end. Anything else,
     * however it is wrong, is simply not a statement: no error, no detail about why.
     */
    public static Optional<MentorRoleStatement> verify(String token, String secret, long nowEpochSeconds) {
        if (token == null || secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.trim().split("\\.", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return Optional.empty();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((LABEL + parts[0]).getBytes(StandardCharsets.UTF_8));
            byte[] given = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(expected, given)) {  // constant time
                return Optional.empty();
            }
            JSONObject json = new JSONObject(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            Object uid = json.opt("uid");
            Object role = json.opt("role");
            Object granted = json.opt("granted");
            Object exp = json.opt("exp");
            if (!(uid instanceof String) || ((String) uid).isBlank() || !(role instanceof String) || !(granted instanceof Boolean)
                    || !(exp instanceof Number)) {
                return Optional.empty();
            }
            long expiresAt = ((Number) exp).longValue();
            if (expiresAt <= nowEpochSeconds) {
                return Optional.empty();
            }
            return Optional.of(new MentorRoleStatement((String) uid, (String) role, (Boolean) granted, expiresAt));
        } catch (Exception e) {  // bad base64, bad JSON: not a statement
            return Optional.empty();
        }
    }

    public static Optional<MentorRoleStatement> verify(String token, String secret) {
        return verify(token, secret, Instant.now().getEpochSecond());
    }
}
