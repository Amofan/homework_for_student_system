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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

@Service
public class AssignmentService {

    /**
     * 学生可见作业的查询主体。
     *
     * <p>可见性以 {@code published_at is not null} 为准，而不是只看状态：已发布的作业会继续
     * 走向 SUBMITTING / GRADING / REVIEWING / COMPLETED，用状态白名单迟早会漏掉一个，
     * 让学生“交完就看不见作业了”。而 {@code published_at} 一旦写上就不会再被抹掉。
     *
     * <p>班级由学生自己的档案反查，所以学生不可能看到别的班的作业，也不需要前端传班级参数
     * 让服务端去信任。
     */
    private static final String STUDENT_SELECT = """
        select a.id, a.title, a.status, a.due_at,
               sc.name as class_name,
               t.display_name as teacher_name,
               (select count(*) from assignment_question aq where aq.assignment_id = a.id) as question_count,
               (select sub.status from submission sub
                  where sub.assignment_id = a.id and sub.student_id = :studentId) as submission_status
        from assignment a
        join school_class sc on sc.id = a.class_id
        join teacher t on t.id = a.teacher_id
        where a.deleted_at is null and sc.deleted_at is null and a.published_at is not null
          and a.class_id = (select s.class_id from student s where s.id = :studentId and s.deleted_at is null)
        """;

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
                select id, class_id, title, status, published_at, due_at, version from assignment
                where teacher_id = :teacherId and deleted_at is null order by id desc
                """)
            .param("teacherId", teacherId)
            .query((rs, rowNum) -> view(rs))
            .list();
    }

    public AssignmentView requireOwned(long teacherId, long assignmentId) {
        return jdbc.sql("""
                select id, class_id, title, status, published_at, due_at, version from assignment
                where id = :id and teacher_id = :teacherId and deleted_at is null
                """)
            .param("id", assignmentId)
            .param("teacherId", teacherId)
            .query((rs, rowNum) -> view(rs))
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

    /**
     * 发布作业：{@code DRAFT}/{@code OCR_REVIEW} → {@code PUBLISHED}。
     *
     * <p>三步检查的顺序是有意的——先判状态是否允许，再判内容是否够发布，最后才动数据。
     * 版本冲突放在最后：它是并发问题而不是业务规则问题，前两步都通过才值得检查。
     */
    @Transactional
    public AssignmentView publish(long teacherId, long assignmentId, PublishAssignmentCommand command) {
        AssignmentView current = requireOwned(teacherId, assignmentId);
        if (!current.status().canTransitionTo(AssignmentStatus.PUBLISHED)) {
            throw new DomainException("ASSIGNMENT_STATUS_TRANSITION_INVALID",
                "作业当前为" + current.status() + "，不能再次发布", HttpStatus.CONFLICT);
        }
        if (current.questionIds().isEmpty()) {
            throw new DomainException("ASSIGNMENT_HAS_NO_QUESTION",
                "作业至少需要一道题目才能发布", HttpStatus.CONFLICT);
        }
        if (command.dueAt() != null && command.dueAt().isBefore(Instant.now())) {
            throw new DomainException("ASSIGNMENT_DUE_AT_INVALID", "截止时间不能早于当前时间");
        }

        // 截止时间有两种写法而不是给同一个参数绑 null：给具名参数绑 null 时，
        // 类型推断要依赖驱动对 setNull(Types.NULL) 的处理，H2 与 MySQL 行为并不一致。
        int updated = command.dueAt() == null
            ? jdbc.sql("""
                    update assignment
                    set status = :status, published_at = current_timestamp(3), due_at = null,
                        version = version + 1, updated_at = current_timestamp(3)
                    where id = :id and teacher_id = :teacherId and deleted_at is null
                      and version = :version
                    """)
                .param("status", AssignmentStatus.PUBLISHED.name())
                .param("id", assignmentId)
                .param("teacherId", teacherId)
                .param("version", command.version())
                .update()
            : jdbc.sql("""
                    update assignment
                    set status = :status, published_at = current_timestamp(3), due_at = :dueAt,
                        version = version + 1, updated_at = current_timestamp(3)
                    where id = :id and teacher_id = :teacherId and deleted_at is null
                      and version = :version
                    """)
                .param("status", AssignmentStatus.PUBLISHED.name())
                .param("dueAt", Timestamp.from(command.dueAt()))
                .param("id", assignmentId)
                .param("teacherId", teacherId)
                .param("version", command.version())
                .update();

        if (updated == 0) {
            throw new DomainException("ASSIGNMENT_VERSION_CONFLICT",
                "作业已被其他操作修改，请刷新后重试", HttpStatus.CONFLICT);
        }
        return requireOwned(teacherId, assignmentId);
    }

    public List<StudentAssignmentView> listForStudent(long studentId) {
        return jdbc.sql(STUDENT_SELECT + " order by a.published_at desc, a.id desc")
            .param("studentId", studentId)
            .query(AssignmentService::toStudentView)
            .list();
    }

    public StudentAssignmentView findForStudent(long studentId, long assignmentId) {
        return jdbc.sql(STUDENT_SELECT + " and a.id = :assignmentId")
            .param("studentId", studentId)
            .param("assignmentId", assignmentId)
            .query(AssignmentService::toStudentView)
            .optional()
            // 草稿、别的班的作业、不存在的作业都返回同一个 404：区分开来等于告诉学生
            // “这份作业存在，只是不属于你”。
            .orElseThrow(() -> new DomainException(
                "ASSIGNMENT_NOT_FOUND", "作业不存在或尚未发布", HttpStatus.NOT_FOUND));
    }

    private AssignmentView view(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        List<Long> questionIds = jdbc.sql("""
                select question_id from assignment_question where assignment_id = :id order by question_order
                """)
            .param("id", id).query(Long.class).list();
        return new AssignmentView(id, rs.getLong("class_id"), rs.getString("title"),
            AssignmentStatus.parse(rs.getString("status")),
            instant(rs.getTimestamp("published_at")), instant(rs.getTimestamp("due_at")),
            rs.getInt("version"), questionIds);
    }

    private static StudentAssignmentView toStudentView(ResultSet rs, int rowNum) throws SQLException {
        String submissionStatus = rs.getString("submission_status");
        return new StudentAssignmentView(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("class_name"),
            rs.getString("teacher_name"),
            AssignmentStatus.parse(rs.getString("status")),
            instant(rs.getTimestamp("due_at")),
            rs.getInt("question_count"),
            submissionStatus == null ? StudentAssignmentView.NOT_SUBMITTED : submissionStatus);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
