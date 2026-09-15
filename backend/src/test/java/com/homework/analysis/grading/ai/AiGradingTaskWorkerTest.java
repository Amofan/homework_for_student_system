package com.homework.analysis.grading.ai;

import com.homework.analysis.assignment.AssignmentService;
import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.testing.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 用固定时钟验证 AI 任务的有限重试与退避状态机。
 *
 * <p>固定时钟取未来时刻：数据库中真实的 current_timestamp 远早于它，
 * 因此“退避期内不再领取”才是真实可验证的；需要模拟退避到期时，
 * 由测试直接把 next_attempt_at 改到过去，而不是推进时钟
 * （推进注入的时钟不会改变数据库的 current_timestamp）。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiGradingTaskWorkerTest {

    private static final Instant FIXED_NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant PAST = Instant.parse("2026-01-01T00:00:00Z");
    private static final long TEACHER_ID = 11L;
    private static final long ASSIGNMENT_ID = 501L;
    private static final long TASK_ID = 801L;

    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcClient jdbcClient;
    @Autowired AssignmentService assignments;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean AiModelClient modelClient;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'a', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C1', '七年级一班')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (1001, 101, '001', '张三')");
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active) values (301, 11, 'ALG', '一元一次方程', 7, true)");
        jdbc.update("insert into question(id, teacher_id, question_code, type, content, total_score, primary_knowledge_point_id, accepted_answers) values (401, 11, 'Q1', 'FILL_BLANK', '题1', 10, 301, '[\"2\"]')");
        jdbc.update("insert into question_knowledge_point(question_id, knowledge_point_id, is_primary) values (401, 301, true)");
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status) values (501, 11, 101, '作业', 'IMPORTED')");
        jdbc.update("insert into assignment_question(assignment_id, question_id, question_order) values (501, 401, 1)");
        jdbc.update("insert into submission(id, assignment_id, student_id, status) values (601, 501, 1001, 'IMPORTED')");
        jdbc.update("insert into student_answer(id, submission_id, question_id, answer_content) values (611, 601, 401, '2')");
        jdbc.update("insert into ai_grading_task(id, answer_id, status) values (?, ?, 'PENDING')", TASK_ID, 611L);
    }

    @Test
    void 首次失败后按30秒退避且退避期内不再领取() {
        given(modelClient.grade(any())).willThrow(new IllegalStateException("模型不可用"));

        AiTaskProcessResult first = worker().processOne(TEACHER_ID, ASSIGNMENT_ID);

        assertThat(first.processed()).isTrue();
        assertThat(first.status()).isEqualTo("RETRYABLE_FAILED");
        assertThat(task().get("attempt_count")).isEqualTo(1);
        assertThat(task().get("last_error_code")).isEqualTo("MODEL_CALL_FAILED");
        assertThat(nextAttemptAt()).isEqualTo(FIXED_NOW.plus(30, ChronoUnit.SECONDS));

        // 退避未到期，同一任务不得被再次领取
        AiTaskProcessResult second = worker().processOne(TEACHER_ID, ASSIGNMENT_ID);

        assertThat(second.status()).isEqualTo("EMPTY");
        assertThat(task().get("attempt_count")).isEqualTo(1);
        assertThat(countGradingResults()).isZero();
    }

    @Test
    void 第二次失败退避两分钟() {
        given(modelClient.grade(any())).willThrow(new IllegalStateException("模型不可用"));

        failAndExpire();
        worker().processOne(TEACHER_ID, ASSIGNMENT_ID);

        assertThat(task().get("attempt_count")).isEqualTo(2);
        assertThat(nextAttemptAt()).isEqualTo(FIXED_NOW.plus(2, ChronoUnit.MINUTES));
    }

    @Test
    void 第三次失败退避十分钟() {
        given(modelClient.grade(any())).willThrow(new IllegalStateException("模型不可用"));

        failAndExpire();
        failAndExpire();
        worker().processOne(TEACHER_ID, ASSIGNMENT_ID);

        assertThat(task().get("attempt_count")).isEqualTo(3);
        assertThat(nextAttemptAt()).isEqualTo(FIXED_NOW.plus(10, ChronoUnit.MINUTES));
    }

    @Test
    void 第四次失败转为人工处理且不再自动重试() {
        given(modelClient.grade(any())).willThrow(new IllegalStateException("模型不可用"));

        failAndExpire();
        failAndExpire();
        failAndExpire();
        AiTaskProcessResult fourth = worker().processOne(TEACHER_ID, ASSIGNMENT_ID);

        assertThat(fourth.status()).isEqualTo("MANUAL_REQUIRED");
        assertThat(task().get("attempt_count")).isEqualTo(4);
        assertThat(task().get("status")).isEqualTo("MANUAL_REQUIRED");

        // 人工处理态不属于可领取状态，即便退避时间已过也不再自动重试
        jdbc.update("update ai_grading_task set next_attempt_at = ? where id = ?", Timestamp.from(PAST), TASK_ID);
        assertThat(worker().processOne(TEACHER_ID, ASSIGNMENT_ID).status()).isEqualTo("EMPTY");
    }

    @Test
    void 模型返回建议时写入待复核结果并结束任务() {
        given(modelClient.grade(any())).willReturn(call(suggestion(), 513, 604, 3.799));

        AiTaskProcessResult result = worker().processOne(TEACHER_ID, ASSIGNMENT_ID);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(task().get("status")).isEqualTo("SUCCEEDED");
        assertThat(task().get("model_name")).isEqualTo("stub-model");
        assertThat(task().get("attempt_count")).isEqualTo(1);

        Map<String, Object> grading = jdbc.queryForMap("select source, suggested_score, error_type, ai_error_type,"
            + " status from grading_result where answer_id = 611");
        assertThat(grading.get("source")).isEqualTo("AI");
        assertThat(grading.get("suggested_score")).isEqualTo(6);
        assertThat(grading.get("error_type")).isEqualTo("CORRECT");
        // 复核会改写 error_type，模型原始错因必须同时留在 ai_error_type
        assertThat(grading.get("ai_error_type")).isEqualTo("CORRECT");
        assertThat(grading.get("status")).isEqualTo("PENDING_REVIEW");

        Map<String, Object> call = jdbc.queryForMap("select input_tokens, output_tokens, ai_seconds"
            + " from ai_grading_task where id = " + TASK_ID);
        assertThat(call.get("input_tokens")).isEqualTo(513);
        assertThat(call.get("output_tokens")).isEqualTo(604);
        assertThat(((Number) call.get("ai_seconds")).doubleValue()).isEqualTo(3.799);
    }

    @Test
    void 模型未返回用量时记为空而不是零() {
        given(modelClient.grade(any())).willReturn(call(suggestion(), null, null, 0.512));

        assertThat(worker().processOne(TEACHER_ID, ASSIGNMENT_ID).status()).isEqualTo("SUCCEEDED");

        Map<String, Object> stored = jdbc.queryForMap("select input_tokens, output_tokens, ai_seconds"
            + " from ai_grading_task where id = " + TASK_ID);
        assertThat(stored.get("input_tokens")).isNull();
        assertThat(stored.get("output_tokens")).isNull();
        assertThat(((Number) stored.get("ai_seconds")).doubleValue()).isEqualTo(0.512);
    }

    @Test
    void 非本人作业不会被处理() {
        assertThatThrownBy(() -> worker().processOne(999L, ASSIGNMENT_ID))
            .isInstanceOf(DomainException.class);

        // 越权请求不得改动任何任务状态
        assertThat(task().get("status")).isEqualTo("PENDING");
        assertThat(task().get("attempt_count")).isEqualTo(0);
    }

    private static AiGradingSuggestion suggestion() {
        return new AiGradingSuggestion(6, List.of(new AiGradingSuggestion.RubricScoreDetail(1, 6, "列式正确")),
            "CORRECT", "过程完整", "保持书写规范", true);
    }

    private static ModelCall call(AiGradingSuggestion suggestion, Integer inputTokens,
                                  Integer outputTokens, double aiSeconds) {
        return new ModelCall(suggestion, inputTokens, outputTokens, aiSeconds);
    }

    private void failAndExpire() {
        worker().processOne(TEACHER_ID, ASSIGNMENT_ID);
        jdbc.update("update ai_grading_task set next_attempt_at = ? where id = ?", Timestamp.from(PAST), TASK_ID);
    }

    private AiGradingTaskWorker worker() {
        return new AiGradingTaskWorker(jdbcClient, assignments, modelClient, objectMapper,
            "stub-model", Clock.fixed(FIXED_NOW, java.time.ZoneOffset.UTC));
    }

    private Map<String, Object> task() {
        return jdbc.queryForMap("select status, attempt_count, model_name, next_attempt_at, last_error_code"
            + " from ai_grading_task where id = " + TASK_ID);
    }

    private Instant nextAttemptAt() {
        return jdbc.queryForObject("select next_attempt_at from ai_grading_task where id = " + TASK_ID,
            (rs, rowNum) -> rs.getTimestamp("next_attempt_at").toInstant());
    }

    private int countGradingResults() {
        return jdbc.queryForObject("select count(*) from grading_result", Integer.class);
    }
}
