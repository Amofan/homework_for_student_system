package com.homework.analysis.document;

import com.homework.analysis.testing.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保留策略的执行结果。
 *
 * <p>时间通过"把行的 created_at / deleted_at 写成过去"来构造，而不是替换 Clock：
 * 被清理逻辑真正依赖的是 SQL 里的时间比较，改 Clock 反而验证不到那条 SQL。
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentCleanupJobTest {

    private static final Path STORAGE_ROOT = createStorageRoot();
    private static final String SHA = "a".repeat(64);

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local-root", STORAGE_ROOT::toString);
    }

    @Autowired DocumentCleanupJob job;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() throws IOException {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'a', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C-101', '七年级一班')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (1001, 101, '001', '张三')");
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status) values (501, 11, 101, '作业', 'DRAFT')");
    }

    @Test
    void 未完成上传满七天后删除对象但保留审计行() throws IOException {
        String key = "teachers/11/uploads/a.png";
        writeObject(key, "content-a");
        long fileId = insertFile(key, SHA, "ACTIVE", 8, null);
        insertDocument(fileId, "PENDING");

        DocumentCleanupJob.CleanupSummary summary = job.runOnce();

        assertThat(summary.attempted()).isEqualTo(1);
        assertThat(summary.purged()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
        assertThat(objectOf(key)).doesNotExist();

        // 行还在，sha256 还在：文件拿不回来，但"当时上传的是哪一份内容"仍可回答。
        assertThat(jdbc.queryForObject("select status from stored_file where id = ?", String.class, fileId))
            .isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select sha256 from stored_file where id = ?", String.class, fileId))
            .isEqualTo(SHA);
        assertThat(jdbc.queryForObject("select purged_at from stored_file where id = ?", Object.class, fileId))
            .isNotNull();
    }

    @Test
    void 未满七天的上传不动() throws IOException {
        String key = "teachers/11/uploads/b.png";
        writeObject(key, "content-b");
        insertDocument(insertFile(key, SHA, "ACTIVE", 3, null), "PENDING");

        assertThat(job.runOnce().attempted()).isZero();
        assertThat(objectOf(key)).exists();
    }

    /** 已确认的文档是教师核对过的正式材料，不在任何清理规则范围内。 */
    @Test
    void 已确认文档的文件不被清理() throws IOException {
        String key = "teachers/11/uploads/c.png";
        writeObject(key, "content-c");
        insertDocument(insertFile(key, SHA, "ACTIVE", 60, null), "CONFIRMED");

        assertThat(job.runOnce().attempted()).isZero();
        assertThat(objectOf(key)).exists();
    }

    @Test
    void 已标记删除的文件在宽限期后才清理() throws IOException {
        String expired = "teachers/11/uploads/d.png";
        String withinGrace = "teachers/11/uploads/e.png";
        writeObject(expired, "content-d");
        writeObject(withinGrace, "content-e");
        long expiredId = insertFile(expired, SHA, "DELETED", 40, 31);
        insertFile(withinGrace, SHA, "DELETED", 40, 5);

        DocumentCleanupJob.CleanupSummary summary = job.runOnce();

        assertThat(summary.attempted()).isEqualTo(1);
        assertThat(objectOf(expired)).doesNotExist();
        assertThat(objectOf(withinGrace)).exists();
        assertThat(jdbc.queryForObject("select purged_at from stored_file where id = ?", Object.class, expiredId))
            .isNotNull();
    }

    /**
     * 学生交上来的答卷原图不在清理范围内，哪怕识别还没跑完、或者识别失败了。
     *
     * <p>学生上传建的 {@code document_upload} 同样停在 {@code PENDING}（识别排队中）或
     * {@code FAILED}（照片太糊），而这两个状态正是"废弃上传"的判据。少了版本这一层的判断，
     * 一份交上来放了一周的答卷会被清理任务把原图删掉 —— 教师那边页面打不开，
     * 申诉时连学生交的是什么都说不出。
     */
    @Test
    void 已提交答卷的原图不会被当成废弃上传清掉() throws IOException {
        String key = "students/1001/submissions/a.png";
        writeObject(key, "answer-a");
        // 识别还在排队：文档状态与"学生传完就走"的废弃上传一模一样，区别只在版本已提交。
        long fileId = insertSubmissionUpload(key, "PROCESSING", "PENDING");

        assertThat(job.runOnce().attempted()).isZero();
        assertThat(objectOf(key)).exists();
        assertThat(jdbc.queryForObject("select status from stored_file where id = ?", String.class, fileId))
            .isEqualTo("ACTIVE");
    }

    /** 识别失败也是"已经交了"：删掉原图，教师连手工批改都无从下手。 */
    @Test
    void 识别失败的答卷原图也不会被清掉() throws IOException {
        String key = "students/1001/submissions/b.png";
        writeObject(key, "answer-b");
        long fileId = insertSubmissionUpload(key, "NEEDS_REVIEW", "FAILED");

        assertThat(job.runOnce().attempted()).isZero();
        assertThat(objectOf(key)).exists();
        assertThat(jdbc.queryForObject("select status from document_upload where original_file_id = ?",
            String.class, fileId)).isEqualTo("FAILED");
    }

    /**
     * 还在编辑中的草稿仍然算废弃上传：学生传完就走、不再回来，存储不该无限留着。
     *
     * <p>清理之后这一版会被 {@code SUBMISSION_PAGE_FILE_MISSING} 挡住并提示重新上传 ——
     * 是能看见的失败，而不是教师打开试卷时才发现缺页。
     */
    @Test
    void 没提交的草稿上传仍按废弃上传清理() throws IOException {
        String key = "students/1001/submissions/c.png";
        writeObject(key, "draft-c");
        insertSubmissionUpload(key, "DRAFT", "PENDING");

        assertThat(job.runOnce().purged()).isEqualTo(1);
        assertThat(objectOf(key)).doesNotExist();
    }

    /**
     * 删除对象失败时不能先改元数据。
     *
     * <p>顺序反过来的话，这一行状态已经变成"已清理"，下一轮查询再也找不到它，
     * 而对象还留在存储里，永远不会有人来删。
     *
     * <p>这里用一个非空目录冒充对象路径：{@code Files.deleteIfExists} 删不掉非空目录，
     * 于是产生一次真实（而非 mock 出来）的存储删除失败。
     */
    @Test
    void 删除失败时保留行状态待下轮重试() throws IOException {
        String key = "teachers/11/uploads/f.png";
        Path blocked = objectOf(key);
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("occupied.txt"), "占位", StandardCharsets.UTF_8);
        long fileId = insertFile(key, SHA, "ACTIVE", 30, null);
        insertDocument(fileId, "FAILED");

        DocumentCleanupJob.CleanupSummary summary = job.runOnce();

        assertThat(summary.attempted()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.purged()).isZero();
        // 行状态没变：下一轮还会被查出来，继续重试。
        assertThat(jdbc.queryForObject("select status from stored_file where id = ?", String.class, fileId))
            .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select purged_at from stored_file where id = ?", Object.class, fileId))
            .isNull();
    }

    private Path objectOf(String key) {
        return STORAGE_ROOT.resolve(key);
    }

    private void writeObject(String key, String content) throws IOException {
        Path target = objectOf(key);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private long insertFile(String key, String sha256, String status, int createdDaysAgo, Integer deletedDaysAgo) {
        Long id = jdbc.queryForObject("select coalesce(max(id), 6000) + 1 from stored_file", Long.class);
        jdbc.update("""
            insert into stored_file(id, teacher_id, storage_key, original_name, mime_type, size_bytes,
                                    sha256, status, created_at, deleted_at)
            values (?, 11, ?, 'file.png', 'image/png', 9, ?, ?, ?, ?)
            """, id, key, sha256, status, daysAgo(createdDaysAgo),
            deletedDaysAgo == null ? null : daysAgo(deletedDaysAgo));
        return id;
    }

    private void insertDocument(long fileId, String status) {
        Long id = jdbc.queryForObject("select coalesce(max(id), 6500) + 1 from document_upload", Long.class);
        jdbc.update("""
            insert into document_upload(id, document_kind, teacher_id, assignment_id, original_file_id, status)
            values (?, 'EXAM_PAPER', 11, 501, ?, ?)
            """, id, fileId, status);
    }

    /**
     * 一次学生上传：文件是 30 天前建的（远超保留期），文档状态由调用方指定。
     *
     * <p>与教师上传的差别只有两处 —— {@code document_kind} 是学生答卷，以及挂在一个提交版本上。
     * 判断"这份上传还算不算废弃"看的正是后者。
     */
    private long insertSubmissionUpload(String key, String versionStatus, String documentStatus) {
        jdbc.update("insert into submission(id, assignment_id, student_id, status) values (601, 501, 1001, 'IMPORTED')");
        jdbc.update("""
            insert into submission_version(id, submission_id, assignment_id, student_id,
                                          version_no, status, is_current)
            values (701, 601, 501, 1001, 1, ?, ?)
            """, versionStatus, !"DRAFT".equals(versionStatus) && !"UPLOADED".equals(versionStatus));
        long fileId = insertFile(key, SHA, "ACTIVE", 30, null);
        jdbc.update("""
            insert into document_upload(document_kind, teacher_id, assignment_id, student_id,
                                        submission_version_id, original_file_id, status)
            values ('STUDENT_SUBMISSION', 11, 501, 1001, 701, ?, ?)
            """, fileId, documentStatus);
        return fileId;
    }

    private static Timestamp daysAgo(int days) {
        return Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("homework-cleanup-storage-");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试用存储目录", exception);
        }
    }
}
