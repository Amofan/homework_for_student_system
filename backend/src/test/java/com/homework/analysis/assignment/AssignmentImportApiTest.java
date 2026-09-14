package com.homework.analysis.assignment;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.testing.TestDatabaseCleaner;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssignmentImportApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'a', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, '2026-7-1', '七年级一班')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (1001, 101, '001', '张三')");
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active) values (301, 11, 'ALG', '代数', 7, true)");
        jdbc.update("insert into question(id, teacher_id, question_code, type, content, total_score, primary_knowledge_point_id, accepted_answers) values (401, 11, 'Q-001', 'FILL_BLANK', '1+1', 5, 301, '[\"2\"]')");
        jdbc.update("insert into question_knowledge_point(question_id, knowledge_point_id, is_primary) values (401, 301, true)");
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status) values (501, 11, 101, '第一单元作业', 'DRAFT')");
        jdbc.update("insert into assignment_question(assignment_id, question_id, question_order) values (501, 401, 1)");
    }

    @Test
    void oneUnknownStudentRejectsWholeWorkbookThenValidWorkbookImports() throws Exception {
        var bad = new MockMultipartFile("file", "answers.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            workbook("999", "Q-001", "2"));
        mvc.perform(multipart("/api/assignments/501/answers/import").file(bad)
                .header("Authorization", bearerFor(11)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.importedRows").value(0))
            .andExpect(jsonPath("$.data.errors[0].code").value("STUDENT_NOT_FOUND"));
        assertThat(jdbc.queryForObject("select count(*) from student_answer", Integer.class)).isZero();

        var good = new MockMultipartFile("file", "answers.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            workbook("001", "Q-001", "2"));
        mvc.perform(multipart("/api/assignments/501/answers/import").file(good)
                .header("Authorization", bearerFor(11)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.importedRows").value(1))
            .andExpect(jsonPath("$.data.errors.length()").value(0));
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

    private String bearerFor(long teacherId) {
        return "Bearer " + jwtService.issue(teacherId, teacherId, "TEACHER");
    }
}
