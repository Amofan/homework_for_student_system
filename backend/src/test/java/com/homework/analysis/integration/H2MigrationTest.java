package com.homework.analysis.integration;

import com.homework.analysis.testing.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 在默认测试档（内存数据库 + Flyway）上验证迁移结果。
 *
 * <p>与 {@link MySqlMigrationIT} 的分工：本类不启动容器，负责在每次 {@code mvn test}
 * 中快速发现“迁移没跑通”或“列/约束没落地”；MySQL 方言与信息模式断言由
 * {@code -Pmysql-it} 下的集成测试承担。
 */
@SpringBootTest
@ActiveProfiles("test")
class H2MigrationTest {

    @Autowired JdbcTemplate jdbc;

    @Value("${spring.datasource.url}")
    String datasourceUrl;

    @BeforeEach
    void cleanBusinessTables() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'teacher-a', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '数学教师')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C-101', '七年级一班')");
    }

    /**
     * 先证明连的是内存库，后续断言才有意义。
     *
     * <p>与 {@code MySqlMigrationIT} 刻意让 datasource 指向不存在的地址同理：这里断言 H2，
     * 避免将来有人在默认档误接真实库，把“迁移已通过”变成假消息。
     */
    @Test
    void 运行的是内存数据库而不是真实MySQL() {
        assertThat(datasourceUrl).startsWith("jdbc:h2:");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class))
            .isGreaterThanOrEqualTo(8);
    }

    @Test
    void V8新增列全部存在() {
        assertThat(columnsOf("app_user")).contains("account_status");
        assertThat(columnsOf("student")).contains("user_id");
        assertThat(columnsOf("assignment")).contains("published_at", "due_at", "version");
    }

    @Test
    void V9文档与OCR表全部存在() {
        assertThat(allTables()).contains(
            "stored_file", "document_upload", "document_page", "document_region", "ocr_task");
    }

    @Test
    void V9关键唯一约束与索引存在() {
        assertThat(constraintTypes("stored_file", "uk_stored_file_key")).containsExactly("UNIQUE");
        assertThat(constraintTypes("document_page", "uk_document_page_no")).containsExactly("UNIQUE");
        // 幂等键名字与内容都在 V11 换过：这里断言的是迁移之后的最终状态，
        // 旧键名留在这里只会让人以为判重还是按 (storage_sha256, document_kind) 走。
        assertThat(constraintTypes("ocr_task", "uk_ocr_task_document_version")).containsExactly("UNIQUE");
        assertThat(constraintTypes("ocr_task", "uk_ocr_task_idempotency")).isEmpty();

        assertThat(indexNames("ocr_task")).contains("idx_ocr_task_claim");
        assertThat(indexNames("document_region")).contains("idx_document_region_page");
        assertThat(indexNames("stored_file")).contains("idx_stored_file_sha");
    }

    /**
     * 区域坐标必须落在 0..1。
     *
     * <p>坐标是归一化值，与页面像素尺寸解耦。越界值不只是一个坏数字——它会让后续按区域裁剪
     * 出来的题图是空白的，而那正是教师校对时唯一能核对的东西，所以交给数据库拒绝。
     */
    @Test
    void V9区域坐标越界会被拒绝() {
        long pageId = seedDocumentChain();
        jdbc.update("""
            insert into document_region(id, page_id, region_type, x, y, width, height)
            values (9001, ?, 'TEXT_BLOCK', 0.1, 0.1, 0.3, 0.3)
            """, pageId);

        assertThatThrownBy(() -> jdbc.update("""
            insert into document_region(id, page_id, region_type, x, y, width, height)
            values (9002, ?, 'TEXT_BLOCK', 1.5, 0.1, 0.3, 0.3)
            """, pageId)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("select count(*) from document_region", Integer.class)).isEqualTo(1);
    }

    /**
     * OCR 任务按文档判重，不按文件内容判重。
     *
     * <p>学生把同一张照片重新交一次（退回后重交、或从相册里又选了一遍），会产生第二份
     * {@code document_upload}，但字节完全相同。旧的 (storage_sha256, document_kind,
     * processing_version) 键会把第二条任务静默跳过，那份答卷就永远停在"待识别"——
     * 判重的对象是"这份文档有没有任务"，不是"这个字节串有没有任务"。
     */
    @Test
    void V11OCR任务按文档判重而不是按文件哈希判重() {
        jdbc.update("insert into student(id, class_id, student_no, name) values (100, 101, '0001', '甲')");
        long firstDocumentId = seedStudentSubmissionDocument(6501, 6001);
        long secondDocumentId = seedStudentSubmissionDocument(6502, 6002);

        jdbc.update("""
            insert into ocr_task(id, document_id, document_kind, storage_sha256, params_version)
            values (8001, ?, 'STUDENT_SUBMISSION', ?, 'p1')
            """, firstDocumentId, "a".repeat(64));

        // 同一份文档、同一处理版本再来一条：重复识别没有意义，拒掉。
        assertThatThrownBy(() -> jdbc.update("""
            insert into ocr_task(id, document_id, document_kind, storage_sha256, params_version)
            values (8002, ?, 'STUDENT_SUBMISSION', ?, 'p1')
            """, firstDocumentId, "a".repeat(64)))
            .isInstanceOf(DataIntegrityViolationException.class);

        // 另一份文档、哈希一模一样：这是旧键会误伤的那一行，必须能建出来。
        jdbc.update("""
            insert into ocr_task(id, document_id, document_kind, storage_sha256, params_version)
            values (8003, ?, 'STUDENT_SUBMISSION', ?, 'p1')
            """, secondDocumentId, "a".repeat(64));

        assertThat(jdbc.queryForObject("select count(*) from ocr_task", Integer.class)).isEqualTo(2);
    }

    /** 一份学生答卷文档：独立的 stored_file 行，但内容哈希与另一份完全相同。 */
    private long seedStudentSubmissionDocument(long documentId, long fileId) {
        jdbc.update("""
            insert into stored_file(id, teacher_id, storage_key, original_name, mime_type, size_bytes, sha256)
            values (?, 11, ?, 'answer.jpg', 'image/jpeg', 1024, ?)
            """, fileId, "teachers/11/documents/" + fileId + "/original/" + fileId + ".jpg", "a".repeat(64));
        jdbc.update("""
            insert into document_upload(id, document_kind, teacher_id, student_id, original_file_id, status)
            values (?, 'STUDENT_SUBMISSION', 11, 100, ?, 'PENDING')
            """, documentId, fileId);
        return documentId;
    }

    @Test
    void V9页面在同一文档内页码唯一() {
        long documentId = seedDocumentChainWithDocument();
        long fileId = jdbc.queryForObject(
            "select original_file_id from document_upload where id = ?", Long.class, documentId);

        jdbc.update("""
            insert into document_page(id, document_id, page_no, page_file_id)
            values (7001, ?, 1, ?)
            """, documentId, fileId);

        assertThatThrownBy(() -> jdbc.update("""
            insert into document_page(id, document_id, page_no, page_file_id)
            values (7002, ?, 1, ?)
            """, documentId, fileId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private long seedDocumentChain() {
        long documentId = seedDocumentChainWithDocument();
        long fileId = jdbc.queryForObject(
            "select original_file_id from document_upload where id = ?", Long.class, documentId);
        jdbc.update("""
            insert into document_page(id, document_id, page_no, page_file_id)
            values (7001, ?, 1, ?)
            """, documentId, fileId);
        return 7001L;
    }

    @Test
    void V10候选题与题图表存在() {
        assertThat(allTables()).contains("paper_question_candidate", "question_asset");
        assertThat(columnsOf("paper_question_candidate")).contains(
            "assignment_id", "document_id", "document_kind", "order_no", "question_code",
            "source_region_ids", "warnings", "review_status", "version");
        assertThat(columnsOf("question_asset")).contains("question_id", "file_id", "role", "sort_order");
    }

    @Test
    void V10约束与索引存在() {
        assertThat(constraintTypes("paper_question_candidate", "uk_paper_candidate_order"))
            .containsExactly("UNIQUE");
        assertThat(constraintTypes("question_asset", "uk_question_asset_order")).containsExactly("UNIQUE");
        assertThat(indexNames("paper_question_candidate")).contains("idx_paper_candidate_code");
        assertThat(indexNames("question_asset")).contains("idx_question_asset_file");
    }

    /**
     * 同一文档内的候选顺序唯一。
     *
     * <p>顺序是候选之间唯一的稳定标识：确认入库时 {@code assignment_question.question_order}
     * 直接由它决定，出现两行同序就会让题目顺序变成"看数据库先返回哪一行"。
     * 空白卷与答案卷各自从 1 开始，所以唯一键必须带上 document_id。
     */
    @Test
    void V10同一文档内候选顺序唯一而不同文档可重复() {
        long examDocumentId = seedDocumentChainWithDocument();
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status) values (501, 11, 101, '整卷作业', 'OCR_REVIEW')");
        jdbc.update("""
            insert into paper_question_candidate(id, assignment_id, document_id, document_kind, order_no, source_region_ids)
            values (9101, 501, ?, 'EXAM_PAPER', 1, '[1]')
            """, examDocumentId);

        assertThatThrownBy(() -> jdbc.update("""
            insert into paper_question_candidate(id, assignment_id, document_id, document_kind, order_no, source_region_ids)
            values (9102, 501, ?, 'EXAM_PAPER', 1, '[2]')
            """, examDocumentId)).isInstanceOf(DataIntegrityViolationException.class);

        // 答案卷是另一份文档，它自己的第 1 题与空白卷第 1 题互不冲突。
        jdbc.update("""
            insert into stored_file(id, teacher_id, storage_key, original_name, mime_type, size_bytes, sha256)
            values (6002, 11, 'teachers/11/documents/2/original/6002.pdf', 'key.pdf',
                    'application/pdf', 1024, ?)
            """, "c".repeat(64));
        jdbc.update("""
            insert into document_upload(id, document_kind, teacher_id, assignment_id, original_file_id, status)
            values (6502, 'ANSWER_KEY', 11, 501, 6002, 'PENDING')
            """);
        jdbc.update("""
            insert into paper_question_candidate(id, assignment_id, document_id, document_kind, order_no, source_region_ids)
            values (9103, 501, 6502, 'ANSWER_KEY', 1, '[3]')
            """);

        assertThat(jdbc.queryForObject("select count(*) from paper_question_candidate", Integer.class))
            .isEqualTo(2);
    }

    @Test
    void V11提交版本与答案资产表存在() {
        assertThat(allTables()).contains(
            "submission_version", "submission_page", "submission_audit", "student_answer_asset");
        assertThat(columnsOf("submission_version")).contains(
            "submission_id", "assignment_id", "student_id", "version_no", "status", "is_current",
            "submitted_at", "locked_at", "returned_at", "return_reason", "superseded_at");
        assertThat(columnsOf("submission_page")).contains(
            "submission_version_id", "page_no", "document_id", "document_page_no", "page_file_id",
            "rotated_file_id", "thumbnail_file_id", "rotation_degrees", "quality_status", "quality_score");
        assertThat(columnsOf("submission_audit")).contains(
            "submission_version_id", "assignment_id", "student_id", "action", "actor_role", "actor_id", "detail");
        assertThat(columnsOf("student_answer_asset")).contains(
            "answer_id", "submission_version_id", "submission_page_id", "document_region_id",
            "file_id", "role", "sort_order");

        // 当前版本指针必须可空：V3 时代导入的提交没有版本概念，写成 not null 这些历史行就迁不动。
        assertThat(jdbc.queryForObject("""
            select is_nullable from information_schema.columns
            where lower(table_name) = 'submission' and lower(column_name) = 'submission_version_id'
            """, String.class)).isEqualTo("YES");

        // 退回时让旧评分失效靠这两列，缺一列就只能删行，而删行会把"发生过的事"抹掉。
        assertThat(columnsOf("grading_result")).contains("invalidated_at", "invalidated_reason");
        assertThat(columnsOf("ai_grading_task")).contains("invalidated_at", "invalidated_reason");
    }

    @Test
    void V11约束与索引存在() {
        assertThat(constraintTypes("submission_version", "uk_submission_version_no")).containsExactly("UNIQUE");
        assertThat(constraintTypes("submission_page", "uk_submission_page_no")).containsExactly("UNIQUE");
        assertThat(constraintTypes("submission_page", "ck_submission_page_rotation")).containsExactly("CHECK");
        assertThat(constraintTypes("student_answer_asset", "uk_answer_asset_file")).containsExactly("UNIQUE");
        // V9 留下待补的那条外键，V11 必须补上：否则学生答卷的文档可以挂到一个不存在的版本上。
        assertThat(constraintTypes("document_upload", "fk_document_upload_version")).containsExactly("FOREIGN KEY");

        assertThat(indexNames("submission_version")).contains("idx_submission_version_assignment");
        assertThat(indexNames("submission_page")).contains("idx_submission_page_version");
        assertThat(indexNames("submission_audit")).contains("idx_submission_audit_version");
        assertThat(indexNames("student_answer_asset")).contains("idx_answer_asset_answer");
    }

    /**
     * 版本号在 (作业, 学生) 内唯一。
     *
     * <p>并发提交时两个请求会算出同一个 version_no，正常路径靠服务层锁作业行串行化；
     * 只要有一处忘了加锁（新调用方、批处理、运维脚本），唯一键就是最后一道闸。
     */
    @Test
    void V11同一学生同一作业的版本号唯一() {
        seedSubmissionChain();
        insertVersion(1101, 1);

        assertThatThrownBy(() -> insertVersion(1102, 1))
            .isInstanceOf(DataIntegrityViolationException.class);

        // 第 2 版是正常递增，不该被上面的唯一键挡下。
        insertVersion(1103, 2);

        assertThat(jdbc.queryForObject("select count(*) from submission_version", Integer.class)).isEqualTo(2);
    }

    /** 同一版本内页码唯一：两个"第 3 页"会让界面上翻页顺序变得无法解释。 */
    @Test
    void V11同一版本内页码唯一() {
        long versionId = seedDraftVersionWithFiles();
        jdbc.update("""
            insert into submission_page(id, submission_version_id, page_no, document_id, page_file_id, rotated_file_id)
            values (1201, ?, 1, 6501, 6001, 6001)
            """, versionId);

        assertThatThrownBy(() -> jdbc.update("""
            insert into submission_page(id, submission_version_id, page_no, document_id, page_file_id, rotated_file_id)
            values (1202, ?, 1, 6502, 6002, 6002)
            """, versionId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 同一份上传里的同一页只能占一个位置。
     *
     * <p>没有这条键的话，同一张答卷图可以被放进两个页码，识别时同一道题出现两次，
     * 学生会看到题号重复、教师会给同一道题打两次分。
     */
    @Test
    void V11同一份上传的同一页不能被放进两个位置() {
        long versionId = seedDraftVersionWithFiles();
        jdbc.update("""
            insert into submission_page(id, submission_version_id, page_no, document_id, page_file_id, rotated_file_id)
            values (1201, ?, 1, 6501, 6001, 6001)
            """, versionId);

        // 页码不同，所以撞的是 (document_id, document_page_no) 而不是页码唯一键。
        assertThatThrownBy(() -> jdbc.update("""
            insert into submission_page(id, submission_version_id, page_no, document_id, page_file_id, rotated_file_id)
            values (1202, ?, 2, 6501, 6001, 6001)
            """, versionId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 旋转只允许 0/90/180/270。
     *
     * <p>任意角度会让后续按区域裁剪的坐标全都要跟着做三角函数，而那些坐标是教师框出来的、
     * 人可读的归一化值 —— 一个手滑的 45 度不该有污染它们的能力。
     */
    @Test
    void V11旋转角度只允许四个直角() {
        long versionId = seedDraftVersionWithFiles();
        jdbc.update("""
            insert into submission_page(id, submission_version_id, page_no, document_id,
                                        page_file_id, rotated_file_id, rotation_degrees)
            values (1201, ?, 1, 6501, 6001, 6001, 90)
            """, versionId);

        assertThatThrownBy(() -> jdbc.update("""
            insert into submission_page(id, submission_version_id, page_no, document_id,
                                        page_file_id, rotated_file_id, rotation_degrees)
            values (1202, ?, 2, 6502, 6002, 6002, 45)
            """, versionId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V12答案候选与配准列存在() {
        assertThat(allTables()).contains("submission_answer_candidate");
        assertThat(columnsOf("submission_answer_candidate")).contains(
            "submission_version_id", "question_id", "order_no", "answer_text", "answer_latex",
            "source_region_ids", "confidence", "blank", "warnings", "review_status", "version");
        // 整份答卷级别的警告与每条候选自己那份是两层：这里回答"整份答卷有什么问题"。
        assertThat(columnsOf("submission_version")).contains("extraction_warnings");
        // 配准记在"识别出的那一页"上，不是记在"学生排的第几页"上：学生把第 2 页排到第 1 位，
        // 配准结果不该跟着换位置。
        assertThat(columnsOf("document_page")).contains("template_page_no", "alignment_confidence");
    }

    @Test
    void V12约束与索引存在() {
        assertThat(constraintTypes("submission_answer_candidate", "uk_answer_candidate_question"))
            .containsExactly("UNIQUE");
        assertThat(constraintTypes("submission_answer_candidate", "uk_answer_candidate_order"))
            .containsExactly("UNIQUE");
        assertThat(constraintTypes("submission_answer_candidate", "fk_answer_candidate_version"))
            .containsExactly("FOREIGN KEY");
        assertThat(constraintTypes("submission_answer_candidate", "fk_answer_candidate_question"))
            .containsExactly("FOREIGN KEY");
        assertThat(indexNames("submission_answer_candidate")).contains("idx_answer_candidate_version");
    }

    /**
     * 一道题只能有一条候选。
     *
     * <p>两条会让"这道题的答案是什么"没有唯一答案，确认入库时也无从选择；
     * 改派因此被设计成**互换**（{@code AnswerCorrectionCommand.questionId}），
     * 而不是"把这条挪到一道还没有候选的题上"—— 唯一键决定了后者永远不存在。
     */
    @Test
    void V12一道题只能有一条答案候选() {
        long versionId = seedAnswerCandidateChain();
        insertCandidate(1301, versionId, 401, 1);

        assertThatThrownBy(() -> insertCandidate(1302, versionId, 401, 2))
            .isInstanceOf(DataIntegrityViolationException.class);
        // 展示顺序同理：两行都排 1 号位会让题目顺序变成"看数据库先返回哪一行"。
        assertThatThrownBy(() -> insertCandidate(1303, versionId, 402, 1))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 候选的默认值与空值语义。
     *
     * <p>`answer_text` 可空是刻意的：NULL = 还没有识别结果，空串 = 识别到了但那一块是空的。
     * 把它写成 not null default '' 会让这两种情况同形，而教师看到的差别
     * 正是"这道题还没识别"与"学生这题没写"。
     */
    @Test
    void V12候选的默认值与空值语义() {
        long versionId = seedAnswerCandidateChain();
        insertCandidate(1301, versionId, 401, 1);

        assertThat(jdbc.queryForObject("""
            select blank from submission_answer_candidate where id = 1301
            """, Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
            select review_status from submission_answer_candidate where id = 1301
            """, String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
            select version from submission_answer_candidate where id = 1301
            """, Integer.class)).isZero();

        for (String nullable : List.of("answer_text", "answer_latex", "warnings", "confidence")) {
            assertThat(jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where lower(table_name) = 'submission_answer_candidate' and lower(column_name) = ?
                """, String.class, nullable))
                .as("%s 必须可空：写成 not null 会让“还没识别”与“识别到空”同形", nullable)
                .isEqualTo("YES");
        }
    }

    /** 一条候选链：提交版本 + 一道题。答题文本与警告都不给，走默认值。 */
    private long seedAnswerCandidateChain() {
        seedSubmissionChain();
        insertVersion(1101, 1);
        jdbc.update("""
            insert into knowledge_point(id, teacher_id, code, name, grade, active)
            values (301, 11, 'ALG', '一元一次方程', 7, true)
            """);
        jdbc.update("""
            insert into question(id, teacher_id, question_code, type, content, total_score,
                                 primary_knowledge_point_id, accepted_answers)
            values (401, 11, 'Q1', 'FILL_BLANK', '题1', 10, 301, '["2"]')
            """);
        jdbc.update("""
            insert into question(id, teacher_id, question_code, type, content, total_score,
                                 primary_knowledge_point_id, accepted_answers)
            values (402, 11, 'Q2', 'FILL_BLANK', '题2', 10, 301, '["3"]')
            """);
        return 1101L;
    }

    private void insertCandidate(long id, long versionId, long questionId, int orderNo) {
        jdbc.update("""
            insert into submission_answer_candidate(id, submission_version_id, question_id, order_no,
                                                    source_region_ids)
            values (?, ?, ?, ?, '[]')
            """, id, versionId, questionId, orderNo);
    }

    private void insertVersion(long id, int versionNo) {        jdbc.update("""
            insert into submission_version(id, submission_id, assignment_id, student_id, version_no, status)
            values (?, 701, 501, 100, ?, 'DRAFT')
            """, id, versionNo);
    }

    /** 一条提交链：学生 → 已发布作业 → 提交 → 第 1 版草稿 + 两次上传（各一张页面图）。 */
    private long seedDraftVersionWithFiles() {
        seedSubmissionChain();
        insertVersion(1101, 1);
        seedPageDocument(6501, 6001);
        seedPageDocument(6502, 6002);
        return 1101L;
    }

    /** 一次学生上传：一个 document_upload 加它的原始文件。 */
    private void seedPageDocument(long documentId, long fileId) {
        seedStoredFile(fileId);
        jdbc.update("""
            insert into document_upload(id, document_kind, teacher_id, assignment_id, student_id,
                                        submission_version_id, original_file_id, status)
            values (?, 'STUDENT_SUBMISSION', 11, 501, 100, 1101, ?, 'PENDING')
            """, documentId, fileId);
    }

    private void seedSubmissionChain() {
        jdbc.update("insert into student(id, class_id, student_no, name) values (100, 101, '0001', '甲')");
        jdbc.update("""
            insert into assignment(id, teacher_id, class_id, title, status)
            values (501, 11, 101, '第一单元测验', 'PUBLISHED')
            """);
        jdbc.update("""
            insert into submission(id, assignment_id, student_id, status)
            values (701, 501, 100, 'IMPORTED')
            """);
    }

    /** SHA 用「60 个 f + 四位 id」拼出来：64 位合法十六进制，且每个文件都不同。 */
    private void seedStoredFile(long fileId) {
        jdbc.update("""
            insert into stored_file(id, teacher_id, storage_key, original_name, mime_type, size_bytes, sha256)
            values (?, 11, ?, 'page.jpg', 'image/jpeg', 1024, ?)
            """, fileId, "teachers/11/submissions/" + fileId + ".jpg",
            "f".repeat(60) + String.format("%04d", fileId));
    }

    private long seedDocumentChainWithDocument() {
        jdbc.update("""
            insert into stored_file(id, teacher_id, storage_key, original_name, mime_type, size_bytes, sha256)
            values (6001, 11, 'teachers/11/documents/1/original/6001.pdf', 'paper.pdf',
                    'application/pdf', 1024, ?)
            """, "b".repeat(64));
        jdbc.update("""
            insert into document_upload(id, document_kind, teacher_id, original_file_id, status)
            values (6501, 'EXAM_PAPER', 11, 6001, 'PENDING')
            """);
        return 6501L;
    }

    @Test
    void V8约束与索引存在() {
        // 用完整约束类型字符串比较：MySQL 与 H2 在 information_schema 中都写作
        // UNIQUE / FOREIGN KEY，比首字母缩写更不容易被方言差异骗过。
        assertThat(constraintTypes("student", "uk_student_user")).containsExactly("UNIQUE");
        assertThat(constraintTypes("student", "fk_student_user")).containsExactly("FOREIGN KEY");
        assertThat(indexNames("student")).contains("idx_student_user");
        assertThat(indexNames("assignment")).contains("idx_assignment_class_status");
    }

    @Test
    void 未开通学生可以并存而同一账号只能绑定一个学生() {
        jdbc.update("insert into student(id, class_id, student_no, name) values (100, 101, '0001', '甲')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (101, 101, '0002', '乙')");

        // user_id 为 NULL 的多行互不冲突，这是历史数据尚未开通账号的常态。
        assertThat(jdbc.queryForList(
            "select count(*) from student where user_id is null", Integer.class)).containsExactly(2);

        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (2, 'stu_a', 'x', 'STUDENT', true)");
        jdbc.update("update student set user_id = 2 where id = 100");

        assertThatThrownBy(() -> jdbc.update("update student set user_id = 2 where id = 101"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 账号状态默认ACTIVE且作业版本默认0() {
        assertThat(jdbc.queryForObject("select account_status from app_user where id = 1", String.class))
            .isEqualTo("ACTIVE");

        jdbc.update("insert into assignment(id, teacher_id, class_id, title) values (501, 11, 101, '作业')");

        assertThat(jdbc.queryForObject("select version from assignment where id = 501", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject(
            "select count(*) from assignment where id = 501 and published_at is null and due_at is null",
            Integer.class)).isEqualTo(1);
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList("""
            select lower(column_name) from information_schema.columns
            where lower(table_name) = lower(?) order by ordinal_position
            """, String.class, table);
    }

    private List<String> allTables() {
        return jdbc.queryForList(
            "select lower(table_name) from information_schema.tables", String.class);
    }

    private List<String> constraintTypes(String table, String constraint) {
        return jdbc.queryForList("""
            select constraint_type from information_schema.table_constraints
            where lower(table_name) = lower(?) and lower(constraint_name) = lower(?)
            """, String.class, table, constraint);
    }

    private List<String> indexNames(String table) {
        return jdbc.queryForList("""
            select lower(index_name) from information_schema.indexes
            where lower(table_name) = lower(?)
            """, String.class, table);
    }
}
