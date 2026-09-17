package com.homework.analysis.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 在一次性 mysql:8.4 容器上验证迁移完整性与 MySQL 方言兼容性。
 *
 * <p>只在 {@code mvn verify -Pmysql-it} 下执行，普通 {@code mvn test} 不要求本机 Docker。
 * 容器随测试结束销毁，不复用 compose.yaml 的持久化卷。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("mysql-it")
class MySqlMigrationIT {

    /** 与迁移脚本一一对应的核心业务表；缺任何一张都说明迁移没有完整落地。 */
    private static final List<String> CORE_TABLES = List.of(
        "app_user", "teacher", "school_class", "student", "knowledge_point",
        "question", "question_knowledge_point", "rubric_item", "assignment",
        "assignment_question", "submission", "student_answer", "ai_grading_task",
        "grading_result", "teacher_review", "grading_audit", "exercise_set", "exercise_item",
        "stored_file", "document_upload", "document_page", "document_region", "ocr_task",
        "paper_question_candidate", "question_asset",
        "submission_version", "submission_page", "submission_audit", "student_answer_asset",
        "submission_answer_candidate");

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Autowired JdbcTemplate jdbc;

    /**
     * 运行在真实 MySQL 上，而不是静默退回内存数据库。
     * 若 @ServiceConnection 失效，本用例会先失败，后续断言才有意义。
     */
    @Test
    void 运行的是真实MySQL8() {
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("8.");
    }

    @Test
    void 迁移脚本全部成功且版本与迁移目录一致() throws IOException {
        List<String> applied = jdbc.queryForList(
            "select version from flyway_schema_history where success = 1 order by installed_rank",
            String.class);

        assertThat(applied).containsExactlyElementsOf(migrationVersionsOnDisk());
    }

    @Test
    void 核心业务表全部存在() {
        List<String> tables = jdbc.queryForList(
            "select lower(table_name) from information_schema.tables where table_schema = database()",
            String.class);

        assertThat(tables).containsAll(CORE_TABLES);
    }

