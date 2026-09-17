package com.homework.analysis.auth;

import java.time.Instant;

/**
 * 令牌解析后的当前主体。
 *
 * <p>教师与学生共用一条记录：{@code teacherId} 与 {@code studentId} 恰好只有一个非空，
 * 由 {@code role} 决定。用可空包装类型而不是 {@code 0} 或 {@code -1} 表示“不适用”，
 * 这样“令牌缺少主体”与“主体 ID 是 0”不会混为一谈。
 *
 * <p>学生令牌刻意不携带姓名、学号或班级名。
 */
public record AuthPrincipal(
    long userId,
    Long teacherId,
    Long studentId,
    String role,
    boolean passwordChangeRequired,
    Instant expiresAt) {

    public static final String ROLE_TEACHER = "TEACHER";
    public static final String ROLE_STUDENT = "STUDENT";

    public boolean isTeacher() {
        return ROLE_TEACHER.equals(role);
    }

    public boolean isStudent() {
        return ROLE_STUDENT.equals(role);
    }
}
