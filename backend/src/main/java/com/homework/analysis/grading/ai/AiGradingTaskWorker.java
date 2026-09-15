package com.homework.analysis.grading.ai;

import com.homework.analysis.assignment.AssignmentService;
import com.homework.analysis.grading.ErrorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class AiGradingTaskWorker {
    private static final Logger log = LoggerFactory.getLogger(AiGradingTaskWorker.class);
    /** 候选任务可能被其它线程抢先领取，最多重新挑选若干轮，避免线程在同一答案上反复竞争。 */
    private static final int MAX_CLAIM_ROUNDS = 3;

    private final JdbcClient jdbc;
    private final AssignmentService assignments;
    private final AiModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final String modelName;
    private final Clock clock;

    AiGradingTaskWorker(JdbcClient jdbc, AssignmentService assignments, AiModelClient modelClient,
                        ObjectMapper objectMapper, @Value("${app.model.name:disabled}") String modelName,
                        Clock clock) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
        this.clock = clock;
    }

    public AiTaskProcessResult processOne(long teacherId, long assignmentId) {
        assignments.requireOwned(teacherId, assignmentId);
        TaskData task = claimNextTask(assignmentId);
        if (task == null) return new AiTaskProcessResult(false, null, "EMPTY", "没有可处理的 AI 任务");
        AiGradingRequest request = new AiGradingRequest("answer-" + task.answerId(), task.question(),
            task.standardAnswer(), task.answer(), task.totalScore(), rubrics(task.answerId()), ErrorType.aiCodes());
        try {
            ModelCall call = modelClient.grade(request);
            AiGradingSuggestion suggestion = call.suggestion();
            String details = objectMapper.writeValueAsString(suggestion.scoreDetails());
            // ai_error_type 与 error_type 写入同一个值：error_type 之后会被教师复核改写，
            // 模型原始错因必须留在这一列里，否则错因一致率无从统计。
            jdbc.sql("""
                    insert into grading_result(answer_id, source, suggested_score, error_type, ai_error_type,
                                               teacher_explanation, student_feedback, score_details, status)
                    values (:answerId, 'AI', :score, :errorType, :aiErrorType, :explanation, :feedback, :details,
                            'PENDING_REVIEW')
                    """)
                .param("answerId", task.answerId())
                .param("score", suggestion.suggestedScore())
                .param("errorType", suggestion.errorType())
                .param("aiErrorType", suggestion.errorType())
                .param("explanation", suggestion.teacherExplanation())
                .param("feedback", suggestion.studentFeedback())
                .param("details", details)
                .update();
            jdbc.sql("""
                    update ai_grading_task set status = 'SUCCEEDED', model_name = :modelName,
                        sanitized_response = :response, input_tokens = :inputTokens,
                        output_tokens = :outputTokens, ai_seconds = :aiSeconds,
                        attempt_count = attempt_count + 1,
                        updated_at = current_timestamp(3) where id = :id
                    """)
                .param("modelName", modelName)
                .param("response", details)
                .param("inputTokens", call.inputTokens())
                .param("outputTokens", call.outputTokens())
                .param("aiSeconds", call.aiSeconds())
                .param("id", task.taskId())
                .update();
            return new AiTaskProcessResult(true, task.taskId(), "SUCCEEDED", "AI 建议已生成，等待教师复核");
        } catch (Exception exception) {
            int attempts = task.attemptCount() + 1;
            String status = attempts >= 4 ? "MANUAL_REQUIRED" : "RETRYABLE_FAILED";
            // 失败原因写入日志：这里把所有异常统一映射为同一条对外消息，若再不记录 cause，
            // 配置错误、连接失败与响应解析失败在外部完全无法区分。异常链不含密钥与响应体
            // （见 OpenAiCompatibleModelClient 的封装），可以安全落盘。
            log.warn("AI 评分任务处理失败：taskId={} answerId={} 模型={} 状态={} 第 {} 次尝试",
                task.taskId(), task.answerId(), modelName, status, attempts, exception);
            Instant now = clock.instant();
            Instant next = switch (attempts) {
                case 1 -> now.plus(30, ChronoUnit.SECONDS);
                case 2 -> now.plus(2, ChronoUnit.MINUTES);
                default -> now.plus(10, ChronoUnit.MINUTES);
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

    /**
     * 领取一个可处理的任务。候选行被其它线程抢先领取时条件更新会影响 0 行，
     * 此时重新挑选下一行；连续 {@link #MAX_CLAIM_ROUNDS} 轮都抢不到则返回空，交由调用方稍后重试。
     */
    private TaskData claimNextTask(long assignmentId) {
        for (int round = 0; round < MAX_CLAIM_ROUNDS; round++) {
            List<TaskData> candidates = jdbc.sql("""
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
            if (candidates.isEmpty()) return null;
            TaskData candidate = candidates.getFirst();
            if (markRunning(candidate.taskId()) == 1) return candidate;
        }
        return null;
    }

    /**
     * 带旧状态条件的原子领取。只有受影响行数为 1 的调用者可以继续请求模型，
     * 从而保证两个线程不会重复处理同一条学生答案。
     */
    private int markRunning(long taskId) {
        return jdbc.sql("""
                update ai_grading_task
                set status = 'RUNNING', updated_at = current_timestamp(3)
                where id = :id
                  and status in ('PENDING', 'RETRYABLE_FAILED')
                  and (next_attempt_at is null or next_attempt_at <= current_timestamp(3))
                """)
            .param("id", taskId)
            .update();
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