    /**
     * V8 在真实 MySQL 上落地。
     *
     * <p>H2 的 {@code MODE=MySQL} 会接受部分 MySQL 拒绝的写法，反之亦然；本用例断言的是
     * MySQL 自己的信息模式，因此“迁移在内存库通过”不能替代这里。
     */
    @Test
    void V8学生账号与作业生命周期字段在MySQL上存在() {
        assertThat(columnsOf("app_user")).contains("account_status");
        assertThat(columnsOf("student")).contains("user_id");
        assertThat(columnsOf("assignment")).contains("published_at", "due_at", "version");

        assertThat(jdbc.queryForObject(
            "select column_default from information_schema.columns"
                + " where table_schema = database() and table_name = 'app_user' and column_name = 'account_status'",
            String.class)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
            "select column_type from information_schema.columns"
                + " where table_schema = database() and table_name = 'student' and column_name = 'user_id'",
            String.class)).isEqualTo("bigint");
        assertThat(jdbc.queryForObject(
            "select is_nullable from information_schema.columns"
                + " where table_schema = database() and table_name = 'student' and column_name = 'user_id'",
            String.class)).isEqualTo("YES");
    }

    @Test
    void V8在MySQL上建立了外键唯一约束与索引() {
        List<String> constraints = jdbc.queryForList("""
            select lower(constraint_name) from information_schema.table_constraints
            where table_schema = database() and table_name = 'student'
            """, String.class);
        assertThat(constraints).contains("uk_student_user", "fk_student_user");

        List<String> indexes = jdbc.queryForList("""
            select lower(index_name) from information_schema.statistics
            where table_schema = database() and table_name = 'assignment'
            """, String.class);
        assertThat(indexes).contains("idx_assignment_class_status");

        // 学生表可以为多行 NULL：MySQL 的唯一索引允许重复 NULL，未开通账号的历史学生才能并存。
        assertThat(jdbc.queryForObject("""
            select non_unique from information_schema.statistics
            where table_schema = database() and table_name = 'student' and index_name = 'uk_student_user'
            """, Integer.class)).isZero();
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList("""
            select lower(column_name) from information_schema.columns
            where table_schema = database() and table_name = ?
            """, String.class, table);
    }

    /**
     * V9 在真实 MySQL 上落地。
     *
     * <p>重点盯三处方言风险：归一化坐标必须是 {@code decimal(8,7)}（退化成 float 会让
     * 0.1+0.2 之类的比较在裁剪时漂移）、OCR 原始输出用 {@code text}、
     * 唯一约束与认领索引都在。
     */
    @Test
    void V9文档与OCR表在MySQL上落地() {
        assertThat(columnsOf("stored_file")).contains("storage_key", "sha256", "status", "deleted_at");
        assertThat(columnsOf("document_region")).contains("x", "y", "width", "height", "confidence", "raw_ocr_json");
        assertThat(columnsOf("ocr_task")).contains("storage_sha256", "document_kind", "processing_version", "claimed_at");

        assertThat(columnTypeOf("document_region", "x")).isEqualTo("decimal(8,7)");
        assertThat(columnTypeOf("ocr_task", "raw_output")).isEqualTo("text");

        List<String> constraints = jdbc.queryForList("""
            select lower(constraint_name) from information_schema.table_constraints
            where table_schema = database()
            """, String.class);
        // 幂等键在 V11 换成了 (document_id, processing_version)：这里断言的是迁移之后的最终状态，
        // 旧键名留在这里只会让人以为 (storage_sha256, document_kind, processing_version) 还在生效。
        assertThat(constraints).contains(
            "uk_stored_file_key", "uk_document_page_no", "uk_ocr_task_document_version");
        assertThat(constraints).doesNotContain("uk_ocr_task_idempotency");

        List<String> indexes = jdbc.queryForList("""
            select lower(index_name) from information_schema.statistics
            where table_schema = database() and table_name = 'ocr_task'
            """, String.class);
        assertThat(indexes).contains("idx_ocr_task_claim");
    }

    private String columnTypeOf(String table, String column) {
        return jdbc.queryForObject("""
            select column_type from information_schema.columns
            where table_schema = database() and table_name = ? and column_name = ?
            """, String.class, table, column);
    }

    /**
     * V10 在真实 MySQL 上落地。
     *
     * <p>两处方言风险值得单独盯：候选题的正文与 JSON 字段必须是 {@code text}/{@code longtext}
     * 而不是被截断成 {@code varchar}（题干稍长就会被静默截断，OCR 结果直接丢失），
     * 以及 {@code confidence} 必须是 {@code decimal} 而不是浮点——0.85 这种阈值比较用 float
     * 会出现"看着相等却不大于"的边界问题。
     */
    @Test
    void V10候选与题图表在MySQL上落地() {
        assertThat(columnsOf("paper_question_candidate")).contains(
            "assignment_id", "document_id", "document_kind", "order_no", "question_code",
            "content", "accepted_answers", "rubric_items", "source_region_ids", "warnings", "version");
        assertThat(columnsOf("question_asset")).contains("question_id", "file_id", "role", "sort_order");

        assertThat(columnTypeOf("paper_question_candidate", "confidence")).isEqualTo("decimal(5,4)");
        assertThat(columnTypeOf("paper_question_candidate", "content")).startsWith("text");
        assertThat(columnTypeOf("paper_question_candidate", "warnings")).startsWith("text");

        List<String> constraints = jdbc.queryForList("""
            select lower(constraint_name) from information_schema.table_constraints
            where table_schema = database()
            """, String.class);
        assertThat(constraints).contains("uk_paper_candidate_order", "uk_question_asset_order");

        List<String> indexes = jdbc.queryForList("""
            select lower(index_name) from information_schema.statistics
            where table_schema = database() and table_name = 'paper_question_candidate'
            """, String.class);
        assertThat(indexes).contains("idx_paper_candidate_code");
    }

    /**
     * V11 在真实 MySQL 上落地。
     *
     * <p>V11 是第一个动到既有约束的迁移，三处都属于"H2 过了不代表 MySQL 也过"：
     * 删掉 V9 的 {@code uk_ocr_task_idempotency} 用的是 {@code drop index}（MySQL 里唯一约束
     * 就是个索引，H2 两种写法都收）、给 {@code document_upload} 补上 V9 留下的外键、
     * 以及一条 CHECK。所以这里逐条对着 MySQL 自己的信息模式断言。
     */
    @Test
    void V11提交版本与答案资产表在MySQL上落地() {
        assertThat(columnsOf("submission_version")).contains(
            "submission_id", "assignment_id", "student_id", "version_no", "status", "is_current",
            "submitted_at", "locked_at", "returned_at", "return_reason", "superseded_at");
        assertThat(columnsOf("submission_page")).contains(
            "submission_version_id", "page_no", "document_id", "document_page_no",
            "page_file_id", "rotated_file_id", "thumbnail_file_id", "rotation_degrees",
            "width", "height", "quality_status", "quality_score");
        assertThat(columnsOf("submission_audit")).contains(
            "submission_version_id", "assignment_id", "student_id", "action", "actor_role",
            "actor_id", "detail");
        assertThat(columnsOf("student_answer_asset")).contains(
            "answer_id", "submission_version_id", "submission_page_id", "document_region_id",
            "file_id", "role", "sort_order");
        assertThat(columnsOf("submission")).contains("submission_version_id");
        assertThat(columnsOf("grading_result")).contains("invalidated_at", "invalidated_reason");
        assertThat(columnsOf("ai_grading_task")).contains("invalidated_at", "invalidated_reason");

        // boolean 在 MySQL 里落成 tinyint(1)：H2 上用 boolean 断言过了不代表这里也对。
        assertThat(columnTypeOf("submission_version", "is_current")).isEqualTo("tinyint(1)");
        // 退回原因与审计说明都要能存下整段话，退化成 varchar(255) 会静默截断。
        assertThat(columnTypeOf("submission_version", "return_reason")).isEqualTo("varchar(1000)");
        assertThat(columnTypeOf("submission_audit", "detail")).isEqualTo("varchar(1000)");
        assertThat(columnTypeOf("grading_result", "invalidated_reason")).isEqualTo("varchar(500)");
        assertThat(columnTypeOf("ai_grading_task", "invalidated_reason")).isEqualTo("varchar(500)");

        // 部署安全阀：没有这两个默认值，凡是没显式写这两列的插入全会失败，
        // 表现是"所有页面都存不进去"而不是一条错误提示。
        assertThat(columnDefaultOf("submission_version", "is_current")).isEqualTo("0");
        assertThat(columnDefaultOf("submission_page", "quality_status")).isEqualTo("OK");
        // 历史提交没有版本概念，读侧按"没有提交版本"处理，不能是 NOT NULL。
        assertThat(columnNullableOf("submission", "submission_version_id")).isEqualTo("YES");

        // grading_result 的长文本列必须是 text。varchar 按 4n 字节全额计入 MySQL 的行宽上限
        // （65535），这张表原来三列就占了 64000，加一列就直接建不出来。谁把它们改回 varchar，
        // 下一次给 grading_result 加列就会失败 —— 而报错信息里不会提到是这里超了。
        assertThat(columnTypeOf("grading_result", "score_details")).startsWith("text");
        assertThat(columnTypeOf("grading_result", "teacher_explanation")).startsWith("text");
        assertThat(columnTypeOf("grading_result", "student_feedback")).startsWith("text");
        // MODIFY 会连 NOT NULL 一起重述，漏掉就不是"换个类型"而是"放开约束"了。
        assertThat(columnNullableOf("grading_result", "score_details")).isEqualTo("NO");

        assertThat(constraintNamesOf("submission_version")).contains(
            "uk_submission_version_no", "fk_submission_version_submission",
            "fk_submission_version_assignment", "fk_submission_version_student");
        assertThat(constraintNamesOf("submission_page")).contains(
            "uk_submission_page_no", "uk_submission_page_source", "ck_submission_page_rotation");
        assertThat(constraintNamesOf("student_answer_asset")).contains(
            "uk_answer_asset_file", "fk_answer_asset_region");

        // V9 留下的待补外键：学生提交的文档在这里挂上提交版本。
        assertThat(jdbc.queryForList("""
            select lower(referenced_table_name) from information_schema.key_column_usage
            where table_schema = database() and table_name = 'document_upload'
              and column_name = 'submission_version_id'
            """, String.class)).containsExactly("submission_version");

        // submission.submission_version_id 刻意不加外键（会与版本表的反向引用成环）。
        // 断言"没有"就是这条设计决定在库里的样子：将来谁顺手补一个，会在这里被拦下。
        assertThat(jdbc.queryForObject("""
            select count(*) from information_schema.key_column_usage
            where table_schema = database() and table_name = 'submission'
              and referenced_table_name = 'submission_version'
            """, Integer.class)).isZero();
    }

    /**
     * 换掉的 OCR 幂等键在 MySQL 上换干净了。
     *
     * <p>这是 V11 唯一一处 {@code drop}：旧键留着会继续按"内容哈希相同"判重，
     * 学生的答卷撞上空白卷的哈希时会被静默跳过；新键的列序写反（处理版本在前）则会让
     * "同一份文档的不同处理版本"各自建任务，两种都只有真库的信息模式看得出来。
     */
    @Test
    void V11的OCR幂等键在MySQL上按文档与处理版本重建() {
        assertThat(indexNamesOf("ocr_task")).doesNotContain("uk_ocr_task_idempotency");

        List<String> columns = jdbc.queryForList("""
            select lower(column_name) from information_schema.statistics
            where table_schema = database() and table_name = 'ocr_task'
              and index_name = 'uk_ocr_task_document_version'
            order by seq_in_index
            """, String.class);
        assertThat(columns).containsExactly("document_id", "processing_version");
    }

    /**
     * 旋转角度的 CHECK 在 MySQL 上真的会拦。
     *
     * <p>光断言约束存在不够：MySQL 8.0.16 之前的版本会接受 CHECK 语法然后当它不存在，
     * 而库里躺着一个 45 度的页面，表现是后续按区域裁图全都裁错位置 —— 不报错，只出错。
     * 关掉外键检查是为了插进这一行非法数据，同一个连接上原样恢复。
     */
    @Test
    void V11的旋转约束在MySQL上真的拦得住() {
        assertThatThrownBy(() -> jdbc.execute((ConnectionCallback<Integer>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set foreign_key_checks = 0");
                try {
                    statement.executeUpdate("""
                        insert into submission_page(submission_version_id, page_no, document_id,
                                                    document_page_no, page_file_id, rotated_file_id,
                                                    rotation_degrees)
                        values (9991, 1, 9992, 1, 9993, 9993, 45)
                        """);
                } finally {
                    statement.execute("set foreign_key_checks = 1");
                }
            }
            return 0;
        })).hasMessageContaining("ck_submission_page_rotation");
    }

    /**
     * V12 在真实 MySQL 上落地。
     *
     * <p>这张表里三个长文本列全用 {@code text} 而不是 {@code varchar}，理由与 V11 给
     * {@code grading_result} 换类型那次是同一个：varchar 无论实际存几个字都按 4n 字节全额计入
     * MySQL 的行宽上限（65535），答案文本 + LaTeX + 警告三列按 varchar(4000) 写就会在真库上
     * 以 "Row size too large" 收场 —— 而 H2 没有这条限制，内存库里一直全绿。
     *
     * <p>{@code blank} 是 boolean → MySQL 落成 tinyint(1)，默认 0 必须显式写下来：
     * 少了默认值，凡是没写这一列的插入全会失败，表现是"所有校对都保存不了"。
     */
    @Test
    void V12答案候选在MySQL上落地() {
        assertThat(columnsOf("submission_answer_candidate")).contains(
            "submission_version_id", "question_id", "order_no", "answer_text", "answer_latex",
            "source_region_ids", "confidence", "blank", "warnings", "review_status", "version");
        assertThat(columnsOf("submission_version")).contains("extraction_warnings");
        assertThat(columnsOf("document_page")).contains("template_page_no", "alignment_confidence");

        assertThat(columnTypeOf("submission_answer_candidate", "answer_text")).isEqualTo("text");
        assertThat(columnTypeOf("submission_answer_candidate", "answer_latex")).isEqualTo("text");
        assertThat(columnTypeOf("submission_answer_candidate", "warnings")).isEqualTo("text");
        assertThat(columnTypeOf("submission_answer_candidate", "source_region_ids"))
            .isEqualTo("varchar(1024)");
        // 置信度取组成这道题的区域里的最小值，四位小数与识别引擎给的一致。
        assertThat(columnTypeOf("submission_answer_candidate", "confidence")).isEqualTo("decimal(5,4)");
        assertThat(columnTypeOf("submission_answer_candidate", "blank")).isEqualTo("tinyint(1)");
        assertThat(columnTypeOf("submission_answer_candidate", "review_status")).isEqualTo("varchar(32)");

        assertThat(columnDefaultOf("submission_answer_candidate", "blank")).isEqualTo("0");
        assertThat(columnDefaultOf("submission_answer_candidate", "review_status")).isEqualTo("PENDING");
        assertThat(columnDefaultOf("submission_answer_candidate", "version")).isEqualTo("0");

        // NULL = 还没有识别结果，空串 = 识别到了但那一块是空的。两者不能同形。
        assertThat(columnNullableOf("submission_answer_candidate", "answer_text")).isEqualTo("YES");
        assertThat(columnNullableOf("submission_answer_candidate", "answer_latex")).isEqualTo("YES");
        assertThat(columnNullableOf("submission_answer_candidate", "confidence")).isEqualTo("YES");
        // 归属与顺序是这道题存在于候选区的凭据，不能为空。
        assertThat(columnNullableOf("submission_answer_candidate", "source_region_ids")).isEqualTo("NO");
        assertThat(columnNullableOf("submission_answer_candidate", "order_no")).isEqualTo("NO");

        assertThat(constraintNamesOf("submission_answer_candidate")).contains(
            "uk_answer_candidate_question", "uk_answer_candidate_order",
            "fk_answer_candidate_version", "fk_answer_candidate_question");
        assertThat(indexNamesOf("submission_answer_candidate")).contains("idx_answer_candidate_version");
    }

    /**
     * 一道题只能有一条候选，这条唯一键在 MySQL 上真的会拦。
     *
     * <p>改派之所以被设计成"互换"而不是"挪到一道还没候选的题上"，就是因为这条键：
     * 后者那种目标状态在库里根本不存在。关掉外键检查插两行同题候选，是为了在不用伪造
     * 整条提交链的情况下单独验证这条约束会拦。
     */
    @Test
    void V12的一题一候选在MySQL上真的拦得住() {
        assertThatThrownBy(() -> jdbc.execute((ConnectionCallback<Integer>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set foreign_key_checks = 0");
                try {
                    statement.executeUpdate("""
                        insert into submission_answer_candidate(submission_version_id, question_id,
                                                                order_no, source_region_ids)
                        values (9991, 9992, 1, '[]'), (9991, 9992, 2, '[]')
                        """);
                } finally {
                    statement.execute("set foreign_key_checks = 1");
                }
            }
            return 0;
        })).hasMessageContaining("uk_answer_candidate_question");
    }

    private List<String> constraintNamesOf(String table) {
        return jdbc.queryForList("""
            select lower(constraint_name) from information_schema.table_constraints
            where table_schema = database() and table_name = ?
            """, String.class, table);
    }

    private List<String> indexNamesOf(String table) {
        return jdbc.queryForList("""
            select lower(index_name) from information_schema.statistics
            where table_schema = database() and table_name = ?
            """, String.class, table);
    }

    private String columnDefaultOf(String table, String column) {
        return jdbc.queryForObject("""
            select column_default from information_schema.columns
            where table_schema = database() and table_name = ? and column_name = ?
            """, String.class, table, column);
    }

    private String columnNullableOf(String table, String column) {
        return jdbc.queryForObject("""
            select is_nullable from information_schema.columns
            where table_schema = database() and table_name = ? and column_name = ?
            """, String.class, table, column);
    }

    /** 从 classpath 上的迁移脚本文件名解析版本号，作为“磁盘真相”与数据库记录比对。 */
    private static List<String> migrationVersionsOnDisk() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
            .getResources("classpath:db/migration/V*.sql");
        return Arrays.stream(resources)
            .map(resource -> {
                String filename = resource.getFilename() == null ? "" : resource.getFilename();
                Matcher matcher = VERSION.matcher(filename);
                if (!matcher.matches()) {
                    throw new IllegalStateException("无法解析迁移版本号：" + resource);
                }
                return matcher.group(1);
            })
            .sorted(Comparator.comparingInt(Integer::parseInt))
            .toList();
    }
}
