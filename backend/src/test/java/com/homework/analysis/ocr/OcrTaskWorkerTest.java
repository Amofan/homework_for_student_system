package com.homework.analysis.ocr;

import com.homework.analysis.document.StoredFileService;
import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.testing.TestDatabaseCleaner;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * OCR 任务编排：状态流转、退避重试与"绝不写正式数据"。
 *
 * <p>识别引擎被替换成 mock，因此这里既不联网也不下载模型；页面渲染走真实实现，
 * 目的是顺带覆盖"PDF 先拆分再送识别"这条路径。
 */
@SpringBootTest
@ActiveProfiles("test")
class OcrTaskWorkerTest {

    private static final Path STORAGE_ROOT = createStorageRoot();

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local-root", STORAGE_ROOT::toString);
    }

    @MockitoBean OcrProvider provider;

    @Autowired OcrTaskWorker worker;
    @Autowired StoredFileService files;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'a', 'x', 'TEACHER', true), (2, 'b', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲'), (22, 2, '教师乙')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C-101', '七年级一班')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (1001, 101, '001', '张三')");
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status) values (501, 11, 101, '作业', 'DRAFT')");
    }

    @Test
    void 识别成功后任务待校对且不写入任何正式数据() throws IOException {
        long documentId = documentWithTask(png(), "PENDING", 0, null);
        when(provider.analyze(any())).thenReturn(result(1));

        OcrTaskProcessResult outcome = worker.processOne(11, documentId);

        assertThat(outcome.processed()).isTrue();
        assertThat(outcome.status()).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("select status from ocr_task where document_id = ?", String.class, documentId))
            .isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("select engine from ocr_task where document_id = ?", String.class, documentId))
            .isEqualTo("paddleocr");
        assertThat(jdbc.queryForObject("select model_version from ocr_task where document_id = ?", String.class, documentId))
            .isEqualTo("3.7.0");
        assertThat(jdbc.queryForObject("select duration_ms from ocr_task where document_id = ?", Long.class, documentId))
            .isNotNull();
        assertThat(jdbc.queryForObject("select raw_output from ocr_task where document_id = ?", String.class, documentId))
            .contains("p1-r1");
        assertThat(jdbc.queryForObject("select failure_code from ocr_task where document_id = ?", String.class, documentId))
            .isNull();

        assertThat(jdbc.queryForObject("select status from document_upload where id = ?", String.class, documentId))
            .isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("select page_count from document_upload where id = ?", Integer.class, documentId))
            .isEqualTo(1);

        // OCR 结果只是候选数据：正式题目、答案与成绩必须由教师确认后另行写入。
        assertThat(jdbc.queryForObject("select count(*) from question", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from student_answer", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from grading_result", Integer.class)).isZero();
    }

    @Test
    void 服务不可用时进入退避重试且不改文档状态() throws IOException {
        long documentId = documentWithTask(png(), "PENDING", 0, null);
        when(provider.analyze(any())).thenThrow(
            new DomainException("OCR_UNAVAILABLE", "服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE));

        OcrTaskProcessResult outcome = worker.processOne(11, documentId);

        assertThat(outcome.status()).isEqualTo("RETRY_WAIT");
        assertThat(jdbc.queryForObject("select status from ocr_task where document_id = ?", String.class, documentId))
            .isEqualTo("RETRY_WAIT");
        assertThat(jdbc.queryForObject("select attempt_count from ocr_task where document_id = ?", Integer.class, documentId))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("select next_attempt_at from ocr_task where document_id = ?", Object.class, documentId))
            .isNotNull();
        assertThat(jdbc.queryForObject("select failure_code from ocr_task where document_id = ?", String.class, documentId))
            .isEqualTo("OCR_UNAVAILABLE");
        // 文档还停在 PENDING：重试还没结束，教师不该看到"识别失败"。
        assertThat(jdbc.queryForObject("select status from document_upload where id = ?", String.class, documentId))
            .isEqualTo("PENDING");
    }

    @Test
    void 重试次数用尽后标记失败并需要人工介入() throws IOException {
        long documentId = documentWithTask(png(), "RETRY_WAIT", 3, Timestamp.from(Instant.now().minusSeconds(60)));
        when(provider.analyze(any())).thenThrow(
            new DomainException("OCR_UNAVAILABLE", "服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE));

        OcrTaskProcessResult outcome = worker.processOne(11, documentId);

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select attempt_count from ocr_task where document_id = ?", Integer.class, documentId))
            .isEqualTo(4);
        assertThat(jdbc.queryForObject("select next_attempt_at from ocr_task where document_id = ?", Object.class, documentId))
            .isNull();
        assertThat(jdbc.queryForObject("select status from document_upload where id = ?", String.class, documentId))
            .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select failure_reason_code from document_upload where id = ?", String.class, documentId))
            .isEqualTo("OCR_UNAVAILABLE");
    }

    /**
     * 契约不合法不重试。
     *
     * <p>上游返回了不合契约的数据，再问一次还是同样的数据；把它当"偶发故障"重试，
     * 只会把"上游有 bug"伪装成"网络抖动"，拖到人工兜底时才暴露。
     */
    @Test
    void 契约不合法时直接失败不占用退避时间() throws IOException {
        long documentId = documentWithTask(png(), "PENDING", 0, null);
        when(provider.analyze(any())).thenThrow(
            new DomainException("OCR_CONTRACT_INVALID", "结果不符合契约", HttpStatus.BAD_GATEWAY));

        assertThat(worker.processOne(11, documentId).status()).isEqualTo("FAILED");

        assertThat(jdbc.queryForObject("select failure_code from ocr_task where document_id = ?", String.class, documentId))
            .isEqualTo("OCR_CONTRACT_INVALID");
        assertThat(jdbc.queryForObject("select next_attempt_at from ocr_task where document_id = ?", Object.class, documentId))
            .isNull();
    }

    @Test
    void 没有可处理任务时返回空结果() throws IOException {
        OcrTaskProcessResult outcome = worker.processOne(11, insertDocument(png(), "PENDING"));

        assertThat(outcome.processed()).isFalse();
        assertThat(outcome.status()).isEqualTo("EMPTY");
    }

    @Test
    void 未到重试时间的任务不被领取() throws IOException {
        long documentId = documentWithTask(png(), "RETRY_WAIT", 1, Timestamp.from(Instant.now().plusSeconds(600)));

        assertThat(worker.processOne(11, documentId).status()).isEqualTo("EMPTY");
    }

    @Test
    void 不能处理其他教师的文档() throws IOException {
        long documentId = insertDocument(png(), "PENDING");

        assertThatThrownBy(() -> worker.processOne(22, documentId))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("DOCUMENT_NOT_FOUND");
    }

    /** PDF 先按 DPI 拆成页面图再送识别：OCR 与后续裁剪必须基于同一份像素。 */
    @Test
    void PDF先按页渲染再送识别() throws IOException {
        long documentId = documentWithTask(pdf(3), "PENDING", 0, null);
        when(provider.analyze(any())).thenReturn(result(3));

        worker.processOne(11, documentId);

        ArgumentCaptor<OcrRequest> captor = ArgumentCaptor.forClass(OcrRequest.class);
        org.mockito.Mockito.verify(provider).analyze(captor.capture());
        OcrRequest request = captor.getValue();
        assertThat(request.schemaVersion()).isEqualTo("v1");
        assertThat(request.documentKind()).isEqualTo("EXAM_PAPER");
        assertThat(request.pages()).hasSize(3);
        assertThat(request.pages()).extracting(OcrRequest.Page::pageNo).containsExactly(1, 2, 3);
        assertThat(request.pages().getFirst().contentBase64()).isNotBlank();
        assertThat(jdbc.queryForObject("select page_count from document_upload where id = ?", Integer.class, documentId))
            .isEqualTo(3);
    }

    private long documentWithTask(byte[] content, String taskStatus, int attemptCount, Timestamp nextAttempt)
        throws IOException {
        long documentId = insertDocument(content, "PENDING");
        String sha256 = jdbc.queryForObject("select sha256 from stored_file where id = "
            + "(select original_file_id from document_upload where id = ?)", String.class, documentId);
        Long taskId = jdbc.queryForObject("select coalesce(max(id), 8000) + 1 from ocr_task", Long.class);
        jdbc.update("""
            insert into ocr_task(id, document_id, document_kind, storage_sha256, status, attempt_count,
                                 processing_version, next_attempt_at)
            values (?, ?, 'EXAM_PAPER', ?, ?, ?, 1, ?)
            """, taskId, documentId, sha256, taskStatus, attemptCount, nextAttempt);
        return documentId;
    }

    private long insertDocument(byte[] content, String status) throws IOException {
        String name = "paper-" + UUID.randomUUID() + (isPdf(content) ? ".pdf" : ".png");
        String contentType = isPdf(content) ? "application/pdf" : "image/png";
        long fileId = files.storeTeacherFile(11, name, contentType, content).id();
        Long documentId = jdbc.queryForObject("select coalesce(max(id), 6500) + 1 from document_upload", Long.class);
        jdbc.update("""
            insert into document_upload(id, document_kind, teacher_id, assignment_id, original_file_id, status)
            values (?, 'EXAM_PAPER', 11, 501, ?, ?)
            """, documentId, fileId, status);
        return documentId;
    }

    private static boolean isPdf(byte[] content) {
        return content.length > 4 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F';
    }

    private static OcrResult result(int pageCount) {
        List<OcrResult.Page> pages = new java.util.ArrayList<>();
        for (int index = 1; index <= pageCount; index++) {
            pages.add(new OcrResult.Page(index, 2480, 3508, List.of(new OcrRegion(
                "p" + index + "-r1", "QUESTION_TEXT", 0.1, 0.2, 0.7, 0.08, "题干", null, 0.93))));
        }
        return new OcrResult("v1", "paddleocr", "3.7.0", pages);
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] pdf(int pageCount) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pageCount; index++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("homework-ocr-storage-");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试用存储目录", exception);
        }
    }
}
