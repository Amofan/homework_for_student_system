package com.homework.analysis.grading.ai;

import com.homework.analysis.assignment.AssignmentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class AiGradingTaskWorker {
    private static final List<String> ERROR_TYPES = List.of(
        "CORRECT", "CALCULATION_ERROR", "METHOD_ERROR", "CONCEPT_ERROR", "INCOMPLETE", "OTHER");
    private final JdbcClient jdbc;
    private final AssignmentService assignments;
    private final AiModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final String modelName;

    AiGradingTaskWorker(JdbcClient jdbc, AssignmentService assignments, AiModelClient modelClient,
                        ObjectMapper objectMapper, @Value("${app.model.name:disabled}") String modelName) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
    }

    public AiTaskProcessResult processOne(long teacherId, long assignmentId) {
        assignments.requireOwned(teacherId, assignmentId);
        List<TaskData> tasks = jdbc.sql("""
                select t.id as task_id, t.answer_id, t.attempt_count,
                       sa.answer_content, q.content, q.standard_answer, q.total_score
                from ai_grading_task t
                join student_answer sa on sa.id = t.answer_id
                join submission s on s.id = sa.submission_id
                join question q on q.id = sa.question_id
                where s.assignment_id = :assignmentId
                  and t.status in ('PENDING', 'RETRYABLE_FAILED')
                  and (t.next_attempt_at is null or t.next_attempt_at <= current_timestamp(3))
                order by t.id limit 1
                """)
            .param("assignmentId", assignmentId)
            .query((rs, rowNum) -> new TaskData(
                rs.getLong("task_id"), rs.getLong("answer_id"), rs.getInt("attempt_count"),
                rs.getString("answer_content"), rs.getString("content"),
                rs.getString("standard_answer"), rs.getInt("total_score")))
            .list();
        if (tasks.isEmpty()) return new AiTaskProcessResult(false, null, "EMPTY", "没有可处理的 AI 任务");
        TaskData task = tasks.getFirst();
        jdbc.sql("update ai_grading_task set status = 'RUNNING', updated_at = current_timestamp(3) where id = :id")
            .param("id", task.taskId()).update();
        AiGradingRequest request = new AiGradingRequest("answer-" + task.answerId(), task.question(),
            task.standardAnswer(), task.answer(), task.totalScore(), rubrics(task.answerId()), ERROR_TYPES);
        try {
            AiGradingSuggestion suggestion = modelClient.grade(request);
            String details = objectMapper.writeValueAsString(suggestion.scoreDetails());
            jdbc.sql("""
                    insert into grading_result(answer_id, source, suggested_score, error_type,
                                               teacher_explanation, student_feedback, score_details, status)
                    values (:answerId, 'AI', :score, :errorType, :explanation, :feedback, :details, 'PENDING_REVIEW')
                    """)
                .param("answerId", task.answerId())
                .param("score", suggestion.suggestedScore())
                .param("errorType", suggestion.errorType())
                .param("explanation", suggestion.teacherExplanation())
                .param("feedback", suggestion.studentFeedback())
                .param("details", details)
                .update();
            jdbc.sql("""
                    update ai_grading_task set status = 'SUCCEEDED', model_name = :modelName,
                        sanitized_response = :response, attempt_count = attempt_count + 1,
                        updated_at = current_timestamp(3) where id = :id
                    """)
                .param("modelName", modelName)
                .param("response", details)
                .param("id", task.taskId())
                .update();
            return new AiTaskProcessResult(true, task.taskId(), "SUCCEEDED", "AI 建议已生成，等待教师复核");
        } catch (Exception exception) {
            int attempts = task.attemptCount() + 1;
            String status = attempts >= 4 ? "MANUAL_REQUIRED" : "RETRYABLE_FAILED";
            Instant next = switch (attempts) {
                case 1 -> Instant.now().plus(30, ChronoUnit.SECONDS);
                case 2 -> Instant.now().plus(2, ChronoUnit.MINUTES);
                default -> Instant.now().plus(10, ChronoUnit.MINUTES);
            };
            jdbc.sql("""
                    update ai_grading_task set status = :status, attempt_count = :attempts,
                        next_attempt_at = :nextAttempt, last_error_code = 'MODEL_CALL_FAILED',
                        updated_at = current_timestamp(3) where id = :id
                    """)
                .param("status", status)
                .param("attempts", attempts)
                .param("nextAttempt", next)
                .param("id", task.taskId())
                .update();
            return new AiTaskProcessResult(true, task.taskId(), status, "模型暂时不可用，任务已安全保留");
        }
    }

    private List<AiGradingRequest.Rubric> rubrics(long answerId) {
        return jdbc.sql("""
                select r.id, r.title, r.criteria, r.max_score from rubric_item r
                join student_answer sa on sa.question_id = r.question_id
                where sa.id = :answerId order by r.order_no
                """)
            .param("answerId", answerId)
            .query((rs, rowNum) -> new AiGradingRequest.Rubric(rs.getInt("id"), rs.getString("title"),
                rs.getString("criteria"), rs.getInt("max_score")))
            .list();
    }

    private record TaskData(long taskId, long answerId, int attemptCount, String answer,
                            String question, String standardAnswer, int totalScore) {}
}
