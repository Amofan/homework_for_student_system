package com.homework.analysis.classroom.account;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.testing.TestDatabaseCleaner;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentAccountApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seedTwoTeachers() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'a', 'x', 'TEACHER', true), (2, 'b', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲'), (22, 2, '教师乙')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C-101', '七年级一班'), (202, 22, 'C-202', '八年级一班')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (1001, 101, '001', '张三'), (1002, 101, '002', '李四'), (2001, 202, '001', '其他班学生')");
    }

    @Test
    void 开通账号返回一次性明文密码并建立绑定() throws Exception {
        JsonNode account = provision(11, 101, 1001).get(0);

        assertThat(account.get("studentId").asLong()).isEqualTo(1001L);
        assertThat(account.get("name").asText()).isEqualTo("张三");
        assertThat(account.get("newlyProvisioned").asBoolean()).isTrue();
        assertThat(account.get("username").asText()).startsWith("stu_");
        assertThat(account.get("username").asText()).hasSize(16);
        assertThat(account.get("temporaryPassword").asText()).hasSize(16);

        String hash = jdbc.queryForObject("select password_hash from app_user where username = ?",
            String.class, account.get("username").asText());
        assertThat(passwordEncoder.matches(account.get("temporaryPassword").asText(), hash)).isTrue();
        assertThat(hash).doesNotContain(account.get("temporaryPassword").asText());

        jdbc.queryForObject("select user_id from student where id = 1001", Long.class);
        assertThat(jdbc.queryForObject("select account_status from app_user where username = ?",
            String.class, account.get("username").asText())).isEqualTo("PASSWORD_CHANGE_REQUIRED");
    }

    @Test
    void 名册行反映账号状态且不泄露密码() throws Exception {
        provision(11, 101, 1001);

        String body = mvc.perform(get("/api/classes/101/students").header("Authorization", bearerFor(11)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].accountUsername").exists())
            .andExpect(jsonPath("$.data[0].accountStatus").value("PASSWORD_CHANGE_REQUIRED"))
            // 未开通的行整组省略账号字段，前端只靠字段存在与否就能决定按钮文案。
            .andExpect(jsonPath("$.data[1].accountUsername").doesNotExist())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("password_hash").doesNotContain("passwordHash");
    }

    @Test
    void 重复开通是幂等的且不返回任何密码() throws Exception {
        JsonNode first = provision(11, 101, 1001).get(0);
        JsonNode second = provision(11, 101, 1001).get(0);

        assertThat(second.get("username").asText()).isEqualTo(first.get("username").asText());
        assertThat(second.get("newlyProvisioned").asBoolean()).isFalse();
        assertThat(second.get("temporaryPassword")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from app_user where role = 'STUDENT'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void 不能为其他教师班级的学生开通账号() throws Exception {
        String body = "{\"studentIds\":[2001]}";
        mvc.perform(post("/api/teacher/classes/101/student-accounts/provision")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));

        assertThat(jdbc.queryForObject("select count(*) from app_user where role = 'STUDENT'", Integer.class))
            .isZero();
    }

    @Test
    void 整批开通中只要有一个学生不属于本班就全部回滚() throws Exception {
        String body = "{\"studentIds\":[1001,2001]}";
        mvc.perform(post("/api/teacher/classes/101/student-accounts/provision")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select count(*) from app_user where role = 'STUDENT'", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("select count(*) from student where user_id is not null", Integer.class))
            .isZero();
    }

    @Test
    void 重置密码换发新临时密码并回到强制改密状态() throws Exception {
        JsonNode account = provision(11, 101, 1001).get(0);
        String username = account.get("username").asText();
        String original = account.get("temporaryPassword").asText();
        completeFirstPasswordChange(username, original);

        // 重置接口返回单个凭据对象，不是数组。
        JsonNode reset = resetPassword(11, 1001);
        String resetTemporary = reset.get("temporaryPassword").asText();

        assertThat(reset.get("newlyProvisioned").asBoolean()).isFalse();
        assertThat(reset.get("username").asText()).isEqualTo(username);
        assertThat(resetTemporary).isNotEqualTo(original);
        assertThat(jdbc.queryForObject("select account_status from app_user where username = ?",
            String.class, username)).isEqualTo("PASSWORD_CHANGE_REQUIRED");

        loginExpectingUnauthorized(username, "NewPass!2345");
        login(username, resetTemporary);
    }

    @Test
    void 未开通账号的学生不能重置密码() throws Exception {
        mvc.perform(post("/api/teacher/students/1001/password/reset").header("Authorization", bearerFor(11)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("STUDENT_ACCOUNT_NOT_PROVISIONED"));
    }

    @Test
    void 不能重置其他教师学生的密码() throws Exception {
        provision(11, 101, 1001);

        mvc.perform(post("/api/teacher/students/1001/password/reset").header("Authorization", bearerFor(22)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
    }

    @Test
    void 学生首次改密后旧密码失效并换发不带改密标志的令牌() throws Exception {
        JsonNode account = provision(11, 101, 1001).get(0);
        String username = account.get("username").asText();
        String temporary = account.get("temporaryPassword").asText();

        String oldToken = login(username, temporary);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldToken))
            .andExpect(jsonPath("$.data.passwordChangeRequired").value(true))
            .andExpect(jsonPath("$.data.role").value("STUDENT"));

        String newToken = changePassword(oldToken, temporary, "NewPass!2345");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + newToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.passwordChangeRequired").value(false))
            .andExpect(jsonPath("$.data.studentId").value(1001));

        assertThat(jdbc.queryForObject("select account_status from app_user where username = ?",
            String.class, username)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select password_hash from app_user where username = ?",
            String.class, username)).doesNotContain(temporary);

        loginExpectingUnauthorized(username, temporary);
        login(username, "NewPass!2345");
    }

    @Test
    void 改密拒绝复用当前密码与过弱的新密码() throws Exception {
        JsonNode account = provision(11, 101, 1001).get(0);
        String token = login(account.get("username").asText(), account.get("temporaryPassword").asText());

        mvc.perform(post("/api/auth/password/change")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + account.get("temporaryPassword").asText()
                    + "\",\"newPassword\":\"" + account.get("temporaryPassword").asText() + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("PASSWORD_REUSED"));

        mvc.perform(post("/api/auth/password/change")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + account.get("temporaryPassword").asText()
                    + "\",\"newPassword\":\"alllowercaseonly\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("PASSWORD_TOO_WEAK"));

        mvc.perform(post("/api/auth/password/change")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"wrong-current\",\"newPassword\":\"NewPass!2345\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void 删除学生同步停用账号并阻止登录() throws Exception {
        JsonNode account = provision(11, 101, 1001).get(0);
        String username = account.get("username").asText();
        String temporary = account.get("temporaryPassword").asText();

        mvc.perform(delete("/api/students/1001").header("Authorization", bearerFor(11)))
            .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select account_status from app_user where username = ?",
            String.class, username)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("select enabled from app_user where username = ?",
            Boolean.class, username)).isFalse();
        loginExpectingUnauthorized(username, temporary);
    }

    @Test
    void 凭据导出禁止缓存且只保留本次明文行() throws Exception {
        JsonNode account = provision(11, 101, 1001).get(0);
        String username = account.get("username").asText();
        String temporary = account.get("temporaryPassword").asText();

        String body = "{\"classId\":101,\"students\":["
            + "{\"studentNo\":\"001\",\"name\":\"张三\",\"username\":\"" + username
            + "\",\"temporaryPassword\":\"" + temporary + "\"},"
            + "{\"studentNo\":\"002\",\"name\":\"李四\",\"username\":\"stu_bbbb\",\"temporaryPassword\":null}]}";

        byte[] bytes = mvc.perform(post("/api/teacher/student-accounts/credentials")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Content-Disposition",
                containsString("attachment; filename=student-credentials-101.xlsx")))
            .andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo(temporary);
        }
    }

    @Test
    void 不能导出其他教师班级的凭据() throws Exception {
        String body = "{\"classId\":202,\"students\":[{\"studentNo\":\"001\",\"name\":\"张三\","
            + "\"username\":\"stu_aaaa\",\"temporaryPassword\":\"Temp1234Abcd5678\"}]}";

        mvc.perform(post("/api/teacher/student-accounts/credentials")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("CLASS_NOT_FOUND"));
    }

    @Test
    void 学生令牌不能调用教师端开通接口() throws Exception {
        JsonNode account = provision(11, 101, 1001).get(0);
        String studentToken = login(account.get("username").asText(), account.get("temporaryPassword").asText());

        mvc.perform(post("/api/teacher/classes/101/student-accounts/provision")
                .header("Authorization", "Bearer " + studentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentIds\":[1002]}"))
            .andExpect(status().isForbidden());
    }

    private JsonNode provision(long teacherId, long classId, long... studentIds) throws Exception {
        String body = "{\"studentIds\":["
            + Arrays.stream(studentIds).mapToObj(Long::toString).collect(Collectors.joining(",")) + "]}";
        String response = mvc.perform(post("/api/teacher/classes/" + classId + "/student-accounts/provision")
                .header("Authorization", bearerFor(teacherId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).get("data");
    }

    private JsonNode resetPassword(long teacherId, long studentId) throws Exception {
        String response = mvc.perform(post("/api/teacher/students/" + studentId + "/password/reset")
                .header("Authorization", bearerFor(teacherId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).get("data");
    }

    private String changePassword(String token, String currentPassword, String newPassword) throws Exception {
        String response = mvc.perform(post("/api/auth/password/change")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + currentPassword
                    + "\",\"newPassword\":\"" + newPassword + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).get("data").get("accessToken").asText();
    }

    private void completeFirstPasswordChange(String username, String temporary) throws Exception {
        changePassword(login(username, temporary), temporary, "NewPass!2345");
    }

    private String login(String username, String password) throws Exception {
        String response = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).get("data").get("accessToken").asText();
    }

    private void loginExpectingUnauthorized(String username, String password) throws Exception {
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    private String bearerFor(long teacherId) {
        return "Bearer " + jwtService.issue(teacherId, teacherId, "TEACHER");
    }
}
