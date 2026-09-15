package com.homework.analysis.exercise;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.question.QuestionDifficulty;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExerciseApiTest {
    private static final String DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled)"
            + " values (1,'a','x','TEACHER',true),(2,'b','x','TEACHER',true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11,1,'教师甲'),(22,2,'教师乙')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name)"
            + " values (101,11,'C-1','七年级一班'),(102,11,'C-3','七年级二班'),(202,22,'C-2','八年级一班')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (1001,101,'001','张三')");
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status)"
            + " values (501,11,101,'第一单元作业','DRAFT'),(502,11,102,'第二单元作业','DRAFT')");
        jdbc.update("insert into submission(id, assignment_id, student_id) values (9001,501,1001)");
    }

    @Test
    void 生成查看确认导出走通完整接口() throws Exception {
        seedThreeTiers();
        long exerciseId = generate();

        mvc.perform(get("/api/exercises").param("classId", "101").header("Authorization", bearer(11)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(exerciseId))
            .andExpect(jsonPath("$.data[0].status").value("DRAFT"))
            .andExpect(jsonPath("$.data[0].title").value("七年级一班 · 第一单元作业 分层练习"))
            .andExpect(jsonPath("$.data[0].notices.length()").value(0));

        mvc.perform(get("/api/exercises/" + exerciseId).header("Authorization", bearer(11)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.className").value("七年级一班"))
            .andExpect(jsonPath("$.data.sourceAssignmentTitle").value("第一单元作业"))
            .andExpect(jsonPath("$.data.items.length()").value(15))
            .andExpect(jsonPath("$.data.items[0].tier").value("FOUNDATION"))
            .andExpect(jsonPath("$.data.items[0].sortOrder").value(1))
            .andExpect(jsonPath("$.data.items[0].knowledgePointName").value("一元一次方程"))
            .andExpect(jsonPath("$.data.items[5].tier").value("CORRECTION"))
            .andExpect(jsonPath("$.data.items[10].tier").value("IMPROVEMENT"));

        mvc.perform(post("/api/exercises/" + exerciseId + "/approve").header("Authorization", bearer(11)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("APPROVED"))
            .andExpect(jsonPath("$.data.approvedAt").isNotEmpty());

        MvcResult exported = mvc.perform(get("/api/exercises/" + exerciseId + "/export.docx")
                .header("Authorization", bearer(11)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", DOCX_MIME))
            .andExpect(header().string("Content-Disposition",
                "attachment; filename=\"exercise-" + exerciseId + ".docx\""))
            // 题库全是子集内的公式，不该出现降级响应头
            .andExpect(header().doesNotExist("X-Formula-Fallback"))
            .andReturn();

        byte[] document = exported.getResponse().getContentAsByteArray();
        assertThat(document).isNotEmpty();
        // docx 本身是 zip 包，开头两字节固定是 PK：用它证明拿到的是文档字节而不是错误 JSON
        assertThat(new String(document, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
    }

    @Test
    void 未确认的练习单导出返回冲突而不是文档() throws Exception {
        seedThreeTiers();
        long exerciseId = generate();

        mvc.perform(get("/api/exercises/" + exerciseId + "/export.docx").header("Authorization", bearer(11)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("EXERCISE_NOT_APPROVED"));
    }

    @Test
    void 题库出现子集外的公式时导出返回降级题号() throws Exception {
        seedThreeTiers();
        long exerciseId = generate();
        mvc.perform(post("/api/exercises/" + exerciseId + "/approve").header("Authorization", bearer(11)))
            .andExpect(status().isOk());
        // 导出时按 question 表读回题干，所以改题库就能让这道题降级
        jdbc.update("update question set content = ? where id = 401", "$\\vec{a}$");

        mvc.perform(get("/api/exercises/" + exerciseId + "/export.docx")
                .header("Authorization", bearer(11)))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Formula-Fallback", "Q-401"));
    }

    /**
     * 业务规则把一张练习单限制在 15 题以内，用数据库造 21 条降级记录会把接口测试
     * 淹在不可能出现的场景里，所以截断规则直接用包级可见的辅助方法验证。
     */
    @Test
    void 降级题号最多保留二十个并追加省略号() {
        List<String> codes = IntStream.rangeClosed(1, 21).mapToObj(index -> "Q-" + index).toList();

        assertThat(ExerciseController.fallbackHeader(codes))
            .isEqualTo("Q-1,Q-2,Q-3,Q-4,Q-5,Q-6,Q-7,Q-8,Q-9,Q-10,"
                + "Q-11,Q-12,Q-13,Q-14,Q-15,Q-16,Q-17,Q-18,Q-19,Q-20,...");
        assertThat(ExerciseController.fallbackHeader(List.of())).isEmpty();
    }

    @Test
    void 已确认的练习单不能重复确认() throws Exception {
        seedThreeTiers();
        long exerciseId = generate();
        mvc.perform(post("/api/exercises/" + exerciseId + "/approve").header("Authorization", bearer(11)))
            .andExpect(status().isOk());

        mvc.perform(post("/api/exercises/" + exerciseId + "/approve").header("Authorization", bearer(11)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("EXERCISE_NOT_DRAFT"));
    }

    @Test
    void 别的教师看不到也改不了我的练习单() throws Exception {
        seedThreeTiers();
        long exerciseId = generate();

        mvc.perform(get("/api/exercises/" + exerciseId).header("Authorization", bearer(22)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("EXERCISE_NOT_FOUND"));
        mvc.perform(post("/api/exercises/" + exerciseId + "/approve").header("Authorization", bearer(22)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("EXERCISE_NOT_FOUND"));
        mvc.perform(get("/api/exercises/" + exerciseId + "/export.docx").header("Authorization", bearer(22)))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/exercises").param("classId", "101").header("Authorization", bearer(22)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("CLASS_NOT_FOUND"));
    }

    @Test
    void 没有已确认结果时生成返回业务错误码() throws Exception {
        seedThreeTiers();
        jdbc.update("delete from grading_result");
        jdbc.update("delete from student_answer");

        mvc.perform(post("/api/exercises")
                .header("Authorization", bearer(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":101,\"sourceAssignmentId\":501}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("EXERCISE_NO_CONFIRMED_RESULTS"));
    }

    @Test
    void 来源作业与班级不匹配时生成返回业务错误码() throws Exception {
        seedThreeTiers();

        mvc.perform(post("/api/exercises")
                .header("Authorization", bearer(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":101,\"sourceAssignmentId\":502}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("EXERCISE_ASSIGNMENT_MISMATCH"));
    }

    @Test
    void 未登录时返回统一错误信封() throws Exception {
        mvc.perform(get("/api/exercises").param("classId", "101"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    private long generate() throws Exception {
        MvcResult result = mvc.perform(post("/api/exercises")
                .header("Authorization", bearer(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":101,\"sourceAssignmentId\":501}"))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").path("id").asLong();
    }

    /** 三个知识点分别落在三个层级，各配 6 道题；每层取 5 道，因此没有"题库不足"提示。 */
    private void seedThreeTiers() {
        long[] knowledgePoints = {301, 302, 303};
        String[] names = {"一元一次方程", "整式乘法", "全等三角形"};
        QuestionDifficulty[] cycle = {
            QuestionDifficulty.ADVANCED, QuestionDifficulty.BASIC, QuestionDifficulty.MEDIUM
        };
        for (int group = 0; group < knowledgePoints.length; group++) {
            jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active)"
                + " values (?,11,?,?,7,true)", knowledgePoints[group], "KP-" + knowledgePoints[group],
                names[group]);
            for (int index = 0; index < 6; index++) {
                long questionId = 401 + group * 10 + index;
                jdbc.update("insert into question(id, teacher_id, question_code, type, content, standard_answer,"
                        + " total_score, difficulty, primary_knowledge_point_id, accepted_answers)"
                        + " values (?,11,?,'FILL_BLANK',?,'标准答案',10,?,?,'[\"标准答案\"]')",
                    questionId, "Q-" + questionId, "题目" + questionId, cycle[index % cycle.length].name(),
                    knowledgePoints[group]);
                jdbc.update("insert into question_knowledge_point(question_id, knowledge_point_id, is_primary)"
                    + " values (?,?,true)", questionId, knowledgePoints[group]);
                jdbc.update("insert into assignment_question(assignment_id, question_id, question_order)"
                    + " values (501,?,?)", questionId, group * 10 + index + 1);
            }
        }
        confirm(401, 5);
        confirm(411, 7);
        confirm(421, 9);
    }

    /** 造一条教师已确认的评分；题目总分固定 10，因此得分即该知识点的掌握度。 */
    private void confirm(long questionId, int earned) {
        jdbc.update("insert into student_answer(id, submission_id, question_id, answer_content)"
            + " values (?,9001,?,'作答')", questionId, questionId);
        jdbc.update("insert into grading_result(id, answer_id, source, suggested_score, confirmed_score,"
                + " error_type, score_details, status) values (?,?,'RULE',?,?,'CORRECT','{}','CONFIRMED')",
            questionId, questionId, earned, earned);
    }

    private String bearer(long teacherId) {
        return "Bearer " + jwtService.issue(teacherId, teacherId, "TEACHER");
    }
}
