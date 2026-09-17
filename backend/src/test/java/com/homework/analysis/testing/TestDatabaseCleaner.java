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
        // 答案图排在最前：它同时引用 stored_file、document_region、student_answer 和
        // submission_version（V11），是这片引用网里最深的一层。
        "student_answer_asset",
        // 答卷答案候选引用 submission_version 与 question（V12），比这两个父表都先清。
        "submission_answer_candidate",
        // 文档与 OCR 一层：它们引用 assignment / student / teacher，
        // 必须比这些父表先清空。
        "ocr_task", "document_region", "document_page",
        // 候选题引用 document_upload 与 assignment；题目配图引用 question 与 stored_file，
        // 所以两者都必须排在 document_upload / stored_file / question 之前。
        // submission_page 引用 stored_file 与 submission_version，同样要排在两者之前。
        "question_asset", "paper_question_candidate", "submission_page",
        // document_upload 在 V11 挂上了 submission_version 外键，所以它也必须排在版本表之前。
        "document_upload", "stored_file",
        "exercise_item", "exercise_set",
        "grading_audit", "teacher_review", "grading_result", "ai_grading_task",
        "submission_audit", "submission_version",
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
