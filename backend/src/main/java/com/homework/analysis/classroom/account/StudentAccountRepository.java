package com.homework.analysis.classroom.account;

import com.homework.analysis.shared.jdbc.GeneratedKeys;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class StudentAccountRepository {

    private final JdbcClient jdbc;

    StudentAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 班级下全部学生的账号视图。
     *
     * <p>一次查完整个班级再在内存里按请求的 ID 过滤，而不是按 ID 逐个查库：
     * 开通是按班批量进行的，逐条查询会把一次操作放大成 N 次往返；
     * 而“请求的 ID 是否属于本班”这个判断放在内存里做，等价于 SQL 过滤，不放松越权检查。
     */
    List<StudentAccountRow> findClassStudents(long teacherId, long classId) {
        return jdbc.sql("""
                select s.id as student_id, s.student_no, s.name, s.user_id,
                       u.username, u.account_status
                from student s
                join school_class c on c.id = s.class_id
                left join app_user u on u.id = s.user_id
                where c.teacher_id = :teacherId and c.id = :classId
                  and c.deleted_at is null and s.deleted_at is null
                order by s.student_no, s.id
                """)
            .param("teacherId", teacherId)
            .param("classId", classId)
            .query(StudentAccountRepository::map)
            .list();
    }

    Optional<StudentAccountRow> findOwnedStudent(long teacherId, long studentId) {
        return jdbc.sql("""
                select s.id as student_id, s.student_no, s.name, s.user_id,
                       u.username, u.account_status
                from student s
                join school_class c on c.id = s.class_id
                left join app_user u on u.id = s.user_id
                where c.teacher_id = :teacherId and s.id = :studentId
                  and c.deleted_at is null and s.deleted_at is null
                """)
            .param("teacherId", teacherId)
            .param("studentId", studentId)
            .query(StudentAccountRepository::map)
            .optional();
    }

    /**
     * 条件插入学生账号。
     *
     * <p>{@code where not exists} 把“登录名是否已被占用”交给数据库在同一语句里判断，
     * 不依赖捕获唯一键冲突异常：捕获异常会让事务处于“已失败但未回滚”的中间态，
     * 而重试又必须留在同一个事务里才不至于留下半开通的数据。
     *
     * @return 新账号 ID；{@code null} 表示登录名已被占用，调用方应当换一个候选值重试
     */
    Long insertStudentAccount(String username, String passwordHash, String accountStatus) {
        return GeneratedKeys.insertOrNull(jdbc, """
            insert into app_user(username, password_hash, role, enabled, account_status)
            select :username, :passwordHash, 'STUDENT', true, :accountStatus
            where not exists (select 1 from app_user where username = :username)
            """, statement -> statement
            .param("username", username)
            .param("passwordHash", passwordHash)
            .param("accountStatus", accountStatus));
    }

    /** 绑定学生与账号；只在尚未绑定时生效，避免并发下把学生改挂到另一个账号。 */
    int bindStudentUser(long studentId, long userId) {
        return jdbc.sql("""
                update student set user_id = :userId, updated_at = current_timestamp(3)
                where id = :studentId and user_id is null and deleted_at is null
                """)
            .param("studentId", studentId)
            .param("userId", userId)
            .update();
    }

    int replacePassword(long userId, String passwordHash, String accountStatus) {
        return jdbc.sql("""
                update app_user set password_hash = :passwordHash, account_status = :accountStatus,
                    updated_at = current_timestamp(3)
                where id = :userId
                """)
            .param("userId", userId)
            .param("passwordHash", passwordHash)
            .param("accountStatus", accountStatus)
            .update();
    }

    private static StudentAccountRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new StudentAccountRow(
            rs.getLong("student_id"),
            rs.getString("student_no"),
            rs.getString("name"),
            rs.getObject("user_id", Long.class),
            rs.getString("username"),
            rs.getString("account_status"));
    }

    /**
     * 学生与其账号的对应关系。
     *
     * <p>{@code userId}/{@code username}/{@code accountStatus} 在未开通时同时为 {@code null}，
     * 因此“未开通”只有一个表示法，不需要再引入一个布尔字段去和它保持同步。
     */
    public record StudentAccountRow(long studentId, String studentNo, String name,
                                    Long userId, String username, String accountStatus) {

        public boolean provisioned() {
            return userId != null;
        }
    }
}
