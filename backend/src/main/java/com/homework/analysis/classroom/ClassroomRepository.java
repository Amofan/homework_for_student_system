package com.homework.analysis.classroom;

import com.homework.analysis.shared.jdbc.GeneratedKeys;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ClassroomRepository {
    private final JdbcClient jdbc;

    ClassroomRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<ClassroomView> findAllOwned(long teacherId) {
        return jdbc.sql("""
                select c.id, c.class_code, c.name, c.grade, c.semester, count(s.id) as student_count
                from school_class c
                left join student s on s.class_id = c.id and s.deleted_at is null
                where c.teacher_id = :teacherId and c.deleted_at is null
                group by c.id, c.class_code, c.name, c.grade, c.semester
                order by c.id
                """)
            .param("teacherId", teacherId)
            .query(ClassroomRepository::map)
            .list();
    }

    Optional<ClassroomView> findOwned(long teacherId, long classId) {
        return jdbc.sql("""
                select c.id, c.class_code, c.name, c.grade, c.semester, count(s.id) as student_count
                from school_class c
                left join student s on s.class_id = c.id and s.deleted_at is null
                where c.id = :classId and c.teacher_id = :teacherId and c.deleted_at is null
                group by c.id, c.class_code, c.name, c.grade, c.semester
                """)
            .param("classId", classId)
            .param("teacherId", teacherId)
            .query(ClassroomRepository::map)
            .optional();
    }

    ClassroomView insert(long teacherId, ClassroomRequest request) {
        long classId = GeneratedKeys.insert(jdbc, """
                insert into school_class(teacher_id, class_code, name, grade, semester)
                values (:teacherId, :classCode, :name, :grade, :semester)
                """, statement -> statement
            .param("teacherId", teacherId).param("classCode", request.classCode().trim())
            .param("name", request.name().trim()).param("grade", request.grade())
            .param("semester", request.semester()));
        return findOwned(teacherId, classId).orElseThrow(
            () -> new IllegalStateException("新建班级后未能读回该班级"));
    }

    int updateOwned(long teacherId, long classId, ClassroomRequest request) {
        return jdbc.sql("""
                update school_class set class_code = :classCode, name = :name, grade = :grade,
                    semester = :semester, updated_at = current_timestamp(3)
                where id = :classId and teacher_id = :teacherId and deleted_at is null
                """)
            .param("classCode", request.classCode().trim()).param("name", request.name().trim())
            .param("grade", request.grade()).param("semester", request.semester())
            .param("classId", classId).param("teacherId", teacherId).update();
    }

    int softDeleteOwned(long teacherId, long classId) {
        return jdbc.sql("""
                update school_class set deleted_at = current_timestamp(3), updated_at = current_timestamp(3)
                where id = :classId and teacher_id = :teacherId and deleted_at is null
                """)
            .param("classId", classId).param("teacherId", teacherId).update();
    }

    boolean hasActiveAssignmentsOwned(long teacherId, long classId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                select case when count(*) > 0 then true else false end
                from assignment a
                join school_class c on c.id = a.class_id
                where a.class_id = :classId and a.teacher_id = :teacherId
                  and a.deleted_at is null and c.deleted_at is null
                """)
            .param("classId", classId)
            .param("teacherId", teacherId)
            .query(Boolean.class)
            .single());
    }

    private static ClassroomView map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        int gradeValue = rs.getInt("grade");
        Integer grade = rs.wasNull() ? null : gradeValue;
        return new ClassroomView(
            rs.getLong("id"), rs.getString("class_code"), rs.getString("name"), grade,
            rs.getString("semester"), rs.getLong("student_count"));
    }
}
