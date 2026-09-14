package com.homework.analysis.integration;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.testing.TestDatabaseCleaner;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 在一次性 mysql:8.4 容器上走通完整教学闭环，并验证 MySQL 与 H2 之间
 * 可能存在方言差异的三处：唯一约束、毫秒时间精度、JSON 文本保真。
 *
 * <p>只在 {@code mvn verify -Pmysql-it} 下执行。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysql-it")
class MySqlCoreWorkflowIT {

    private static final String EXCEL_MIME =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String QUESTION_CODE = "IT-Q1";

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seedTeacher() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'a', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲')");
    }

    @Test
    void 教学闭环在真实MySQL上跑通且画像只包含已确认结果() throws Exception {
        long classId = createClass("IT-7-1", "七年级一班");
        createStudent(classId);
        long knowledgePointId = createKnowledgePoint();
        long questionId = createQuestion(knowledgePointId, "[\"2\"]");
        long assignmentId = createAssignment(classId, "第一单元作业", questionId);
        importAnswers(assignmentId, "2");

        mvc.perform(post("/api/grading/assignments/" + assignmentId + "/run").header("Authorization", bearer()))
            .andExpect(status().isOk());

        long resultId = firstReviewResultId(assignmentId);

        // 教师尚未确认，画像不得包含这条结果
        mvc.perform(get("/api/analytics/classes/" + classId + "/mastery")
                .param("assignmentId", String.valueOf(assignmentId))
                .header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(post("/api/grading/results/" + resultId + "/review")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"ACCEPT\",\"feedback\":\"过程完整\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.finalScore").value(5));

        // 确认之后，画像才纳入这条结果，且满分口径与题目总分一致
        mvc.perform(get("/api/analytics/classes/" + classId + "/mastery")
                .param("assignmentId", String.valueOf(assignmentId))
                .header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].earnedScore").value(5))
            .andExpect(jsonPath("$.data[0].possibleScore").value(5))
            .andExpect(jsonPath("$.data[0].answerCount").value(1));
    }

    @Test
    void 毫秒时间精度在MySQL上完整保留() {
        Instant precise = Instant.parse("2026-03-04T05:06:07.123Z");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name, created_at, updated_at)"
            + " values (?, ?, ?, ?, ?, ?)",
            900L, 11L, "IT-MILLIS", "毫秒验证班", Timestamp.from(precise), Timestamp.from(precise));

        Instant readBack = jdbc.queryForObject("select created_at from school_class where id = 900",
            (rs, rowNum) -> rs.getTimestamp("created_at").toInstant());

        assertThat(readBack).isEqualTo(precise);
    }

    @Test
    void 班级编码唯一约束在MySQL上生效() {
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (901, 11, 'IT-DUP', '一班')");

        assertThatThrownBy(() -> jdbc.update(
                "insert into school_class(id, teacher_id, class_code, name) values (902, 11, 'IT-DUP', '二班')"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void JSON文本在MySQL上原样往返且可解析() throws Exception {
        long knowledgePointId = createKnowledgePoint();
        long questionId = createQuestion(knowledgePointId, "[\"x=2\",\"中文答案\"]");

        String stored = jdbc.queryForObject(
            "select accepted_answers from question where id = " + questionId, String.class);

        // 以文本形式存储，往返后仍是合法 JSON 且内容保真
        JsonNode parsed = objectMapper.readTree(stored);
        assertThat(parsed.isArray()).isTrue();
        assertThat(parsed.get(0).asText()).isEqualTo("x=2");
        assertThat(parsed.get(1).asText()).isEqualTo("中文答案");
    }

    private long createClass(String classCode, String name) throws Exception {
        return createdId(mvc.perform(post("/api/classes")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"classCode\":\"" + classCode + "\",\"name\":\"" + name
                    + "\",\"grade\":7,\"semester\":\"2026春\"}"))
            .andExpect(status().isOk())
            .andReturn());
    }

    private void createStudent(long classId) throws Exception {
        mvc.perform(post("/api/classes/" + classId + "/students")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentNo\":\"001\",\"name\":\"张三\"}"))
            .andExpect(status().isOk());
    }

    private long createKnowledgePoint() throws Exception {
        return createdId(mvc.perform(post("/api/knowledge-points")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"IT-ALG\",\"name\":\"一元一次方程\",\"grade\":7,\"active\":true}"))
            .andExpect(status().isOk())
            .andReturn());
    }

    private long createQuestion(long knowledgePointId, String acceptedAnswersJson) throws Exception {
        return createdId(mvc.perform(post("/api/questions")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionCode\":\"" + QUESTION_CODE + "\""
                    + ",\"type\":\"FILL_BLANK\""
                    + ",\"content\":\"解方程 $2x+1=5$\""
                    + ",\"standardAnswer\":\"x=2\""
                    + ",\"totalScore\":5"
                    + ",\"primaryKnowledgePointId\":" + knowledgePointId
                    + ",\"secondaryKnowledgePointIds\":[]"
                    + ",\"acceptedAnswers\":" + acceptedAnswersJson
                    + ",\"rubricItems\":[]}"))
            .andExpect(status().isOk())
            .andReturn());
    }

    private long createAssignment(long classId, String title, long questionId) throws Exception {
        return createdId(mvc.perform(post("/api/assignments")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":" + classId + ",\"title\":\"" + title
                    + "\",\"questionIds\":[" + questionId + "]}"))
            .andExpect(status().isOk())
            .andReturn());
    }

    private void importAnswers(long assignmentId, String answer) throws Exception {
        var file = new MockMultipartFile("file", "answers.xlsx", EXCEL_MIME,
            workbook("001", QUESTION_CODE, answer));
        mvc.perform(multipart("/api/assignments/" + assignmentId + "/answers/import")
                .file(file)
                .header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.importedRows").value(1))
            .andExpect(jsonPath("$.data.errors.length()").value(0));
    }

    private long firstReviewResultId(long assignmentId) throws Exception {
        MvcResult queued = mvc.perform(get("/api/grading/assignments/" + assignmentId + "/review-queue")
                .header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andReturn();
        return objectMapper.readTree(queued.getResponse().getContentAsString())
            .path("data").get(0).path("resultId").asLong();
    }

    /** 从创建类接口的响应信封里取出新记录主键。 */
    private long createdId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").path("id").asLong();
    }

    private byte[] workbook(String studentNo, String questionCode, String answer) throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("answers");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("student_no");
            header.createCell(1).setCellValue("question_code");
            header.createCell(2).setCellValue("answer");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(studentNo);
            row.createCell(1).setCellValue(questionCode);
            row.createCell(2).setCellValue(answer);
            var output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private String bearer() {
        return "Bearer " + jwtService.issue(11, 11, "TEACHER");
    }
}
