package com.homework.analysis.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-14T06:00:00Z"), ZoneOffset.UTC);
    private final JwtService service = new JwtService(
        "test-secret-test-secret-test-secret-1234", "homework-analysis-test", 30, clock);

    @Test
    void 教师令牌只带教师标识且不含学生字段() {
        AuthPrincipal principal = service.parse(service.issueTeacher(12L, 34L));

        assertThat(principal.userId()).isEqualTo(12L);
        assertThat(principal.teacherId()).isEqualTo(34L);
        assertThat(principal.studentId()).isNull();
        assertThat(principal.role()).isEqualTo(AuthPrincipal.ROLE_TEACHER);
        assertThat(principal.isTeacher()).isTrue();
        assertThat(principal.isStudent()).isFalse();
        assertThat(principal.passwordChangeRequired()).isFalse();
        assertThat(principal.expiresAt()).isEqualTo(Instant.parse("2026-09-14T06:30:00Z"));
    }

    @Test
    void 学生令牌带学号与首次改密标志且不含教师字段() {
        AuthPrincipal principal = service.parse(service.issueStudent(2L, 1001L, true));

        assertThat(principal.userId()).isEqualTo(2L);
        assertThat(principal.studentId()).isEqualTo(1001L);
        assertThat(principal.teacherId()).isNull();
        assertThat(principal.role()).isEqualTo(AuthPrincipal.ROLE_STUDENT);
        assertThat(principal.isStudent()).isTrue();
        assertThat(principal.passwordChangeRequired()).isTrue();
    }

    @Test
    void 学生令牌已完成改密时不带改密标志() {
        assertThat(service.parse(service.issueStudent(2L, 1001L, false)).passwordChangeRequired())
            .isFalse();
    }

    @Test
    @SuppressWarnings("deprecation")
    void 兼容重载仍签发教师令牌但拒绝其他角色() {
        assertThat(service.parse(service.issue(12L, 34L, "TEACHER")).teacherId()).isEqualTo(34L);

        // 旧签名只有 teacherId 参数，无法表达学生主体；与其静默签出错误令牌，不如直接失败。
        assertThatThrownBy(() -> service.issue(1L, 2L, AuthPrincipal.ROLE_STUDENT))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
