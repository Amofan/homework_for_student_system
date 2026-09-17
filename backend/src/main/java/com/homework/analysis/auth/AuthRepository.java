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

    /**
     * 读取登录账号。
     *
     * <p>用两次 {@code left join} 同时覆盖教师和学生：账号只可能命中一条分支，
     * 未命中分支的列为 NULL。用 {@code inner join} 会导致学生账号查不到，
     * 用 {@code union} 则要在 SQL 里拼角色判断，都不如让调用方按 {@code role} 取值清晰。
     */
    Optional<LoginAccount> findLoginAccount(String username) {
        return jdbc.sql("""
                select u.id as user_id, u.password_hash, u.role, u.enabled, u.account_status,
                       t.id as teacher_id, t.display_name as teacher_name,
                       s.id as student_id, s.name as student_name
                from app_user u
                left join teacher t on t.user_id = u.id
                left join student s on s.user_id = u.id
                where u.username = :username
                """)
            .param("username", username)
            .query((rs, rowNum) -> new LoginAccount(
                rs.getLong("user_id"),
                rs.getObject("teacher_id", Long.class),
                rs.getObject("student_id", Long.class),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getBoolean("enabled"),
                AccountStatus.parse(rs.getString("account_status")),
                AuthPrincipal.ROLE_TEACHER.equals(rs.getString("role"))
                    ? rs.getString("teacher_name")
                    : rs.getString("student_name")))
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

    Optional<StudentProfile> findStudent(long studentId) {
        return jdbc.sql("""
                select s.id as student_id, s.name, u.account_status
                from student s
                join app_user u on u.id = s.user_id
                where s.id = :studentId and s.deleted_at is null and u.enabled = true
                """)
            .param("studentId", studentId)
            .query((rs, rowNum) -> new StudentProfile(
                rs.getLong("student_id"),
                rs.getString("name"),
                AccountStatus.parse(rs.getString("account_status"))))
            .optional();
    }

    /** 读账号凭据，供改密时校验当前密码。只按主键取一行，不返回姓名等资料。 */
    Optional<AccountCredential> findCredential(long userId) {
        return jdbc.sql("""
                select password_hash, role, enabled, account_status
                from app_user where id = :userId
                """)
            .param("userId", userId)
            .query((rs, rowNum) -> new AccountCredential(
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getBoolean("enabled"),
                AccountStatus.parse(rs.getString("account_status"))))
            .optional();
    }

    int replacePassword(long userId, String passwordHash, AccountStatus accountStatus) {
        return jdbc.sql("""
                update app_user set password_hash = :passwordHash, account_status = :accountStatus,
                    updated_at = current_timestamp(3)
                where id = :userId
                """)
            .param("userId", userId)
            .param("passwordHash", passwordHash)
            .param("accountStatus", accountStatus.name())
            .update();
    }

    /**
     * 登录账号视图。
     *
     * <p>{@code teacherId} 与 {@code studentId} 恰好一个非空。若角色与已开通的主体不匹配
     * （例如 {@code role=TEACHER} 却只有学生档案），{@link #roleMatchesSubject()} 为 false，
     * 登录会被当作凭据错误处理，避免出现“主体缺失但令牌照发”的账号。
     */
    record LoginAccount(long userId, Long teacherId, Long studentId, String passwordHash,
                        String role, boolean enabled, AccountStatus accountStatus,
                        String displayName) {

        boolean roleMatchesSubject() {
            if (AuthPrincipal.ROLE_TEACHER.equals(role)) {
                return teacherId != null && studentId == null;
            }
            if (AuthPrincipal.ROLE_STUDENT.equals(role)) {
                return studentId != null && teacherId == null;
            }
            return false;
        }

        boolean allowsLogin() {
            return enabled && accountStatus.allowsLogin() && roleMatchesSubject();
        }
    }

    record TeacherProfile(long teacherId, String displayName, String schoolName) {}

    record StudentProfile(long studentId, String displayName, AccountStatus accountStatus) {}

    record AccountCredential(String passwordHash, String role, boolean enabled,
                             AccountStatus accountStatus) {

        boolean allowsPasswordChange() {
            return enabled && accountStatus.allowsLogin();
        }
    }
}
