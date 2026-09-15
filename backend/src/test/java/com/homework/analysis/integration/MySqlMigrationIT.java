package com.homework.analysis.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

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
        "grading_result", "teacher_review", "grading_audit", "exercise_set", "exercise_item");

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
