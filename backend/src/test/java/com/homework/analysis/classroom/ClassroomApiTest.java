package com.homework.analysis.classroom;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClassroomApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;

    @BeforeEach
    void seedTwoTeachersAndClasses() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'a', 'x', 'TEACHER', true), (2, 'b', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲'), (22, 2, '教师乙')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, '2026-7-1', '七年级一班'), (202, 22, '2026-8-1', '八年级一班')");
    }

    @Test
    void teacherListsOnlyOwnedClasses() throws Exception {
        mvc.perform(get("/api/classes").header("Authorization", bearerFor(11)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(101));
    }

    @Test
    void foreignClassIsHiddenAsNotFound() throws Exception {
        mvc.perform(get("/api/classes/202").header("Authorization", bearerFor(11)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("CLASS_NOT_FOUND"));
    }

    @Test
    void classWithAssignmentCannotBeDeleted() throws Exception {
        jdbc.update("insert into assignment(id, teacher_id, class_id, title) values (301, 11, 101, '第一章测试')");

        mvc.perform(delete("/api/classes/101").header("Authorization", bearerFor(11)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CLASS_HAS_ASSIGNMENTS"));
    }

    @Test
    void unauthenticatedRequestReturnsStableJsonEnvelope() throws Exception {
        mvc.perform(get("/api/classes"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    private String bearerFor(long teacherId) {
        return "Bearer " + jwtService.issue(teacherId, teacherId, "TEACHER");
    }
}
