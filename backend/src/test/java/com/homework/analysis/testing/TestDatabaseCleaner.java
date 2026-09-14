package com.homework.analysis.testing;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试共用的数据库清理器。
 *
 * <p>所有集成测试类在 {@code @BeforeEach} 中统一调用本类，按完整外键顺序清空业务表，
 * 使任何测试类都不依赖其它测试类的执行结果或执行顺序。
 *
 * <p>表名来自代码内固定允许列表，不接收外部输入。不删除 {@code flyway_schema_history}，
 * 也不关闭数据库外键约束检查。
 */
public final class TestDatabaseCleaner {

    /** 子表在前、父表在后，顺序与 Flyway 迁移中声明外键依赖的方向相反。 */
    private static final String[] TABLES = {
        "grading_audit", "teacher_review", "grading_result", "ai_grading_task",
        "student_answer", "submission", "assignment_question", "assignment",
        "rubric_item", "question_knowledge_point", "question", "knowledge_point",
        "student", "school_class", "teacher", "app_user"
    };

    private TestDatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbc) {
        for (String table : TABLES) {
            jdbc.update("delete from " + table);
        }
    }
}
