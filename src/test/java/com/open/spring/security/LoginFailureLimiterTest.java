package com.open.spring.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Wrong passwords are capped per account and IP, without locking the student out elsewhere. */
class LoginFailureLimiterTest {

    private static final String IP = "10.0.0.1";

    private LoginFailureLimiter limiter() {
        return new LoginFailureLimiter(3, 15);
    }

    @Test
    void blocksAfterTheLastAllowedFailure() {
        LoginFailureLimiter limiter = limiter();
        for (int i = 0; i < 2; i++) limiter.recordFailure("toby", IP);
        assertFalse(limiter.isBlocked("toby", IP), "two of three failures still allows a try");
        limiter.recordFailure("toby", IP);
        assertTrue(limiter.isBlocked("toby", IP), "the third failure blocks");
    }

    @Test
    void uidCaseDoesNotDodgeTheCap() {
        LoginFailureLimiter limiter = limiter();
        limiter.recordFailure("Toby", IP);
        limiter.recordFailure("TOBY", IP);
        limiter.recordFailure("toby", IP);
        assertTrue(limiter.isBlocked("tObY", IP));
    }

    @Test
    void anotherIpOrAccountIsNotBlocked() {
        LoginFailureLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) limiter.recordFailure("toby", IP);
        assertFalse(limiter.isBlocked("toby", "10.0.0.2"), "the student on their own machine can still log in");
        assertFalse(limiter.isBlocked("hop", IP), "other accounts from the same school IP are unaffected");
    }

    @Test
    void successClearsTheCount() {
        LoginFailureLimiter limiter = limiter();
        for (int i = 0; i < 2; i++) limiter.recordFailure("toby", IP);
        limiter.recordSuccess("toby", IP);
        limiter.recordFailure("toby", IP);
        assertFalse(limiter.isBlocked("toby", IP));
    }
}
