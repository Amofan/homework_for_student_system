package com.homework.analysis.classroom;

import com.homework.analysis.shared.jdbc.GeneratedKeys;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class StudentRepository {
    private final JdbcClient jdbc;

    StudentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<StudentView> findAllOwned(long teacherId, long classId) {
        return jdbc.sql("""
                select s.id, s.class_id, s.student_no, s.name, u.username, u.account_status
                from student s
                join school_class c on c.id = s.class_id
                left join app_user u on u.id = s.user_id
                where c.teacher_id = :teacherId and c.id = :classId
                  and c.deleted_at is null and s.deleted_at is null
                order by s.student_no, s.id
                """)
            .param("teacherId", teacherId)
            .param("classId", classId)
            .query(StudentRepository::map)
            .list();
    }

    Optional<StudentView> findOwned(long teacherId, long studentId) {
        return jdbc.sql("""
                select s.id, s.class_id, s.student_no, s.name, u.username, u.account_status
                from student s
                join school_class c on c.id = s.class_id
                left join app_user u on u.id = s.user_id
                where c.teacher_id = :teacherId and s.id = :studentId
                  and c.deleted_at is null and s.deleted_at is null
                """)
            .param("teacherId", teacherId)
            .param("studentId", studentId)
            .query(StudentRepository::map)
            .optional();
    }

    StudentView insert(long teacherId, long classId, StudentRequest request) {
        long studentId = GeneratedKeys.insert(jdbc, """
                insert into student(class_id, student_no, name)
                select c.id, :studentNo, :name from school_class c
                where c.id = :classId and c.teacher_id = :teacherId and c.deleted_at is null
                """, statement -> statement
            .param("studentNo", request.studentNo().trim())
            .param("name", request.name().trim())
            .param("classId", classId)
            .param("teacherId", teacherId));
        return findOwned(teacherId, studentId).orElseThrow(
            () -> new IllegalStateException("新建学生后未能读回该学生"));
    }

    int updateOwned(long teacherId, long studentId, StudentRequest request) {
        return jdbc.sql("""
                update student s set student_no = :studentNo, name = :name,
                    updated_at = current_timestamp(3)
                where s.id = :studentId and s.deleted_at is null
                  and exists (select 1 from school_class c where c.id = s.class_id
                    and c.teacher_id = :teacherId and c.deleted_at is null)
                """)
            .param("studentNo", request.studentNo().trim())
            .param("name", request.name().trim())
            .param("studentId", studentId)
            .param("teacherId", teacherId)
            .update();
    }

    int softDeleteOwned(long teacherId, long studentId) {
        return jdbc.sql("""
                update student s set deleted_at = current_timestamp(3), updated_at = current_timestamp(3)
                where s.id = :studentId and s.deleted_at is null
                  and exists (select 1 from school_class c where c.id = s.class_id
                    and c.teacher_id = :teacherId and c.deleted_at is null)
                """)
            .param("studentId", studentId)
            .param("teacherId", teacherId)
            .update();
    }

    /**
     * 停用学生已开通的账号。
     *
     * <p>删除学生不物理删除账号，也不删除历史提交：账号转为 {@code DISABLED} 后无法登录，
     * 但既有作业、评分与审计记录仍能追到人。子查询读的是 {@code student}/{@code school_class}，
     * 不是被更新的 {@code app_user}，因此不触发 MySQL 的“不能更新正在查询的表”限制。
     */
    int disableAccountOwned(long teacherId, long studentId) {
        return jdbc.sql("""
                update app_user set account_status = 'DISABLED', enabled = false,
                    updated_at = current_timestamp(3)
                where id = (select s.user_id from student s
                    join school_class c on c.id = s.class_id
                    where s.id = :studentId and c.teacher_id = :teacherId and s.user_id is not null)
                """)
            .param("studentId", studentId)
            .param("teacherId", teacherId)
            .update();
    }

    private static StudentView map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new StudentView(rs.getLong("id"), rs.getLong("class_id"),
            rs.getString("student_no"), rs.getString("name"),
            rs.getString("username"), rs.getString("account_status"));
    }
}
