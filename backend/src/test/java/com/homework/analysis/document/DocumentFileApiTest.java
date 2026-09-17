package com.homework.analysis.document;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.testing.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 私有文件读取的归属校验。
 *
 * <p>归属判断全部落在 SQL 里，本类要证明的是"不满足条件时确实读不到"：
 * 别的教师的文件、同班同学的文件、教师的试卷、以及没有任何文档引用的孤儿文件。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentFileApiTest {

    private static final Path STORAGE_ROOT = createStorageRoot();

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local-root", STORAGE_ROOT::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired StoredFileService files;

    private long teacherPaperFileId;
    private long firstStudentAnswerFileId;

    @BeforeEach
    void seed() throws IOException {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("""
            insert into app_user(id, username, password_hash, role, enabled) values
              (1, 'a', 'x', 'TEACHER', true),
              (2, 'b', 'x', 'TEACHER', true),
              (3, 'stu_one', 'x', 'STUDENT', true),
              (4, 'stu_two', 'x', 'STUDENT', true)
            """);
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲'), (22, 2, '教师乙')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C-101', '七年级一班')");
        jdbc.update("""
            insert into student(id, class_id, student_no, name, user_id) values
              (1001, 101, '001', '张三', 3),
              (1002, 101, '002', '李四', 4)
            """);
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active) values (301, 11, 'ALG', '代数', 7, true)");
        jdbc.update("insert into question(id, teacher_id, question_code, type, content, total_score, primary_knowledge_point_id, accepted_answers) values (401, 11, 'Q-001', 'FILL_BLANK', '1+1', 5, 301, '[\"2\"]')");
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status, published_at) values (501, 11, 101, '作业', 'PUBLISHED', current_timestamp(3))");

        teacherPaperFileId = files.storeTeacherFile(11, "试卷.png", "image/png", png(32, 32)).id();
        firstStudentAnswerFileId = files.storeStudentFile(1001, "我的答卷.png", "image/png", png(40, 40)).id();
        long secondStudentAnswerFileId = files.storeStudentFile(1002, "同学的答卷.png", "image/png", png(48, 48)).id();
        long orphanFileId = files.storeTeacherFile(11, "无人引用.png", "image/png", png(16, 16)).id();

        jdbc.update("""
            insert into document_upload(id, document_kind, teacher_id, assignment_id, original_file_id, status)
            values (6501, 'EXAM_PAPER', 11, 501, ?, 'PENDING'),
                   (6502, 'STUDENT_SUBMISSION', 11, 501, ?, 'PENDING'),
                   (6503, 'STUDENT_SUBMISSION', 11, 501, ?, 'PENDING')
            """, teacherPaperFileId, firstStudentAnswerFileId, secondStudentAnswerFileId);
        jdbc.update("update document_upload set student_id = 1001 where id = 6502");
        jdbc.update("update document_upload set student_id = 1002 where id = 6503");
        // 孤儿文件刻意不插 document_upload：即使它的 teacher_id 是对的，也不该被读到。
        assertThat(orphanFileId).isPositive();
    }

    @Test
    void 教师能读到本文档引用的文件并带上防护响应头() throws Exception {
        byte[] body = mvc.perform(get("/api/teacher/files/" + teacherPaperFileId)
                .header("Authorization", teacherToken(11)))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
            .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEqualTo(png(32, 32));
    }

    @Test
    void 教师读不到别的教师的文件() throws Exception {
        mvc.perform(get("/api/teacher/files/" + teacherPaperFileId).header("Authorization", teacherToken(22)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("FILE_NOT_FOUND"));
    }

    @Test
    void 学生能读到自己的答卷文件() throws Exception {
        byte[] body = mvc.perform(get("/api/student/files/" + firstStudentAnswerFileId)
                .header("Authorization", studentToken(1001)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEqualTo(png(40, 40));
    }

    @Test
    void 学生读不到同班同学的文件与教师的试卷() throws Exception {
        mvc.perform(get("/api/student/files/" + firstStudentAnswerFileId)
                .header("Authorization", studentToken(1002)))
            .andExpect(status().isNotFound());

        mvc.perform(get("/api/student/files/" + teacherPaperFileId)
                .header("Authorization", studentToken(1001)))
            .andExpect(status().isNotFound());
    }

    /**
     * 没有任何文档引用的文件对上传者本人也不可读。
     *
     * <p>否则"文件 id 猜对了就能下载"会绕过文档这一层的归属校验——而 id 是连续自增的，
     * 猜中的成本极低。
     */
    @Test
    void 孤儿文件即使归属正确也不可读() throws Exception {
        Long orphanId = jdbc.queryForObject(
            "select id from stored_file where original_name = '无人引用.png'", Long.class);

        mvc.perform(get("/api/teacher/files/" + orphanId).header("Authorization", teacherToken(11)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("FILE_NOT_FOUND"));
    }

    @Test
    void 已标记删除的文件不可读() throws Exception {
        jdbc.update("update stored_file set status = 'DELETED', deleted_at = current_timestamp(3) where id = ?",
            teacherPaperFileId);

        mvc.perform(get("/api/teacher/files/" + teacherPaperFileId).header("Authorization", teacherToken(11)))
            .andExpect(status().isNotFound());
    }

    @Test
    void 未登录读取文件被拒绝() throws Exception {
        mvc.perform(get("/api/teacher/files/" + teacherPaperFileId))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    /** 学生令牌打教师文件接口由安全规则直接拦住，不进入业务层。 */
    @Test
    void 学生令牌不能访问教师文件接口() throws Exception {
        mvc.perform(get("/api/teacher/files/" + teacherPaperFileId)
                .header("Authorization", studentToken(1001)))
            .andExpect(status().isForbidden());
    }

    /**
     * 原始文件名只作为展示元数据。
     *
     * <p>带 {@code ../} 的名字在入库时被洗成 basename，响应头里也不会出现换行——
     * 响应头注入的输入源头是用户上传的文件名，这条路径必须自己再挡一次。
     */
    @Test
    void 文件名在响应头里被清洗() throws Exception {
        long fileId = files.storeTeacherFile(11, "../../evil\"name.png", "image/png", png(8, 8)).id();
        jdbc.update("""
            insert into document_upload(id, document_kind, teacher_id, assignment_id, original_file_id, status)
            values (6599, 'EXAM_PAPER', 11, 501, ?, 'PENDING')
            """, fileId);

        String disposition = mvc.perform(get("/api/teacher/files/" + fileId)
                .header("Authorization", teacherToken(11)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);

        assertThat(disposition).isEqualTo("inline; filename=\"evil_name.png\"");
        assertThat(disposition).doesNotContain("..").doesNotContain("\r").doesNotContain("\n");
    }

    /** 校验失败的请求不能留下半截状态：既没有对象，也没有元数据行。 */
    @Test
    void 改名的可执行文件被拒绝且不留下任何记录() {
        byte[] executable = "MZ\u0090\u0000".getBytes(StandardCharsets.ISO_8859_1);
        Integer before = jdbc.queryForObject("select count(*) from stored_file", Integer.class);

        assertThatThrownBy(() -> files.storeTeacherFile(11, "payload.pdf", "application/pdf", executable))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("FILE_CONTENT_MISMATCH");

        assertThat(jdbc.queryForObject("select count(*) from stored_file", Integer.class)).isEqualTo(before);
    }

    @Test
    void 学生上传的文件归属到其班级的教师() throws IOException {
        StoredFileService.StoredFileView stored = files.storeStudentFile(1001, "答卷.png", "image/png", png(24, 24));

        assertThat(jdbc.queryForObject("select teacher_id from stored_file where id = ?", Long.class, stored.id()))
            .isEqualTo(11L);
        assertThat(jdbc.queryForObject("select student_id from stored_file where id = ?", Long.class, stored.id()))
            .isEqualTo(1001L);
        assertThat(stored.sha256()).hasSize(64);
    }

    private String teacherToken(long teacherId) {
        return "Bearer " + jwtService.issue(teacherId, teacherId, "TEACHER");
    }

    private String studentToken(long studentId) {
        long userId = jdbc.queryForObject("select user_id from student where id = ?", Long.class, studentId);
        return "Bearer " + jwtService.issueStudent(userId, studentId, false);
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("homework-document-files-");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试用存储目录", exception);
        }
    }
}
