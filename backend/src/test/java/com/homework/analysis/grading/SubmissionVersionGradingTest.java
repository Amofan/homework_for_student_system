package com.homework.analysis.grading;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.testing.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批改取数的入口条件：只批"教师已经确认过答案、而且就是当前提交"的那一版。
 *
 * <p>这一层守卫加在批改而不是别处，是因为它是第一条会把作答变成**分数**的链路：
 * 分数一旦产生，学情统计、AI 任务的输入、学生看到的最终结果全都以它为起点。
 * 而它的输入只可能来自一处 —— 教师确认答案时写下的那套 {@code student_answer}。
 *
 * <p>四种被拦下的处境各有各的原因，也各有各的处理动作，所以分开测：
 * <ul>
 *   <li>还在识别 / 等着校对：教师自己就能推进（去校对页确认答案）；</li>
 *   <li>已经被学生重交取代：库里那套答案已经不是学生现在的作答了；</li>
 *   <li>还没有任何一版确认过：这份作业根本还没到能批改的时候。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubmissionVersionGradingTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled)"
            + " values (1, 'a', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name)"
            + " values (101, 11, 'C1', '七年级一班')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (1001, 101, '001', '张三')");
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active)"
            + " values (301, 11, 'ALG', '一元一次方程', 7, true)");
        // 一道客观题（走规则评分）加一道解答题（走 AI 任务）：批改的两条出口都要能被看见。
        jdbc.update("insert into question(id, teacher_id, question_code, type, content, total_score,"
            + " primary_knowledge_point_id, accepted_answers)"
            + " values (401, 11, 'Q1', 'FILL_BLANK', '题1', 10, 301, '[\"2\"]')");
        jdbc.update("insert into question(id, teacher_id, question_code, type, content, total_score,"
            + " primary_knowledge_point_id, accepted_answers)"
            + " values (402, 11, 'Q2', 'SOLUTION', '题2', 10, 301, '[]')");
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status)"
            + " values (501, 11, 101, '作业', 'PUBLISHED')");
        jdbc.update("insert into assignment_question(assignment_id, question_id, question_order)"
            + " values (501, 401, 1), (501, 402, 2)");
        jdbc.update("insert into submission(id, assignment_id, student_id, status, submission_version_id)"
            + " values (601, 501, 1001, 'SUBMITTED', 1101)");
        jdbc.update("insert into student_answer(id, submission_id, question_id, answer_content)"
            + " values (611, 601, 401, '2'), (612, 601, 402, '过程略')");
        seedVersion(1101, 601, 1, "CONFIRMED", true);
    }

    @Test
    void 还在识别的答卷不会被批改() throws Exception {
        setStatus("PROCESSING");

        runGrading()
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_CONFIRMED"));

        assertThat(writtenResults()).isZero();
        assertThat(writtenAiTasks()).isZero();
        // 拦在写之前：连版本状态都不该动，否则学生会看到"老师开始批改了"而其实什么都没批。
        assertThat(versionStatus()).isEqualTo("PROCESSING");
    }

    @Test
    void 等着教师校对的答卷不会被批改() throws Exception {
        setStatus("NEEDS_REVIEW");

        runGrading()
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_CONFIRMED"));

        assertThat(writtenResults()).isZero();
    }

    @Test
    void 学生重交之后不再按库里那套旧答案算分() throws Exception {
        // 学生交了第 2 版：当前提交变成 PROCESSING，而 student_answer 里还是第 1 版那套。
        // 这正是"学生改了、老师没确认"的处境 —— 按库里那套算出来的分数对不上任何一版作答。
        setCurrent(false);
        seedVersion(1102, 601, 2, "PROCESSING", true);

        runGrading()
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_CONFIRMED"));

        assertThat(writtenResults()).isZero();
    }

    @Test
    void 教师确认过的答卷会照批并把这一版钉住() throws Exception {
        runGrading()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ruleGraded").value(1))
            .andExpect(jsonPath("$.data.aiQueued").value(1));

        // 批改一旦开始，学生就不能再改也不能重交 —— 否则老师手上的分数对应的是另一版作答。
        // 钉住发生在写结果之前，所以这里同时验证了"结果写出来了"与"版本被锁了"。
        assertThat(versionStatus()).isEqualTo("LOCKED");
        assertThat(jdbc.queryForObject(
            "select locked_at from submission_version where id = 1101", Object.class)).isNotNull();
        assertThat(jdbc.queryForObject(
            "select suggested_score from grading_result where answer_id = 611", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject(
            "select status from ai_grading_task where answer_id = 612", String.class)).isEqualTo("PENDING");
    }

    @Test
    void 退回重交之后再批改会把失效的评分救活() throws Exception {
        // 上一轮批改留下的痕迹：退回时被标了失效（见 SubmissionVersionService.invalidateLiveResults）。
        // 学生的作答回来后，这一轮要重新批的正是这些答案 —— 而 uk_grading_answer / uk_ai_task_answer
        // 都建在 answer_id 上，同一道题不可能再插一行，只能把旧行复活。
        seedInvalidatedResult(701, 611);
        seedInvalidatedAiTask(801, 612);

        runGrading()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ruleGraded").value(1))
            .andExpect(jsonPath("$.data.aiQueued").value(1));

        assertThat(jdbc.queryForMap(
            "select status, invalidated_at from grading_result where answer_id = 611"))
            .containsEntry("status", "PENDING_REVIEW")
            .containsEntry("invalidated_at", null);
        assertThat(jdbc.queryForMap(
            "select status, invalidated_at from ai_grading_task where answer_id = 612"))
            .containsEntry("status", "PENDING")
            .containsEntry("invalidated_at", null);
        // 复活而不是又插一行：一次批改对一道题只留一条结果。
        assertThat(writtenResults()).isEqualTo(1);
        assertThat(writtenAiTasks()).isEqualTo(1);
    }

    @Test
    void 再批一次不会重复写入() throws Exception {
        runGrading().andExpect(status().isOk());

        runGrading()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ruleGraded").value(0))
            .andExpect(jsonPath("$.data.aiQueued").value(0))
            .andExpect(jsonPath("$.data.skipped").value(2));

        assertThat(writtenResults()).isEqualTo(1);
        assertThat(writtenAiTasks()).isEqualTo(1);
    }

    @Test
    void 还没确认的那一份不会被顺便批掉() throws Exception {
        // 一个班里总有学生还在校对。批改是逐份进行的，不能因为一个人卡住就让全班等，
        // 也不能顺手按库里那份还没被确认的作答算分。
        seedSecondStudent("NEEDS_REVIEW");

        runGrading()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ruleGraded").value(1))
            .andExpect(jsonPath("$.data.aiQueued").value(1));

        assertThat(writtenResults()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from submission_version where id = 1103", String.class))
            .isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject(
            "select locked_at from submission_version where id = 1103", Object.class)).isNull();
    }

    // ---------- 夹具 ----------

    private void seedVersion(long versionId, long submissionId, int versionNo, String status, boolean current) {
        jdbc.update("insert into submission_version(id, submission_id, assignment_id, student_id, version_no,"
                + " status, is_current) values (?, ?, 501, ?, ?, ?, ?)",
            versionId, submissionId, submissionId == 601 ? 1001 : 1002, versionNo, status, current);
    }

    /** 第二个学生（submission 602 / 版本 1103），用来验证"逐份批改"而不是"全班一起"。 */
    private void seedSecondStudent(String status) {
        jdbc.update("insert into student(id, class_id, student_no, name) values (1002, 101, '002', '李四')");
        jdbc.update("insert into submission(id, assignment_id, student_id, status, submission_version_id)"
            + " values (602, 501, 1002, 'SUBMITTED', 1103)");
        jdbc.update("insert into student_answer(id, submission_id, question_id, answer_content)"
            + " values (613, 602, 401, '2'), (614, 602, 402, '过程略')");
        seedVersion(1103, 602, 1, status, true);
    }

    private void seedInvalidatedResult(long resultId, long answerId) {
        jdbc.update("insert into grading_result(id, answer_id, source, suggested_score, error_type,"
                + " score_details, status, invalidated_at, invalidated_reason)"
                + " values (?, ?, 'AI', 3, 'METHOD_ERROR', '[]', 'PENDING_REVIEW', current_timestamp(3), '提交被退回')",
            resultId, answerId);
    }

    private void seedInvalidatedAiTask(long taskId, long answerId) {
        jdbc.update("insert into ai_grading_task(id, answer_id, status, attempt_count,"
            + " invalidated_at, invalidated_reason)"
            + " values (?, ?, 'SUCCEEDED', 2, current_timestamp(3), '提交被退回')", taskId, answerId);
    }

    private void setStatus(String status) {
        jdbc.update("update submission_version set status = ? where id = 1101", status);
    }

    private void setCurrent(boolean current) {
        jdbc.update("update submission_version set is_current = ? where id = 1101", current);
    }

    // ---------- 断言与请求 ----------

    private org.springframework.test.web.servlet.ResultActions runGrading() throws Exception {
        return mvc.perform(post("/api/grading/assignments/501/run").header("Authorization", bearer()));
    }

    private int writtenResults() {
        return count("select count(*) from grading_result");
    }

    private int writtenAiTasks() {
        return count("select count(*) from ai_grading_task");
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private String versionStatus() {
        return jdbc.queryForObject("select status from submission_version where id = 1101", String.class);
    }

    private String bearer() {
        return "Bearer " + jwtService.issue(11, 11, "TEACHER");
    }
}
