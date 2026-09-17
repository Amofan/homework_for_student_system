package com.homework.analysis.auth;

import com.homework.analysis.testing.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiTest {
    private static final String TEACHER_PASSWORD = "TeacherA!234";
    private static final String STUDENT_PASSWORD = "StuTemp!2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedAccounts() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, ?, ?, 'TEACHER', true)",
            "teacher-a", passwordEncoder.encode(TEACHER_PASSWORD));
        jdbc.update("insert into teacher(id, user_id, display_name, school_name) values (11, 1, '数学教师', '城南实验中学')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C-101', '七年级一班')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (1001, 101, '070101', '张晨')");
        jdbc.update("""
            insert into app_user(id, username, password_hash, role, enabled, account_status)
            values (2, ?, ?, 'STUDENT', true, 'PASSWORD_CHANGE_REQUIRED')
            """, "stu_abcdefghijkl", passwordEncoder.encode(STUDENT_PASSWORD));
        jdbc.update("update student set user_id = 2 where id = 1001");
    }

    @Test
    void validCredentialsReturnAccessTokenAndCurrentTeacher() throws Exception {
        String response = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"teacher-a\",\"password\":\"" + TEACHER_PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
            .andReturn().getResponse().getContentAsString();

        String token = extractToken(response);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.role").value("TEACHER"))
            .andExpect(jsonPath("$.data.teacherId").value(11))
            .andExpect(jsonPath("$.data.displayName").value("数学教师"))
            .andExpect(jsonPath("$.data.schoolName").value("城南实验中学"))
            .andExpect(jsonPath("$.data.studentId").doesNotExist())
            .andExpect(jsonPath("$.data.passwordChangeRequired").value(false));
    }

    @Test
    void badPasswordReturnsStableUnauthorizedCode() throws Exception {
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"teacher-a\",\"password\":\"wrong-password\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void 学生登录后me返回学号与首次改密标志() throws Exception {
        String token = login("stu_abcdefghijkl", STUDENT_PASSWORD);

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.role").value("STUDENT"))
            .andExpect(jsonPath("$.data.studentId").value(1001))
            .andExpect(jsonPath("$.data.displayName").value("张晨"))
            .andExpect(jsonPath("$.data.passwordChangeRequired").value(true))
            .andExpect(jsonPath("$.data.teacherId").doesNotExist())
            .andExpect(jsonPath("$.data.schoolName").doesNotExist());
    }

    @Test
    void 学生令牌访问教师专区返回403() throws Exception {
        String token = login("stu_abcdefghijkl", STUDENT_PASSWORD);

        mvc.perform(get("/api/teacher/classes").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void 教师令牌访问学生专区返回403() throws Exception {
        String token = login("teacher-a", TEACHER_PASSWORD);

        mvc.perform(get("/api/student/assignments").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    /**
     * 角色与已开通主体不一致的账号不能登录。
     *
     * <p>这类账号只可能来自人工改库或迁移中间态。放行会签出一枚没有 teacherId 的
     * “教师令牌”，后续所有教师接口都会以 500 或空数据收场，不如在登录处判为凭据错误。
     */
    @Test
    void 角色缺少对应主体档案的账号无法登录() throws Exception {
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (3, ?, ?, 'TEACHER', true)",
            "teacher-orphan", passwordEncoder.encode(TEACHER_PASSWORD));

        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"teacher-orphan\",\"password\":\"" + TEACHER_PASSWORD + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void 停用的学生账号无法登录() throws Exception {
        jdbc.update("update app_user set account_status = 'DISABLED' where id = 2");

        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"stu_abcdefghijkl\",\"password\":\"" + STUDENT_PASSWORD + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    private String login(String username, String password) throws Exception {
        String response = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return extractToken(response);
    }

    private static String extractToken(String response) {
        return response.replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }
}
