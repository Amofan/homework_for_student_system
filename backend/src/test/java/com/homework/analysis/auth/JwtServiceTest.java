package com.homework.analysis.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-14T06:00:00Z"), ZoneOffset.UTC);
    private final JwtService service = new JwtService(
        "test-secret-test-secret-test-secret-1234", "homework-analysis-test", 30, clock);

    @Test
    void tokenCarriesOnlyRequiredIdentityClaims() {
        String token = service.issue(12L, 34L, "TEACHER");
        AuthPrincipal principal = service.parse(token);

        assertThat(principal.userId()).isEqualTo(12L);
        assertThat(principal.teacherId()).isEqualTo(34L);
        assertThat(principal.role()).isEqualTo("TEACHER");
        assertThat(principal.expiresAt()).isEqualTo(Instant.parse("2026-09-14T06:30:00Z"));
    }
}
