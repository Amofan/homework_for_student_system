package com.homework.analysis.grading.review;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.testing.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 教师复核接口的论文取数契约：复核耗时如何落库，以及复核改写错因时
 * 模型原始错因是否仍然保留。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeacherReviewApiTest {
    private static final long RESULT_ID = 701L;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;

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
        jdbc.update("insert into grading_result(id, answer_id, source, suggested_score, error_type, ai_error_type,"
            + " score_details, status) values (701, 611, 'AI', 8, 'CALCULATION_ERROR', 'METHOD_ERROR', '[]', 'PENDING_REVIEW')");
    }

    @Test
    void 前端上报的复核耗时按秒记录() throws Exception {
        review("{\"decision\":\"ACCEPT\",\"feedback\":\"请复习计算\",\"teacherSeconds\":12.345}");

        assertThat(teacherSeconds()).isEqualTo(12.345);
    }

    @Test
    void 未上报耗时时记为空而不是零() throws Exception {
        review("{\"decision\":\"ACCEPT\",\"feedback\":\"请复习计算\"}");

        // 缺失与 0 必须区分：0 会被统计成“教师一瞬间批完”，把省时比例算得虚高
        assertThat(teacherSeconds()).isNull();
    }

    @Test
    void 耗时超出上限时记为空且不阻断复核() throws Exception {
        review("{\"decision\":\"ACCEPT\",\"teacherSeconds\":7200}");

        assertThat(teacherSeconds()).isNull();
        assertThat(jdbc.queryForObject("select status from grading_result where id = 701", String.class))
            .isEqualTo("CONFIRMED");
    }

    @Test
    void 耗时是负数时记为空() throws Exception {
        review("{\"decision\":\"ACCEPT\",\"teacherSeconds\":-3}");

        assertThat(teacherSeconds()).isNull();
    }

    @Test
    void 复核改写错因但模型原始错因仍留在ai_error_type() throws Exception {
        review("{\"decision\":\"MODIFY\",\"finalScore\":6,\"errorType\":\"CONCEPT_ERROR\","
            + "\"reason\":\"概念理解有误\"}");

        Map<String, Object> row = jdbc.queryForMap(
            "select error_type, ai_error_type, confirmed_score from grading_result where id = 701");
        // error_type 被教师复核覆盖，ai_error_type 必须留着模型原判，否则错因一致率无从统计
        assertThat(row.get("error_type")).isEqualTo("CONCEPT_ERROR");
        assertThat(row.get("ai_error_type")).isEqualTo("METHOD_ERROR");
        assertThat(row.get("confirmed_score")).isEqualTo(6);
    }

    private void review(String body) throws Exception {
        mvc.perform(post("/api/grading/results/" + RESULT_ID + "/review")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.resultId").value(RESULT_ID));
    }

    private Double teacherSeconds() {
        return jdbc.queryForObject("select teacher_seconds from teacher_review where result_id = 701", Double.class);
    }

    private String bearer() {
        return "Bearer " + jwtService.issue(11, 11, "TEACHER");
    }
}
