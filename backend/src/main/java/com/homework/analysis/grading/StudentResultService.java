package com.homework.analysis.grading;

import com.homework.analysis.assignment.AssignmentService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 学生端最终成绩的取数。
 *
 * <p>只认"教师复核过"的评分：{@code grading_result.status = 'CONFIRMED'} 且未被作废。
 * 待复核的模型建议、排队中的 AI 任务一律不出现在学生端——那还只是一份建议，
 * 学生看到它就会当成成绩，而老师随后改成别的分数时，他记住的是先前那个。
 *
 * <p>取数一律按 {@code submission_id + student_id} 双条件收窄，不靠"学生手上只有自己的数据"：
 * 越权读别人的成绩是这一层唯一要防的事，多一个条件不花什么代价。
 */
@Service
public class StudentResultService {

    private final JdbcClient jdbc;
    private final AssignmentService assignments;

    StudentResultService(JdbcClient jdbc, AssignmentService assignments) {
        this.jdbc = jdbc;
        this.assignments = assignments;
    }

    public StudentResultView forAssignment(long studentId, long assignmentId) {
        // 与学生端作业详情共用同一道可见性闸门：草稿、别的班的作业、不存在的作业
        // 都得到同一个 404，不能借成绩接口探出"这份作业存在，只是不属于我"。
        assignments.findForStudent(studentId, assignmentId);
        Optional<SubmissionRow> submission = submission(studentId, assignmentId);
        if (submission.isEmpty()) {
            return empty(assignmentId);
        }
        List<StudentResultView.QuestionResult> items = confirmedItems(studentId, assignmentId);
        int questionCount = answerCount(studentId, assignmentId);
        int gradedCount = items.size();

        StudentResultView.State state;
        if (questionCount == 0 || gradedCount == 0) {
            state = StudentResultView.State.PENDING;
        } else if (gradedCount >= questionCount) {
            state = StudentResultView.State.GRADED;
        } else {
            state = StudentResultView.State.PARTIAL;
        }
        // 一道都没批完时两个分数都是空，而不是 0：0 分是一个结论，会让学生以为考试结果已经出来。
        Integer confirmedScore = gradedCount == 0 ? null
            : items.stream().mapToInt(StudentResultView.QuestionResult::confirmedScore).sum();
        Integer gradedScore = gradedCount == 0 ? null
            : items.stream().mapToInt(StudentResultView.QuestionResult::totalScore).sum();
        return new StudentResultView(assignmentId, state, submission.get().versionNo(), confirmedScore,
            gradedScore, gradedCount, questionCount, completedAt(items), items);
    }

    /**
     * 这次提交下已经批完的题。
     *
     * <p>排序用 {@code assignment_question.question_order}，不是题号：题号是教师起的名字
     * （"Q1""Q10"），按字典序排会把 Q10 排到 Q2 前面，而学生看到的顺序应该与卷面一致。
     *
     * <p>内连接 {@code assignment_question} 还有一个作用：教师把某道题从作业里移走之后，
     * 它的作答不该继续出现在成绩单里，也不该算进"几道题没批完"。
     */
    private List<StudentResultView.QuestionResult> confirmedItems(long studentId, long assignmentId) {
        return jdbc.sql("""
                select q.question_code, q.content as question_content, q.total_score,
                       gr.confirmed_score, gr.student_feedback, tr.created_at as confirmed_at
                from student_answer sa
                join submission sub on sub.id = sa.submission_id
                join assignment_question aq on aq.assignment_id = sub.assignment_id
                                           and aq.question_id = sa.question_id
                join question q on q.id = sa.question_id
                join grading_result gr on gr.answer_id = sa.id
                                      and gr.status = 'CONFIRMED' and gr.invalidated_at is null
                left join teacher_review tr on tr.result_id = gr.id
                where sub.assignment_id = :assignmentId and sub.student_id = :studentId
                order by aq.question_order
                """)
            .param("assignmentId", assignmentId)
            .param("studentId", studentId)
            .query((rs, rowNum) -> new StudentResultView.QuestionResult(
                rs.getString("question_code"), rs.getString("question_content"),
                rs.getInt("confirmed_score"), rs.getInt("total_score"),
                rs.getString("student_feedback"), instant(rs, "confirmed_at")))
            .list();
    }

    /** 这次提交里有作答的题数。分母是它，不是作业的题数：没作答的题不该算成"没批完"。 */
    private int answerCount(long studentId, long assignmentId) {
        return jdbc.sql("""
                select count(*) from student_answer sa
                join submission sub on sub.id = sa.submission_id
                join assignment_question aq on aq.assignment_id = sub.assignment_id
                                           and aq.question_id = sa.question_id
                where sub.assignment_id = :assignmentId and sub.student_id = :studentId
                """)
            .param("assignmentId", assignmentId)
            .param("studentId", studentId)
            .query(Integer.class)
            .single();
    }

    private Optional<SubmissionRow> submission(long studentId, long assignmentId) {
        return jdbc.sql("""
                select sub.id, sv.version_no
                from submission sub
                left join submission_version sv on sv.id = sub.submission_version_id
                where sub.assignment_id = :assignmentId and sub.student_id = :studentId
                """)
            .param("assignmentId", assignmentId)
            .param("studentId", studentId)
            .query((rs, rowNum) -> new SubmissionRow(rs.getLong("id"),
                (Integer) rs.getObject("version_no")))
            .optional();
    }

    /**
     * 老师批完最后一道题的时刻。
     *
     * <p>取自 {@code teacher_review.created_at} 而不是 {@code grading_result.updated_at}：
     * 后者会被任何一次写回刷新（重新批改、作废标记），把它当成"什么时候批完的"会随时间漂移。
     */
    private static Instant completedAt(List<StudentResultView.QuestionResult> items) {
        return items.stream().map(StudentResultView.QuestionResult::confirmedAt)
            .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    private static StudentResultView empty(long assignmentId) {
        return new StudentResultView(assignmentId, StudentResultView.State.NOT_SUBMITTED, null,
            null, null, 0, 0, null, List.of());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** {@code version_no} 可空：整卷导入那条旧链路直接写 {@code student_answer}，没有版本行。 */
    private record SubmissionRow(long id, Integer versionNo) {
    }
}
