package com.homework.analysis.grading.review;

import com.homework.analysis.assignment.AssignmentService;
import com.homework.analysis.grading.ErrorType;
import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.shared.jdbc.GeneratedKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TeacherReviewService {
    private static final Logger log = LoggerFactory.getLogger(TeacherReviewService.class);
    /**
     * 教师复核耗时的上限（秒）。它是前端计时，不是可信输入：
     * 标签页被挂起、时钟被改动都会给出荒唐的值。超限只影响论文统计的一行，
     * 不该拦住教师批改，因此记空值并留一条警告，而不是抛出错误。
     */
    private static final double MAX_TEACHER_SECONDS = 3600;

    private final JdbcClient jdbc;
    private final AssignmentService assignments;

    TeacherReviewService(JdbcClient jdbc, AssignmentService assignments) {
        this.jdbc = jdbc;
        this.assignments = assignments;
    }

    public List<ReviewQueueItem> queue(long teacherId, long assignmentId) {
        assignments.requireOwned(teacherId, assignmentId);
        List<ReviewQueueItem> items = jdbc.sql("""
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
                rs.getString("student_feedback"), rs.getString("score_details"), List.of()))
            .list();
        if (items.isEmpty()) {
            return items;
        }
        Map<Long, List<ReviewQueueItem.AnswerAssetView>> assets = answerAssets(
            items.stream().map(ReviewQueueItem::answerId).toList());
        return items.stream()
            .map(item -> new ReviewQueueItem(item.resultId(), item.answerId(), item.studentNo(),
                item.studentName(), item.questionCode(), item.questionContent(), item.answerContent(),
                item.source(), item.suggestedScore(), item.totalScore(), item.errorType(),
                item.teacherExplanation(), item.studentFeedback(), item.scoreDetails(),
                assets.getOrDefault(item.answerId(), List.of())))
            .toList();
    }

    /**
     * 这一批答案的答案图，按答案 id 归拢。
     *
     * <p>一次查完整批，而不是逐条查：复核页一屏就有几十条待复核答案，
     * 逐条查等于把一次列表请求变成几十次。与 {@code AnswerExtractionService} 里
     * "区域按页一次取全"是同一个取舍。
     *
     * <p>{@code role} 目前只有 {@code SOURCE_CROP}（学生手写原图的裁剪）一种，
     * 但仍然如实返回：将来会有 {@code DERIVED}（拼接、增强过的图），
     * 而"这张是原图还是处理过的"决定教师该不该拿它当证据。
     *
     * <p>排序在 SQL 里定死（按答案、再按 sort_order）：跨页作答的两块必须按学生写的顺序
     * 并排显示，在 Java 里再排一次就多了一处可能忘记的地方。
     */
    private Map<Long, List<ReviewQueueItem.AnswerAssetView>> answerAssets(List<Long> answerIds) {
        Map<Long, List<ReviewQueueItem.AnswerAssetView>> byAnswer = new LinkedHashMap<>();
        jdbc.sql("""
                select saa.answer_id, saa.file_id, saa.role, saa.sort_order,
                       sp.page_no, dr.x, dr.y, dr.width, dr.height
                from student_answer_asset saa
                left join submission_page sp on sp.id = saa.submission_page_id
                left join document_region dr on dr.id = saa.document_region_id
                where saa.answer_id in (:answerIds)
                order by saa.answer_id, saa.sort_order
                """)
            .param("answerIds", answerIds)
            .query((rs, rowNum) -> Map.entry(rs.getLong("answer_id"),
                new ReviewQueueItem.AnswerAssetView(rs.getLong("file_id"), rs.getString("role"),
                    (Integer) rs.getObject("page_no"), nullableDouble(rs, "x"), nullableDouble(rs, "y"),
                    nullableDouble(rs, "width"), nullableDouble(rs, "height"), rs.getInt("sort_order"))))
            .list()
            .forEach(entry -> byAnswer.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                .add(entry.getValue()));
        return byAnswer;
    }

    /**
     * 读一个可空的 {@code decimal} 列。
     *
     * <p>不能强转 {@code Double}：{@code decimal(5,4)} 在 H2 与 MySQL 上都按 {@code BigDecimal}
     * 取出来，强转会在运行时炸掉整个待复核列表。走 {@code Number} 两种驱动都收。
     */
    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.doubleValue();
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
        // 最终错因是论文统计的口径来源，不能是自由文本。ACCEPT 沿用模型建议，
        // 那也必须落在固定集合里，否则同样拒绝。
        if (!ErrorType.isKnown(errorType)) {
            throw new DomainException("REVIEW_ERROR_TYPE_INVALID", "最终错因必须从固定标签中选择");
        }
        if (finalScore < 0 || finalScore > result.totalScore()) {
            throw new DomainException("REVIEW_SCORE_OUT_OF_RANGE", "最终得分超出题目分值范围");
        }
        long reviewId = GeneratedKeys.insert(jdbc, """
                insert into teacher_review(result_id, teacher_id, decision, final_score,
                                           final_error_type, feedback, reason, teacher_seconds)
                values (:resultId, :teacherId, :decision, :score, :errorType, :feedback, :reason, :seconds)
                """, statement -> statement
            .param("resultId", resultId).param("teacherId", teacherId)
            .param("decision", command.decision().name()).param("score", finalScore)
            .param("errorType", errorType).param("feedback", feedback).param("reason", command.reason())
            .param("seconds", sanitizeSeconds(command.teacherSeconds())));
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

    /**
     * 复核耗时是缺失即缺失的统计量：无法取值时记 null，绝不用 0 顶替——
     * 0 秒会被均值算法当成“教师一瞬间就批完了”，把省时比例算得虚高。
     */
    private static Double sanitizeSeconds(Double seconds) {
        if (seconds == null) return null;
        if (seconds.isNaN() || seconds.isInfinite() || seconds < 0 || seconds > MAX_TEACHER_SECONDS) {
            log.warn("教师复核耗时超出可接受范围，按缺失记录：seconds={} 上限={}", seconds, MAX_TEACHER_SECONDS);
            return null;
        }
        return seconds;
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
