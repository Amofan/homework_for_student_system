package com.homework.analysis.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AuthRepository {
    private final JdbcClient jdbc;

    AuthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<LoginAccount> findLoginAccount(String username) {
        return jdbc.sql("""
                select u.id as user_id, u.password_hash, u.role, u.enabled,
                       t.id as teacher_id, t.display_name
                from app_user u
                join teacher t on t.user_id = u.id
                where u.username = :username
                """)
            .param("username", username)
            .query((rs, rowNum) -> new LoginAccount(
                rs.getLong("user_id"),
                rs.getLong("teacher_id"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getBoolean("enabled"),
                rs.getString("display_name")))
            .optional();
    }

    Optional<TeacherProfile> findTeacher(long teacherId) {
        return jdbc.sql("""
                select t.id as teacher_id, t.display_name, t.school_name
                from teacher t
                join app_user u on u.id = t.user_id
                where t.id = :teacherId and u.enabled = true
                """)
            .param("teacherId", teacherId)
            .query((rs, rowNum) -> new TeacherProfile(
                rs.getLong("teacher_id"),
                rs.getString("display_name"),
                rs.getString("school_name")))
            .optional();
    }

    record LoginAccount(long userId, long teacherId, String passwordHash, String role,
                        boolean enabled, String displayName) {}

    record TeacherProfile(long teacherId, String displayName, String schoolName) {}
}
