package com.homework.analysis.assignment;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentAssignmentApiTest {

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
              (2, 'b', 'x', 'TEACHER', true),
              (3, 'stu_one', 'x', 'STUDENT', true),
              (4, 'stu_two', 'x', 'STUDENT', true),
              (5, 'stu_other', 'x', 'STUDENT', true)
            """);
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲'), (22, 2, '教师乙')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C-101', '七年级一班'), (202, 22, 'C-202', '八年级一班')");
        jdbc.update("""
            insert into student(id, class_id, student_no, name, user_id) values
              (1001, 101, '001', '张三', 3),
              (1002, 101, '002', '李四', 4),
              (2001, 202, '001', '王五', 5)
            """);
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active) values (301, 11, 'ALG', '代数', 7, true)");
        jdbc.update("""
            insert into question(id, teacher_id, question_code, type, content, standard_answer, total_score,
                                 primary_knowledge_point_id, accepted_answers)
            values (401, 11, 'Q-001', 'FILL_BLANK', '题干不该外泄', '标准答案不该外泄', 5, 301, '["可接受答案不该外泄"]')
            """);
        jdbc.update("""
            insert into assignment(id, teacher_id, class_id, title, status, published_at, due_at, version) values
              (501, 11, 101, '第一单元作业', 'PUBLISHED', current_timestamp(3), '2030-01-01 00:00:00', 1),
              (502, 11, 101, '还没发布的作业', 'DRAFT', null, null, 0),
              (503, 22, 202, '别的班的作业', 'PUBLISHED', current_timestamp(3), '2030-01-01 00:00:00', 1)
            """);
        jdbc.update("insert into assignment_question(assignment_id, question_id, question_order) values (501, 401, 1)");
        jdbc.update("insert into submission(id, assignment_id, student_id, status) values (601, 501, 1001, 'IMPORTED')");
    }

    @Test
    void 学生只看到本班已发布的作业() throws Exception {
        mvc.perform(get("/api/student/assignments").header("Authorization", tokenOf(1001)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(501))
            .andExpect(jsonPath("$.data[0].title").value("第一单元作业"))
            .andExpect(jsonPath("$.data[0].className").value("七年级一班"))
            .andExpect(jsonPath("$.data[0].teacherName").value("教师甲"))
            .andExpect(jsonPath("$.data[0].status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data[0].questionCount").value(1));
    }

    @Test
    void 草稿作业与别的班的作业都返回同一个404() throws Exception {
        mvc.perform(get("/api/student/assignments/502").header("Authorization", tokenOf(1001)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_NOT_FOUND"));

        mvc.perform(get("/api/student/assignments/503").header("Authorization", tokenOf(1001)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_NOT_FOUND"));

        // 同一个班里，别人班的学生也看不到这份已发布作业对应的班级数据。
        mvc.perform(get("/api/student/assignments/503").header("Authorization", tokenOf(2001)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.className").value("八年级一班"));
    }

    /**
     * 字段白名单。
     *
     * <p>用“键集合完全相等”而不是逐条断言“不含某个字段”：以后有人为了省事往投影里加上
     * {@code standardAnswer} 或 {@code rubricItems}，本用例会立刻失败，而逐条否定断言只会
     * 检查当时想到的那几个字段名。
     *
     * <p>这里读成 {@code Map} 而不是操作 {@code JsonNode}：只需要“列出键”这一个能力，
     * 而 {@code readValue(String, Class)} 是 Jackson 最稳定的入口，
     * 不必依赖某个版本里键遍历方法叫什么名字。
     */
    @Test
    @SuppressWarnings("unchecked")
    void 学生作业响应的字段恰好是白名单() throws Exception {
        String body = mvc.perform(get("/api/student/assignments").header("Authorization", tokenOf(1001)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> root = objectMapper.readValue(body, Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) root.get("data");
        assertThat(data.get(0).keySet()).containsExactlyInAnyOrder(
            "id", "title", "className", "teacherName", "status", "dueAt", "questionCount", "submissionStatus");

        // 答案、题干与其他学生的标识一个都不能出现。
        assertThat(body)
            .doesNotContain("standardAnswer")
            .doesNotContain("acceptedAnswers")
            .doesNotContain("rubricItems")
            .doesNotContain("题干不该外泄")
            .doesNotContain("标准答案不该外泄")
            .doesNotContain("可接受答案不该外泄");
    }

    @Test
    void 学生只看得到自己的提交摘要() throws Exception {
        mvc.perform(get("/api/student/assignments").header("Authorization", tokenOf(1001)))
            .andExpect(jsonPath("$.data[0].submissionStatus").value("IMPORTED"));

        // 同班另一名学生没有提交，必须拿到 NOT_SUBMITTED 而不是室友的提交状态。
        mvc.perform(get("/api/student/assignments").header("Authorization", tokenOf(1002)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].submissionStatus").value("NOT_SUBMITTED"));
    }

    @Test
    void 教师令牌不能访问学生作业接口() throws Exception {
        mvc.perform(get("/api/student/assignments")
                .header("Authorization", "Bearer " + jwtService.issue(11, 11, "TEACHER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void 未登录访问学生作业接口会被拒绝() throws Exception {
        mvc.perform(get("/api/student/assignments"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    private String tokenOf(long studentId) {
        long userId = jdbc.queryForObject(
            "select user_id from student where id = ?", Long.class, studentId);
        return "Bearer " + jwtService.issueStudent(userId, studentId, false);
    }
}
