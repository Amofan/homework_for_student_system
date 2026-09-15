package com.homework.analysis.assignment;

import com.homework.analysis.classroom.ClassroomService;
import com.homework.analysis.question.QuestionService;
import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.shared.jdbc.GeneratedKeys;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

@Service
public class AssignmentService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final ClassroomService classrooms;
    private final QuestionService questions;

    AssignmentService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, ClassroomService classrooms,
                      QuestionService questions) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.classrooms = classrooms;
        this.questions = questions;
    }

    public List<AssignmentView> list(long teacherId) {
        return jdbc.sql("""
                select id, class_id, title, status from assignment
                where teacher_id = :teacherId and deleted_at is null order by id desc
                """)
            .param("teacherId", teacherId)
            .query((rs, rowNum) -> view(rs.getLong("id"), rs.getLong("class_id"),
                rs.getString("title"), rs.getString("status")))
            .list();
    }

    public AssignmentView requireOwned(long teacherId, long assignmentId) {
        return jdbc.sql("""
                select id, class_id, title, status from assignment
                where id = :id and teacher_id = :teacherId and deleted_at is null
                """)
            .param("id", assignmentId)
            .param("teacherId", teacherId)
            .query((rs, rowNum) -> view(rs.getLong("id"), rs.getLong("class_id"),
                rs.getString("title"), rs.getString("status")))
            .optional()
            .orElseThrow(() -> new DomainException("ASSIGNMENT_NOT_FOUND", "作业不存在", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public AssignmentView create(long teacherId, AssignmentCommand command) {
        classrooms.get(teacherId, command.classId());
        if (new HashSet<>(command.questionIds()).size() != command.questionIds().size()) {
            throw new DomainException("QUESTION_DUPLICATE", "作业中的题目不能重复");
        }
        command.questionIds().forEach(id -> questions.requireOwned(teacherId, id));
        // 主键由驱动直接回传：再查一次 max(id) 是普通一致性读，同一教师并发建作业时
        // 会读到对方刚提交的行，两次请求拿到同一个 id，题目明细挂到别人的作业上。
        long assignmentId = GeneratedKeys.insert(jdbcTemplate, """
                insert into assignment(teacher_id, class_id, title, status)
                values (?, ?, ?, 'DRAFT')
                """, teacherId, command.classId(), command.title().trim());
        for (int index = 0; index < command.questionIds().size(); index++) {
            jdbc.sql("""
                    insert into assignment_question(assignment_id, question_id, question_order)
                    values (:assignmentId, :questionId, :questionOrder)
                    """)
                .param("assignmentId", assignmentId)
                .param("questionId", command.questionIds().get(index))
                .param("questionOrder", index + 1)
                .update();
        }
        return requireOwned(teacherId, assignmentId);
    }

    private AssignmentView view(long id, long classId, String title, String status) {
        List<Long> questionIds = jdbc.sql("""
                select question_id from assignment_question where assignment_id = :id order by question_order
                """)
            .param("id", id).query(Long.class).list();
        return new AssignmentView(id, classId, title, status, questionIds);
    }
}
