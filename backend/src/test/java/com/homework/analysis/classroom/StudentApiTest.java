package com.homework.analysis.classroom;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'a', 'x', 'TEACHER', true), (2, 'b', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲'), (22, 2, '教师乙')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, '2026-7-1', '七年级一班'), (202, 22, '2026-8-1', '八年级一班')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (1001, 101, '001', '张三'), (2001, 202, '001', '李四')");
    }

    @Test
    void teacherCanManageOwnedStudents() throws Exception {
        mvc.perform(post("/api/classes/101/students")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentNo\":\"002\",\"name\":\"王五\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("王五"));

        mvc.perform(get("/api/classes/101/students").header("Authorization", bearerFor(11)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));

        mvc.perform(put("/api/students/1001")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentNo\":\"001\",\"name\":\"张三同学\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("张三同学"));

        mvc.perform(delete("/api/students/1001").header("Authorization", bearerFor(11)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/classes/101/students").header("Authorization", bearerFor(11)))
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void foreignStudentIsHiddenAsNotFound() throws Exception {
        mvc.perform(put("/api/students/2001")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentNo\":\"001\",\"name\":\"越权修改\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
    }

    private String bearerFor(long teacherId) {
        return "Bearer " + jwtService.issue(teacherId, teacherId, "TEACHER");
    }
}
