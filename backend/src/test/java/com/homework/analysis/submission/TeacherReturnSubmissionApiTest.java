package com.homework.analysis.submission;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.testing.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 教师看提交、开始批改与退回。
 *
 * <p>退回是唯一能把学生从"只读"里放出来的动作，所以这里盯的不是状态列，而是退回的边界：
 * 什么情况下不许退回（没说明原因、已经有确认过的评分、这一版早就被取代），
 * 以及退回到底动了什么、没动什么。
 *
 * <p>"已经有确认过的评分"这一条的用例把库里那套答案指向这一版（{@code submission.submission_version_id}），
 * 因为那正是教师确认答案时会发生的事；真实的确认接口由后续任务实现，这里只把前置状态摆好。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeacherReturnSubmissionApiTest {

    private static final Path STORAGE_ROOT = createStorageRoot();

    private static final long ASSIGNMENT_ID = 501L;
    private static final long STUDENT_ID = 1001L;
    private static final long TEACHER_ID = 11L;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local-root", STORAGE_ROOT::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired ObjectMapper objectMapper;
    @Autowired SubmissionVersionService service;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("""
            insert into app_user(id, username, password_hash, role, enabled) values
              (1, 'teacher', 'x', 'TEACHER', true),
              (2, 'teacher_two', 'x', 'TEACHER', true),
              (3, 'stu_one', 'x', 'STUDENT', true)
            """);
        jdbc.update("""
            insert into teacher(id, user_id, display_name) values
              (11, 1, '教师甲'),
              (12, 2, '教师乙')
            """);
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C-101', '七年级一班')");
        jdbc.update("insert into student(id, class_id, student_no, name, user_id) values (1001, 101, '001', '张三', 3)");
        jdbc.update("""
            insert into assignment(id, teacher_id, class_id, title, status, published_at, due_at, version) values
              (501, 11, 101, '第一单元作业', 'PUBLISHED', current_timestamp(3), '2030-01-01 00:00:00', 1)
            """);
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active) values (301, 11, 'ALG', '一元一次方程', 7, true)");
        jdbc.update("""
            insert into question(id, teacher_id, question_code, type, content, total_score,
                                 primary_knowledge_point_id, accepted_answers)
            values (401, 11, 'Q1', 'FILL_BLANK', '题1', 10, 301, '["2"]')
            """);
    }

    // ---------- 查看 ----------

    @Test
    void 教师看到的是学生交的那一版() throws Exception {
        long versionId = submittedVersion();

        mvc.perform(get("/api/teacher/submissions/" + versionId).header("Authorization", teacherToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PROCESSING"))
            .andExpect(jsonPath("$.data.studentId").value(STUDENT_ID))
            .andExpect(jsonPath("$.data.versionNo").value(1))
            .andExpect(jsonPath("$.data.pages.length()").value(1));
    }

    @Test
    void 不是自己作业上的版本返回404() throws Exception {
        long versionId = submittedVersion();

        // 与"版本不存在"给同一个回答：教师不该从错误码里数出别的班交了几份。
        mvc.perform(get("/api/teacher/submissions/" + versionId).header("Authorization", otherTeacherToken()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_VERSION_NOT_FOUND"));
    }

    @Test
    void 学生令牌不能访问教师提交接口() throws Exception {
        long versionId = submittedVersion();

        returnIt(versionId, "重做", studentToken())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    // ---------- 退回 ----------

    @Test
    void 退回必须给出原因() throws Exception {
        long versionId = submittedVersion();

        // 原因由请求体校验先挡住，所以这三个都是参数错误而不是业务冲突。
        returnIt(versionId, "").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        returnIt(versionId, "   ").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        returnIt(versionId, "重".repeat(1001)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        // 三次失败之后这一版还是原样：校验挡下的请求不该留下半个状态。
        assertThat(statusOf(versionId)).isEqualTo("PROCESSING");
        assertThat(currentVersionIds()).containsExactly(versionId);

        // 刚好到上限的说明是合法输入，列宽与校验上限是同一条线。
        returnIt(versionId, "重".repeat(1000)).andExpect(status().isOk());
        assertThat(returnReasonOf(versionId)).hasSize(1000);
    }

    @Test
    void 服务层对退回原因也有兜底校验() throws Exception {
        long versionId = submittedVersion();

        // 直接调服务：这两个分支是给非 HTTP 调用方兜底的，接口层走不到。
        assertThatThrownBy(() -> service.returnToStudent(TEACHER_ID, versionId, "  "))
            .isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("SUBMISSION_RETURN_REASON_REQUIRED"));
        assertThatThrownBy(() -> service.returnToStudent(TEACHER_ID, versionId, "重".repeat(1001)))
            .isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("SUBMISSION_RETURN_REASON_TOO_LONG"));

        assertThat(statusOf(versionId)).isEqualTo("PROCESSING");
    }

    @Test
    void 退回作废未确认的评分与建议但保留记录() throws Exception {
        long versionId = submittedVersion();
        seedLiveAnswers(versionId);

        returnIt(versionId, "第 3 题看不清，重新拍一下")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("RETURNED"))
            .andExpect(jsonPath("$.data.current").value(false))
            .andExpect(jsonPath("$.data.returnReason").value("第 3 题看不清，重新拍一下"));

        assertThat(invalidatedReasonOf("grading_result", 701)).startsWith("提交被退回：第 3 题看不清");
        assertThat(invalidatedReasonOf("ai_grading_task", 801)).startsWith("提交被退回：");
        // 作废是标记不是删除：教师可能已经看过那条建议，删掉等于把发生过的事抹掉。
        assertThat(countOf("select count(*) from grading_result where id = 701")).isEqualTo(1);
        assertThat(countOf("select count(*) from ai_grading_task where id = 801")).isEqualTo(1);
        assertThat(countOf("select count(*) from submission_audit where submission_version_id = " + versionId
            + " and action = 'RETURNED' and actor_role = 'TEACHER' and actor_id = " + TEACHER_ID)).isEqualTo(1);
    }

    @Test
    void 已确认的评分让退回被拒() throws Exception {
        long versionId = submittedVersion();
        seedLiveAnswers(versionId);
        jdbc.update("update grading_result set status = 'CONFIRMED', confirmed_score = 8 where id = 701");

        returnIt(versionId, "重做")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_ALREADY_FINALIZED"));

        // 教师确认过的评分是人做出的判断，退回不能把它静默作废；版本也不该被动过。
        assertThat(invalidatedReasonOf("grading_result", 701)).isNull();
        assertThat(statusOf(versionId)).isEqualTo("PROCESSING");
        assertThat(currentVersionIds()).containsExactly(versionId);
    }

    @Test
    void 没提交过的版本不能退回() throws Exception {
        long versionId = draftVersionId();

        returnIt(versionId, "重做")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_SUBMITTED"));
    }

    @Test
    void 退回过的版本不能再退一次() throws Exception {
        long versionId = submittedVersion();
        returnIt(versionId, "重做").andExpect(status().isOk());

        returnIt(versionId, "再重做一次")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_ALREADY_RETURNED"));
    }

    // ---------- 退回之后 ----------

    @Test
    void 退回后学生能开新版而旧版原样保留() throws Exception {
        long versionId = submittedVersion();
        returnIt(versionId, "第 3 题看不清").andExpect(status().isOk());

        String history = mvc.perform(get("/api/student/assignments/" + ASSIGNMENT_ID + "/submission/history")
                .header("Authorization", studentToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.canStartNewVersion").value(true))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 退回之后没有当前提交：这一版已经不算数了，新版还没交上来。
        assertThat(dataOf(history).get("currentVersionId")).isNull();

        long next = draftVersionId();
        assertThat(next).isNotEqualTo(versionId);
        assertThat(versionNoOf(next)).isEqualTo(2);

        // 旧版整体留在原地：状态、页面、退回原因都是学生要回看的东西。
        assertThat(statusOf(versionId)).isEqualTo("RETURNED");
        assertThat(returnReasonOf(versionId)).isEqualTo("第 3 题看不清");
        assertThat(countOf("select count(*) from submission_page where submission_version_id = " + versionId))
            .isEqualTo(1);
        assertThat(currentVersionIds()).isEmpty();
    }

    @Test
    void 学生重交后退回新版不动作废上一版的评分() throws Exception {
        long first = submittedVersion();
        // 库里那套答案来自第一版，且还有一条未确认的评分等着教师复核。
        seedLiveAnswers(first);

        long second = draftVersionId();
        upload(second, "改好的.png");
        submitOk(second);
        assertThat(statusOf(first)).isEqualTo("SUPERSEDED");

        returnIt(second, "还是不对").andExpect(status().isOk());

        // 第二版还没经过教师确认，库里那套答案仍然是第一版的。这时作废那些评分，
        // 等于替另一版作答作废评分 —— 而那一版正是要被取代的那一版。
        assertThat(invalidatedReasonOf("grading_result", 701)).isNull();
        assertThat(invalidatedReasonOf("ai_grading_task", 801)).isNull();
        assertThat(countOf("select count(*) from submission_audit where submission_version_id = " + second
            + " and action = 'OCR_STATE_CHANGED'")).isZero();
        // 退回不搬动"库里那套答案来自哪一版"，搬动它的是教师确认答案。
        assertThat(liveVersionId()).isEqualTo(first);
    }

    // ---------- 开始批改 ----------

    @Test
    void 开始批改要求答案已确认() throws Exception {
        long versionId = submittedVersion();

        // 还在等识别/校对（PROCESSING）时锁住，等于锁住一份没人看过的作答。
        lockIt(versionId).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_CONFIRMED"));

        jdbc.update("update submission_version set status = 'CONFIRMED' where id = " + versionId);
        lockIt(versionId).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("LOCKED"));
        assertThat(countOf("select count(*) from submission_version where id = " + versionId
            + " and locked_at is not null")).isEqualTo(1);

        // 连点两下"开始批改"不该报错，也不该多留一条审计。
        lockIt(versionId).andExpect(status().isOk());
        assertThat(countOf("select count(*) from submission_audit where submission_version_id = " + versionId
            + " and action = 'LOCKED' and actor_role = 'TEACHER' and actor_id = " + TEACHER_ID)).isEqualTo(1);
    }

    @Test
    void 不能对已经被取代的版本开始批改() throws Exception {
        long first = submittedVersion();
        jdbc.update("update submission_version set status = 'CONFIRMED' where id = " + first);

        long second = draftVersionId();
        upload(second, "改好的.png");
        submitOk(second);
        assertThat(statusOf(first)).isEqualTo("SUPERSEDED");

        // 锁住一份老师不会再批的作答，学生照样能重交，这道锁就形同虚设。
        lockIt(first).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_CURRENT"));
        assertThat(statusOf(second)).isEqualTo("PROCESSING");
    }

    // ---------- 请求辅助 ----------

    /** 走完"建版 — 传一页 — 提交"，返回提交后的版本 id。 */
    private long submittedVersion() throws Exception {
        long versionId = draftVersionId();
        upload(versionId, "作业.png");
        submitOk(versionId);
        return versionId;
    }

    private long draftVersionId() throws Exception {
        String body = mvc.perform(post("/api/student/assignments/" + ASSIGNMENT_ID + "/submission")
                .header("Authorization", studentToken()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) dataOf(body).get("id")).longValue();
    }

    private void upload(long versionId, String filename) throws Exception {
        mvc.perform(multipart("/api/student/submissions/" + versionId + "/pages")
                .file(new MockMultipartFile("files", filename, "image/png", png(80, 40)))
                .header("Authorization", studentToken()))
            .andExpect(status().isOk());
    }

    private void submitOk(long versionId) throws Exception {
        mvc.perform(post("/api/student/submissions/" + versionId + "/submit")
                .header("Authorization", studentToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    }

    private ResultActions returnIt(long versionId, String reason) throws Exception {
        return returnIt(versionId, reason, teacherToken());
    }

    private ResultActions returnIt(long versionId, String reason, String token) throws Exception {
        return mvc.perform(post("/api/teacher/submissions/" + versionId + "/return")
            .header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("reason", reason))));
    }

    private ResultActions lockIt(long versionId) throws Exception {
        return mvc.perform(post("/api/teacher/submissions/" + versionId + "/lock")
            .header("Authorization", teacherToken()));
    }

    /**
     * 把前置状态摆成"库里那套答案来自这一版，且已有一条未确认的评分与一条待处理的 AI 建议"。
     *
     * <p>这三件事在真实流程里对应：教师确认答案（写 {@code submission.submission_version_id}）、
     * AI 给出建议、教师复核之前。确认接口由后续任务实现，这里只把状态摆好。
     */
    private void seedLiveAnswers(long versionId) {
        long submissionId = submissionIdOf(versionId);
        jdbc.update("""
            insert into student_answer(id, submission_id, question_id, answer_content)
            values (611, ?, 401, '2')
            """, submissionId);
        jdbc.update("""
            insert into grading_result(id, answer_id, source, suggested_score, error_type, score_details, status)
            values (701, 611, 'AI', 8, 'CALCULATION_ERROR', '[]', 'PENDING_REVIEW')
            """);
        jdbc.update("insert into ai_grading_task(id, answer_id, status) values (801, 611, 'PENDING')");
        jdbc.update("update submission set submission_version_id = ? where id = ?", versionId, submissionId);
    }

    // ---------- 断言辅助 ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(String body) throws Exception {
        return (Map<String, Object>) objectMapper.readValue(body, Map.class).get("data");
    }

    private long submissionIdOf(long versionId) {
        Long submissionId = jdbc.queryForObject(
            "select submission_id from submission_version where id = ?", Long.class, versionId);
        return submissionId == null ? 0L : submissionId;
    }

    /** 库里那套答案来自哪一版。V3 时代导入的提交没有版本，这时为 null。 */
    private Long liveVersionId() {
        return jdbc.queryForObject(
            "select submission_version_id from submission where assignment_id = ? and student_id = ?",
            Long.class, ASSIGNMENT_ID, STUDENT_ID);
    }

    /** 某条记录的作废原因；没被作废时是 null。 */
    private String invalidatedReasonOf(String table, long id) {
        return jdbc.queryForObject("select invalidated_reason from " + table + " where id = ?", String.class, id);
    }

    private String returnReasonOf(long versionId) {
        return jdbc.queryForObject("select return_reason from submission_version where id = ?",
            String.class, versionId);
    }

    /** 当前提交是哪几版。多于一条就是错的，所以返回列表而不是单个 id。 */
    private List<Long> currentVersionIds() {
        return jdbc.queryForList("select id from submission_version where is_current = true order by id", Long.class);
    }

    private int versionNoOf(long versionId) {
        Integer versionNo = jdbc.queryForObject(
            "select version_no from submission_version where id = ?", Integer.class, versionId);
        return versionNo == null ? 0 : versionNo;
    }

    private String statusOf(long versionId) {
        return jdbc.queryForObject("select status from submission_version where id = ?", String.class, versionId);
    }

    private int countOf(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private String studentToken() {
        return "Bearer " + jwtService.issueStudent(3, STUDENT_ID, false);
    }

    private String teacherToken() {
        return "Bearer " + jwtService.issueTeacher(1, TEACHER_ID);
    }

    private String otherTeacherToken() {
        return "Bearer " + jwtService.issueTeacher(2, 12);
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    /** 存储根目录。真实写文件，所以不能用 application-test.yml 里那个占位值。 */
    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("homework-submission-return-");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试用存储目录", exception);
        }
    }
}
