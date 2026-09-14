package com.homework.analysis.testing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证清理器能处理自引用外键：knowledge_point.parent_id 指向同表的 id。
 * 无条件 delete 若按主键升序删除，父行会先于子行被删除而触发约束冲突。
 */
@SpringBootTest
@ActiveProfiles("test")
class TestDatabaseCleanerSelfReferenceTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void cleanRemovesParentChildKnowledgePointHierarchy() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'a', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲')");
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active) values (301, 11, 'PARENT', '父知识点', 7, true)");
        jdbc.update("insert into knowledge_point(id, teacher_id, parent_id, code, name, grade, active) values (302, 11, 301, 'CHILD', '子知识点', 7, true)");

        TestDatabaseCleaner.clean(jdbc);

        assertThat(jdbc.queryForObject("select count(*) from knowledge_point", Integer.class)).isZero();
    }
}
