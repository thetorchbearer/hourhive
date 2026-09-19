package com.hourhive.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hourhive.config.AppProperties;
import org.junit.jupiter.api.Test;

class TokenServiceTest {

    private static TokenService service(String secret, long ttlHours) {
        return new TokenService(new AppProperties("", secret, ttlHours, "*", 120));
    }

    @Test
    void issuedTokenVerifiesToSameUser() {
        TokenService s = service("unit-test-secret-unit-test-secret-1", 1);
        assertEquals(42L, s.verify(s.issue(42L)).getAsLong());
    }

    @Test
    void tamperedPayloadIsRejected() {
        TokenService s = service("unit-test-secret-unit-test-secret-1", 1);
        String forged = s.issue(43L).split("\\.")[0] + "." + s.issue(42L).split("\\.")[1];
        assertTrue(s.verify(forged).isEmpty());
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = service("secret-number-one-secret-number-one", 1).issue(7L);
        assertTrue(service("secret-number-two-secret-number-two", 1).verify(token).isEmpty());
    }

    @Test
    void expiredTokenIsRejected() {
        TokenService s = service("unit-test-secret-unit-test-secret-1", -1);
        assertTrue(s.verify(s.issue(5L)).isEmpty());
    }

    @Test
    void garbageIsRejected() {
        TokenService s = service("unit-test-secret-unit-test-secret-1", 1);
        assertTrue(s.verify("not-a-token").isEmpty());
        assertTrue(s.verify("a.b").isEmpty());
        assertTrue(s.verify("").isEmpty());
    }
}
