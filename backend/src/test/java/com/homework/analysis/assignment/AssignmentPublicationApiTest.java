package com.homework.analysis.assignment;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssignmentPublicationApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'a', 'x', 'TEACHER', true), (2, 'b', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲'), (22, 2, '教师乙')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C-101', '七年级一班'), (202, 22, 'C-202', '八年级一班')");
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active) values (301, 11, 'ALG', '代数', 7, true)");
        jdbc.update("insert into question(id, teacher_id, question_code, type, content, total_score, primary_knowledge_point_id, accepted_answers) values (401, 11, 'Q-001', 'FILL_BLANK', '1+1', 5, 301, '[\"2\"]')");
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status) values (501, 11, 101, '第一单元作业', 'DRAFT')");
        jdbc.update("insert into assignment_question(assignment_id, question_id, question_order) values (501, 401, 1)");
        // 502 没有题目，用来验证“空作业不能发布”。
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status) values (502, 11, 101, '空作业', 'DRAFT')");
        // 503 属于另一位教师，用来验证发布接口不越权。
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status) values (503, 22, 202, '别人的作业', 'DRAFT')");
    }

    @Test
    void 发布把草稿推进到已发布并记录时间与版本() throws Exception {
        mvc.perform(get("/api/teacher/assignments/501").header("Authorization", bearerFor(11)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.version").value(0))
            .andExpect(jsonPath("$.data.publishedAt").doesNotExist());

        mvc.perform(post("/api/teacher/assignments/501/publish")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"dueAt\":\"2030-01-01T00:00:00Z\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.publishedAt").isNotEmpty())
            .andExpect(jsonPath("$.data.dueAt").value("2030-01-01T00:00:00Z"));

        assertThat(jdbc.queryForObject("select published_at from assignment where id = 501", Object.class))
            .isNotNull();
    }

    @Test
    void 不传截止时间也可以发布() throws Exception {
        mvc.perform(post("/api/teacher/assignments/501/publish")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.dueAt").doesNotExist());

        assertThat(jdbc.queryForObject("select count(*) from assignment where id = 501 and due_at is null",
            Integer.class)).isEqualTo(1);
    }

    /**
     * 版本号对不上时必须拒绝。
     *
     * <p>这里刻意传一个不存在的版本号：若只按“状态是否允许发布”判断，这次请求会被放行，
     * 两位教师同时发布就会互相覆盖截止时间。
     */
    @Test
    void 版本号过期时拒绝发布并提示刷新() throws Exception {
        mvc.perform(post("/api/teacher/assignments/501/publish")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":7}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_VERSION_CONFLICT"));

        assertThat(jdbc.queryForObject("select status from assignment where id = 501", String.class))
            .isEqualTo("DRAFT");
    }

    @Test
    void 缺少版本号时参数校验失败() throws Exception {
        mvc.perform(post("/api/teacher/assignments/501/publish")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 没有题目的作业不能发布() throws Exception {
        mvc.perform(post("/api/teacher/assignments/502/publish")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_HAS_NO_QUESTION"));
    }

    @Test
    void 不能重复发布() throws Exception {
        mvc.perform(post("/api/teacher/assignments/501/publish")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
            .andExpect(status().isOk());

        mvc.perform(post("/api/teacher/assignments/501/publish")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_STATUS_TRANSITION_INVALID"));
    }

    @Test
    void 截止时间不能早于当前时间() throws Exception {
        mvc.perform(post("/api/teacher/assignments/501/publish")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"dueAt\":\"2000-01-01T00:00:00Z\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_DUE_AT_INVALID"));
    }

    @Test
    void 不能发布其他教师的作业() throws Exception {
        mvc.perform(post("/api/teacher/assignments/503/publish")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_NOT_FOUND"));
    }

    @Test
    void 学生令牌不能发布作业() throws Exception {
        String studentToken = "Bearer " + jwtService.issueStudent(3, 1001, false);

        mvc.perform(post("/api/teacher/assignments/501/publish")
                .header("Authorization", studentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
            .andExpect(status().isForbidden());

        // 旧前缀没有角色规则，靠服务层的 CurrentActor 拦住学生令牌。
        mvc.perform(post("/api/assignments/501/publish")
                .header("Authorization", studentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("AUTH_TEACHER_REQUIRED"));
    }

    private String bearerFor(long teacherId) {
        return "Bearer " + jwtService.issue(teacherId, teacherId, "TEACHER");
    }
}
