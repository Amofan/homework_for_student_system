package com.homework.analysis.grading.review;

import com.homework.analysis.assignment.AssignmentService;
import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.shared.jdbc.GeneratedKeys;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TeacherReviewService {
    private final JdbcClient jdbc;
    private final AssignmentService assignments;

    TeacherReviewService(JdbcClient jdbc, AssignmentService assignments) {
        this.jdbc = jdbc;
        this.assignments = assignments;
    }

    public List<ReviewQueueItem> queue(long teacherId, long assignmentId) {
        assignments.requireOwned(teacherId, assignmentId);
        return jdbc.sql("""
                select gr.id as result_id, sa.id as answer_id, st.student_no, st.name as student_name,
                       q.question_code, q.content as question_content, sa.answer_content,
                       gr.source, gr.suggested_score, q.total_score, gr.error_type,
                       gr.teacher_explanation, gr.student_feedback, gr.score_details
                from grading_result gr
                join student_answer sa on sa.id = gr.answer_id
                join submission sub on sub.id = sa.submission_id
                join student st on st.id = sub.student_id
                join question q on q.id = sa.question_id
                where sub.assignment_id = :assignmentId and gr.status = 'PENDING_REVIEW'
                order by st.student_no, q.question_code
                """)
            .param("assignmentId", assignmentId)
            .query((rs, rowNum) -> new ReviewQueueItem(
                rs.getLong("result_id"), rs.getLong("answer_id"), rs.getString("student_no"),
                rs.getString("student_name"), rs.getString("question_code"), rs.getString("question_content"),
                rs.getString("answer_content"), rs.getString("source"), rs.getInt("suggested_score"),
                rs.getInt("total_score"), rs.getString("error_type"), rs.getString("teacher_explanation"),
                rs.getString("student_feedback"), rs.getString("score_details")))
            .list();
    }

    @Transactional
    public ReviewView review(long teacherId, long resultId, ReviewCommand command) {
        PendingResult result = jdbc.sql("""
                select gr.id, gr.suggested_score, gr.error_type, gr.student_feedback, q.total_score
                from grading_result gr
                join student_answer sa on sa.id = gr.answer_id
                join submission sub on sub.id = sa.submission_id
                join assignment a on a.id = sub.assignment_id
                join question q on q.id = sa.question_id
                where gr.id = :resultId and a.teacher_id = :teacherId and gr.status = 'PENDING_REVIEW'
                for update
                """)
            .param("resultId", resultId)
            .param("teacherId", teacherId)
            .query((rs, rowNum) -> new PendingResult(rs.getLong("id"), rs.getInt("suggested_score"),
                rs.getString("error_type"), rs.getString("student_feedback"), rs.getInt("total_score")))
            .optional()
            .orElseThrow(() -> new DomainException("GRADING_RESULT_NOT_FOUND", "评分结果不存在或已复核", HttpStatus.NOT_FOUND));

        int finalScore;
        String errorType;
        String feedback = blankToFallback(command.feedback(), result.feedback());
        if (command.decision() == ReviewDecision.ACCEPT) {
            finalScore = result.suggestedScore();
            errorType = result.errorType();
        } else {
            requireReason(command.reason());
            if (command.finalScore() == null) {
                throw new DomainException("REVIEW_SCORE_REQUIRED", "修改或驳回时必须填写最终得分");
            }
            finalScore = command.finalScore();
            errorType = blankToFallback(command.errorType(), result.errorType());
        }
        if (finalScore < 0 || finalScore > result.totalScore()) {
            throw new DomainException("REVIEW_SCORE_OUT_OF_RANGE", "最终得分超出题目分值范围");
        }
        long reviewId = GeneratedKeys.insert(jdbc, """
                insert into teacher_review(result_id, teacher_id, decision, final_score,
                                           final_error_type, feedback, reason)
                values (:resultId, :teacherId, :decision, :score, :errorType, :feedback, :reason)
                """, statement -> statement
            .param("resultId", resultId).param("teacherId", teacherId)
            .param("decision", command.decision().name()).param("score", finalScore)
            .param("errorType", errorType).param("feedback", feedback).param("reason", command.reason()));
        jdbc.sql("""
                update grading_result set confirmed_score = :score, error_type = :errorType,
                    student_feedback = :feedback, status = 'CONFIRMED', version = version + 1,
                    updated_at = current_timestamp(3) where id = :resultId and status = 'PENDING_REVIEW'
                """)
            .param("score", finalScore).param("errorType", errorType).param("feedback", feedback)
            .param("resultId", resultId).update();
        jdbc.sql("""
                insert into grading_audit(result_id, teacher_id, action, detail_json)
                values (:resultId, :teacherId, :action, :detail)
                """)
            .param("resultId", resultId).param("teacherId", teacherId)
            .param("action", "REVIEW_" + command.decision().name())
            .param("detail", "{\"finalScore\":" + finalScore + "}")
            .update();
        return new ReviewView(reviewId, resultId, command.decision(), finalScore, errorType, feedback, command.reason());
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DomainException("REVIEW_REASON_REQUIRED", "修改或驳回时必须填写原因");
        }
    }

    private static String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record PendingResult(long id, int suggestedScore, String errorType, String feedback, int totalScore) {}
}
