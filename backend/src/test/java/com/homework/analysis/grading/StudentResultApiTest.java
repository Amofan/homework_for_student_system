package com.homework.analysis.grading;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.testing.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 学生端成绩接口：学生能看到什么，以及**不能**看到什么。
 *
 * <p>这个类的重点不是"分数算得对不对"，而是投影边界。教师端的模型建议分、评分明细、
 * 标准答案、教师改写原因都在库里摆着，任何一个被顺手带出来，学生看到的就可能是
 * 一个后来被老师改掉的分数，或者批改依据本身。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentResultApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("""
            insert into app_user(id, username, password_hash, role, enabled) values
              (1, 'a', 'x', 'TEACHER', true),
              (3, 'stu_one', 'x', 'STUDENT', true),
              (4, 'stu_two', 'x', 'STUDENT', true),
              (5, 'stu_three', 'x', 'STUDENT', true)
            """);
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C-101', '七年级一班')");
        jdbc.update("""
            insert into student(id, class_id, student_no, name, user_id) values
              (1001, 101, '001', '张三', 3),
              (1002, 101, '002', '李四', 4),
              (1003, 101, '003', '王五', 5)
            """);
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active) values (301, 11, 'ALG', '代数', 7, true)");
        jdbc.update("""
            insert into question(id, teacher_id, question_code, type, content, standard_answer, total_score,
                                 primary_knowledge_point_id, accepted_answers) values
              (401, 11, 'Q-001', 'FILL_BLANK', '解方程 $2x+1=5$', '标准答案不该外泄', 10, 301, '["2"]'),
              (402, 11, 'Q-002', 'SOLUTION', '说明每一步的依据', '标准答案不该外泄', 10, 301, '[]')
            """);
        jdbc.update("""
            insert into assignment(id, teacher_id, class_id, title, status, published_at, version) values
              (501, 11, 101, '第一单元作业', 'PUBLISHED', current_timestamp(3), 1),
              (502, 11, 101, '还没发布的作业', 'DRAFT', null, 0)
            """);
        jdbc.update("insert into assignment_question(assignment_id, question_id, question_order) values (501, 401, 1), (501, 402, 2)");
        // 张三两题都答了，李四只答了第一题（第二题没作答，就不该算进"没批完"）。
        jdbc.update("""
            insert into submission(id, assignment_id, student_id, status) values
              (601, 501, 1001, 'IMPORTED'), (602, 501, 1002, 'IMPORTED')
            """);
        jdbc.update("""
            insert into student_answer(id, submission_id, question_id, answer_content) values
              (611, 601, 401, '2'), (612, 601, 402, '设 x……所以 x=2'), (613, 602, 401, '2')
            """);
        jdbc.update("""
            insert into grading_result(id, answer_id, source, suggested_score, confirmed_score, error_type,
                                       ai_error_type, teacher_explanation, student_feedback, score_details, status) values
              (701, 611, 'RULE', 5, 8, 'CALCULATION_ERROR', null, '不该外泄的教师解释', '请复习移项这一步', '[]', 'CONFIRMED'),
              (702, 612, 'AI', 9, null, 'INCOMPLETE', 'INCOMPLETE', '不该外泄的教师解释', '模型的建议反馈', '[{"rubricId":801,"score":9}]', 'PENDING_REVIEW'),
              (703, 613, 'RULE', 10, 10, 'CORRECT', null, '答得很好', '回答正确', '[]', 'CONFIRMED')
            """);
        // 复核时间显式写死：完成时间取的是这一列，用默认的当前时间断言不出"最后一道"这件事。
        jdbc.update("""
            insert into teacher_review(id, result_id, teacher_id, decision, final_score, final_error_type,
                                       feedback, reason, created_at) values
              (901, 701, 11, 'MODIFY', 8, 'CALCULATION_ERROR', '请复习移项这一步', '不该外泄的改写原因', '2026-09-01 08:00:00'),
              (902, 703, 11, 'ACCEPT', 10, 'CORRECT', '回答正确', null, '2026-09-02 09:30:00')
            """);
    }

    @Test
    void 学生看到的是教师确认过的分数与反馈() throws Exception {
        mvc.perform(get("/api/student/assignments/501/result").header("Authorization", tokenOf(1001)))
            .andExpect(status().isOk())
            // 两题作答、只批完一题：报的是"已经批完的那部分"，分母跟着已批的题走，
            // 而不是整份作业的 20 分——否则 8/20 会被学生读成考砸了。
            .andExpect(jsonPath("$.data.state").value("PARTIAL"))
            .andExpect(jsonPath("$.data.confirmedScore").value(8))
            .andExpect(jsonPath("$.data.gradedScore").value(10))
            .andExpect(jsonPath("$.data.gradedQuestionCount").value(1))
            .andExpect(jsonPath("$.data.questionCount").value(2))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].questionCode").value("Q-001"))
            .andExpect(jsonPath("$.data.items[0].questionContent").value("解方程 $2x+1=5$"))
            .andExpect(jsonPath("$.data.items[0].confirmedScore").value(8))
            .andExpect(jsonPath("$.data.items[0].totalScore").value(10))
            .andExpect(jsonPath("$.data.items[0].feedback").value("请复习移项这一步"));
    }

    @Test
    void 待复核的模型建议绝不进学生端() throws Exception {
        String body = mvc.perform(get("/api/student/assignments/501/result").header("Authorization", tokenOf(1001)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> root = objectMapper.readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) root.get("data");
        // 后端配置了 non_null 输出，所以版本号这类空字段不会出现；这里盯的是"出现过的键"。
        assertThat(data.keySet()).containsExactlyInAnyOrder("assignmentId", "state", "confirmedScore",
            "gradedScore", "gradedQuestionCount", "questionCount", "completedAt", "items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertThat(items.get(0).keySet()).containsExactlyInAnyOrder("questionCode", "questionContent",
            "confirmedScore", "totalScore", "feedback", "confirmedAt");

        // 建议分、评分明细、模型原始错因、标准答案与教师改写原因一个都不能出现。
        // 最终错因同样不给：教师"采纳"时它与模型建议一模一样，返回它等于把模型判断发给学生。
        assertThat(body)
            .doesNotContain("suggestedScore")
            .doesNotContain("scoreDetails")
            .doesNotContain("aiErrorType")
            .doesNotContain("errorType")
            .doesNotContain("rubricId")
            .doesNotContain("standardAnswer")
            .doesNotContain("模型的建议反馈")
            .doesNotContain("不该外泄的教师解释")
            .doesNotContain("不该外泄的改写原因")
            .doesNotContain("标准答案不该外泄");
    }

    @Test
    void 一道题都没批完时给的是空而不是零分() throws Exception {
        jdbc.update("update grading_result set confirmed_score = null, status = 'PENDING_REVIEW' where id = 701");

        mvc.perform(get("/api/student/assignments/501/result").header("Authorization", tokenOf(1001)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("PENDING"))
            // 0 分是一个结论：老师还没批，学生看到 0/10 会以为自己考砸了。
            .andExpect(jsonPath("$.data.confirmedScore").doesNotExist())
            .andExpect(jsonPath("$.data.gradedScore").doesNotExist())
            .andExpect(jsonPath("$.data.completedAt").doesNotExist())
            .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void 被退回作废的评分不算成绩() throws Exception {
        // 作废用标记而不是删除，所以行还在库里；不显式排除就会当成有效成绩发给学生。
        jdbc.update("update grading_result set invalidated_at = current_timestamp(3) where id = 701");

        mvc.perform(get("/api/student/assignments/501/result").header("Authorization", tokenOf(1001)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("PENDING"))
            .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void 没作答的题不算进没批完的题数() throws Exception {
        // 李四只答了第一题。分母跟着作答走：拿作业的题目总数当分母，
        // 他永远等不到"批完了"，而那道题他本来就没写。
        mvc.perform(get("/api/student/assignments/501/result").header("Authorization", tokenOf(1002)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("GRADED"))
            .andExpect(jsonPath("$.data.questionCount").value(1))
            .andExpect(jsonPath("$.data.gradedQuestionCount").value(1))
            .andExpect(jsonPath("$.data.confirmedScore").value(10))
            .andExpect(jsonPath("$.data.items[0].questionCode").value("Q-001"));
    }

    @Test
    void 没交过的学生拿到的是还没交的状态() throws Exception {
        mvc.perform(get("/api/student/assignments/501/result").header("Authorization", tokenOf(1003)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("NOT_SUBMITTED"))
            .andExpect(jsonPath("$.data.questionCount").value(0))
            .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void 成绩会说明它属于哪一版() throws Exception {
        jdbc.update("""
            insert into submission_version(id, submission_id, assignment_id, student_id, version_no,
                                           status, is_current) values (1101, 601, 501, 1001, 2, 'LOCKED', true)
            """);
        jdbc.update("update submission set submission_version_id = 1101 where id = 601");

        // 学生重交之后，库里的成绩仍属于上一次批的那一版；页面要能说清这一点，
        // 否则一个正在等新一版结果的学生会把旧分数当成新版的。
        mvc.perform(get("/api/student/assignments/501/result").header("Authorization", tokenOf(1001)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.versionNo").value(2));
    }

    @Test
    void 没发布的作业按不存在处理() throws Exception {
        mvc.perform(get("/api/student/assignments/502/result").header("Authorization", tokenOf(1001)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_NOT_FOUND"));

        mvc.perform(get("/api/student/assignments/999/result").header("Authorization", tokenOf(1001)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_NOT_FOUND"));
    }

    @Test
    void 教师令牌不能读学生成绩接口() throws Exception {
        mvc.perform(get("/api/student/assignments/501/result")
                .header("Authorization", "Bearer " + jwtService.issue(11, 11, "TEACHER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 完成时间取最后一条复核记录的时刻() throws Exception {
        jdbc.update("update grading_result set confirmed_score = 10, status = 'CONFIRMED' where id = 702");
        jdbc.update("""
            insert into teacher_review(id, result_id, teacher_id, decision, final_score, final_error_type,
                                       feedback, reason, created_at)
            values (903, 702, 11, 'MODIFY', 10, 'CORRECT', '补完整答句', '写得更完整', '2026-09-03 21:15:00')
            """);

        String body = mvc.perform(get("/api/student/assignments/501/result").header("Authorization", tokenOf(1001)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("GRADED"))
            .andExpect(jsonPath("$.data.gradedQuestionCount").value(2))
            .andExpect(jsonPath("$.data.confirmedScore").value(18))
            .andExpect(jsonPath("$.data.gradedScore").value(20))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> data = (Map<String, Object>) objectMapper.readValue(body, Map.class).get("data");
        // 与库里的最大值比对，而不是断言一个写死的时间串：这条用例要证明的是
        // "取的是最后批完的那一道"，时区换算交给两边同一套 JDBC 逻辑。
        Instant lastReview = jdbc.queryForObject(
            "select max(created_at) from teacher_review", Timestamp.class).toInstant();
        assertThat(Instant.parse((String) data.get("completedAt"))).isEqualTo(lastReview);
    }

    /** 学生令牌：用户名与 user_id 都得对得上，否则安全层拿不到学生角色。 */
    private String tokenOf(long studentId) {
        long userId = jdbc.queryForObject("select user_id from student where id = ?", Long.class, studentId);
        return "Bearer " + jwtService.issueStudent(userId, studentId, false);
    }
}
