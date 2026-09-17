package com.homework.analysis.submission;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.ocr.OcrProvider;
import com.homework.analysis.ocr.OcrRegion;
import com.homework.analysis.ocr.OcrResult;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 确认答案入库：候选变成正式答案的那一步。
 *
 * <p>这个类的重点是**这道门什么时候关上**。{@code student_answer} 一旦有值，成绩、AI 批改、
 * 学情统计就都开始读它，而它们的失败方式是安静的：一份只校对了一半的答卷入库以后，
 * 教师从库里看不出哪道题是机器说的、哪道题是自己看过的。所以每个"不完整"的用例
 * 都同时断言两件事 —— 请求被拒（说了为什么），以及库里一个字节都没写。
 *
 * <p>另一条是被拒之后的退路：拒了但没告诉教师缺哪一道题，等于让他在几十道题里自己找。
 * 所以错误说明里必须带上题号。
 *
 * <p>种子与 {@link AnswerExtractionServiceTest} 一致（同一份作业、同一张空白卷模板），
 * 但这里不再构造识别结果的各种意外——那些已经在上一个类里冻结了，这里默认识别是成功的：
 * 要验的是"识别之后到落库之间"这一段。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubmissionConfirmationTest {

    private static final Path STORAGE_ROOT = createStorageRoot();

    private static final long ASSIGNMENT_ID = 501L;
    private static final long STUDENT_ID = 1001L;
    private static final int PAGE_WIDTH = 1654;
    private static final int PAGE_HEIGHT = 2339;

    /** 模板第 1 页：Q1 在左半幅、Q2 在右半幅；第 2 页只有 Q3。 */
    private static final double[] Q1_BOX = {0.05, 0.10, 0.45, 0.30};
    private static final double[] Q2_BOX = {0.55, 0.10, 0.95, 0.30};
    private static final double[] Q3_BOX = {0.05, 0.10, 0.45, 0.30};

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local-root", STORAGE_ROOT::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean OcrProvider provider;

    /** 这次识别要返回的答卷结果，由每个用例设定。 */
    private OcrResult studentResult;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("""
            insert into app_user(id, username, password_hash, role, enabled) values
              (1, 'teacher', 'x', 'TEACHER', true),
              (2, 'teacher_b', 'x', 'TEACHER', true),
              (3, 'stu_one', 'x', 'STUDENT', true)
            """);
        jdbc.update("""
            insert into teacher(id, user_id, display_name) values
              (11, 1, '教师甲'), (12, 2, '教师乙')
            """);
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C-101', '七年级一班')");
        jdbc.update("""
            insert into student(id, class_id, student_no, name, user_id) values
              (1001, 101, '001', '张三', 3)
            """);
        jdbc.update("""
            insert into assignment(id, teacher_id, class_id, title, status, published_at, due_at, version) values
              (501, 11, 101, '第一单元作业', 'PUBLISHED', current_timestamp(3), '2030-01-01 00:00:00', 1)
            """);
        jdbc.update("""
            insert into knowledge_point(id, teacher_id, code, name, grade, active)
            values (301, 11, 'ALG', '代数', 7, true)
            """);
        jdbc.update("""
            insert into question(id, teacher_id, question_code, type, content, standard_answer, total_score,
                primary_knowledge_point_id, accepted_answers) values
              (401, 11, '1', 'SOLUTION', '第 1 题', 'x=2', 8, 301, '[]'),
              (402, 11, '2', 'SOLUTION', '第 2 题', 'x=5', 8, 301, '[]'),
              (403, 11, '3', 'SOLUTION', '第 3 题', 'x=7', 8, 301, '[]')
            """);
        jdbc.update("""
            insert into assignment_question(assignment_id, question_id, question_order) values
              (501, 401, 1), (501, 402, 2), (501, 403, 3)
            """);
        seedTemplate();
        studentResult = answerSheet(Q1_BOX, "第一题答案", Q2_BOX, "第二题答案");

        when(provider.analyze(any())).thenAnswer(invocation -> studentResult);
    }

    // ---------- 用例 ----------

    @Test
    void 确认之后每道题的作答都进了正式答案() throws Exception {
        long versionId = reviewedVersion();

        JsonNode data = confirmOk(versionId);

        assertThat(data.get("confirmed").asBoolean()).isTrue();
        assertThat(data.get("status").asText()).isEqualTo("CONFIRMED");
        // 每道作业题一行，包括学生没作答的第三题：批改会按题行取答案，
        // 缺行与"学生没交"在成绩里长得一模一样。
        assertThat(answerCount()).isEqualTo(3);
        assertThat(answerContent("1")).isEqualTo("第一题答案");
        assertThat(answerContent("2")).isEqualTo("第二题答案");
        // 标为空白是一个**已经确认的结论**，在库里就是空串。
        assertThat(answerContent("3")).isEmpty();
        // 正式答案的归属版本跟着挪：批改读的是这一版的答案，不是上一版的。
        assertThat(liveAnswerVersionId()).isEqualTo(versionId);
        assertThat(versionStatus(versionId)).isEqualTo("CONFIRMED");
        assertThat(auditCount("ANSWERS_CONFIRMED")).isEqualTo(1);
    }

    @Test
    void 答案图挂在正式答案上并指回它的来源() throws Exception {
        long versionId = reviewedVersion();

        confirmOk(versionId);

        // 有作答的两道题各有图；空白的那道没有。
        assertThat(assetCount("1")).isEqualTo(1);
        assertThat(assetCount("2")).isEqualTo(1);
        assertThat(assetCount("3")).isZero();
        // 教师的复核对象是那张裁剪图，所以来源必须留得住：哪块区域、哪一页。
        assertThat(jdbc.queryForObject("""
                select count(*) from student_answer_asset a
                join student_answer sa on sa.id = a.answer_id
                join question q on q.id = sa.question_id
                where q.question_code = '1' and a.document_region_id is not null
                  and a.submission_page_id is not null and a.role = 'SOURCE_CROP'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void 还有候选没校对时整份都不入库() throws Exception {
        // 只识别、不逐条校对：候选默认是 PENDING，机器读出来的东西还没经过人的眼睛。
        long versionId = recognisedVersion();

        JsonNode error = confirmError(versionId, "ANSWER_REVIEW_PENDING");

        // 缺哪一道要说出来：只说"有题没校对"，教师得在几十道题里自己找。
        assertThat(error.get("message").asText()).contains("第 3 题");
        assertThat(answerCount()).isZero();
        assertThat(liveAnswerVersionId()).isNull();
        // 版本状态也没动：被拒的确认不该把这一版推到 CONFIRMED。
        assertThat(versionStatus(versionId)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void 还没识别就跑确认会被挡住() throws Exception {
        long versionId = submitAnswerSheet(png(400, 600), "答卷.png");

        JsonNode error = confirmError(versionId, "ANSWER_CANDIDATES_MISSING");

        assertThat(error.get("message").asText()).contains("识别");
        assertThat(answerCount()).isZero();
    }

    @Test
    void 确认之前作业里多了一道题就整份都不入库() throws Exception {
        long versionId = reviewedVersion();
        // 教师在识别之后往作业里加了一道题：这一版没有它的候选，落库就会缺一道题的答案，
        // 而批改会把这行缺失当成"学生没交"。
        jdbc.update("""
            insert into question(id, teacher_id, question_code, type, content, standard_answer, total_score,
                primary_knowledge_point_id, accepted_answers)
            values (404, 11, '4', 'SOLUTION', '第 4 题', 'x=9', 6, 301, '[]')
            """);
        jdbc.update("insert into assignment_question(assignment_id, question_id, question_order) values (501, 404, 4)");

        JsonNode error = confirmError(versionId, "ANSWER_CANDIDATE_INCOMPLETE");

        assertThat(error.get("message").asText()).contains("第 4 题");
        assertThat(answerCount()).isZero();
    }

    @Test
    void 既没内容也没有标为空白时拒绝入库() throws Exception {
        long versionId = recognisedVersion();
        JsonNode data = answersOf(versionId);
        // 教师把第 1 题的识别文字清空了，却没有标成"学生没作答"。落库会得到一条空串，
        // 读的人只会得出"学生写了空白"这个与教师判断相反的结论。
        patchAnswer(versionId, candidateOf(data, "1"), "{\"version\":0,\"answerText\":\"\",\"reviewStatus\":\"CONFIRMED\"}");
        confirmCandidates(versionId, answersOf(versionId), "1");

        JsonNode error = confirmError(versionId, "ANSWER_CONTENT_EMPTY");

        assertThat(error.get("message").asText()).contains("第 1 题");
        assertThat(answerCount()).isZero();
    }

    @Test
    void 重复确认不会写出第二份答案() throws Exception {
        long versionId = reviewedVersion();
        confirmOk(versionId);
        Timestamp confirmedAt = answerUpdatedAt("1");
        String assetsBefore = assetSnapshot();

        // 教师点完确认之后网络断了、刷新页面又点了一次：这不是错误，也不该是第二次入库。
        confirmOk(versionId);

        assertThat(answerCount()).isEqualTo(3);
        assertThat(assetSnapshot()).isEqualTo(assetsBefore);
        assertThat(auditCount("ANSWERS_CONFIRMED")).isEqualTo(1);
        // 时间戳不动：再写一遍会把"教师什么时候确认的"推到现在，那件事就从库里消失了。
        assertThat(answerUpdatedAt("1")).isEqualTo(confirmedAt);
    }

    @Test
    void 确认之后不能再改候选也不能重新识别() throws Exception {
        long versionId = reviewedVersion();
        confirmOk(versionId);

        // 答案已经是正式数据了，再改候选不会同步到 student_answer，只会让教师以为改掉了。
        mvc.perform(patch("/api/teacher/submissions/" + versionId + "/answers/"
                + candidateIdOf(answersOf(versionId), "1"))
                .header("Authorization", teacherToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"answerText\":\"又改了\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_ANSWERS_CONFIRMED"));
        // 重新识别更不行：候选会换主键，教师刚确认过的那些会凭空消失。
        mvc.perform(post("/api/teacher/submissions/" + versionId + "/process")
                .header("Authorization", teacherToken()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_ANSWERS_CONFIRMED"));

        assertThat(answerContent("1")).isEqualTo("第一题答案");
    }

    @Test
    void 别的教师不能确认这份答卷() throws Exception {
        long versionId = reviewedVersion();

        // 版本 id 是连续数字：区分"不存在"与"不是你的"等于把别人的答卷变成可枚举的信息。
        mvc.perform(post("/api/teacher/submissions/" + versionId + "/confirm")
                .header("Authorization", otherTeacherToken()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_VERSION_NOT_FOUND"));

        assertThat(answerCount()).isZero();
    }

    @Test
    void 学生重交之后确认会把正式答案换成新一版() throws Exception {
        long firstVersion = reviewedVersion();
        confirmOk(firstVersion);
        // 学生重交：第 1 题改过了。
        studentResult = answerSheet(Q1_BOX, "改过的第一题答案", Q2_BOX, "第二题答案");
        mvc.perform(post("/api/teacher/submissions/" + firstVersion + "/return")
                .header("Authorization", teacherToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"第 1 题看不清，重拍\"}"))
            .andExpect(status().isOk());

        long secondVersion = reviewedVersion();
        confirmOk(secondVersion);

        // 还是那三行：同一道题在同一个 submission 下只有一行答案，重交是改它，不是再加一套。
        assertThat(answerCount()).isEqualTo(3);
        assertThat(answerContent("1")).isEqualTo("改过的第一题答案");
        assertThat(liveAnswerVersionId()).isEqualTo(secondVersion);
        // 上一版裁出来的图不留在这一行上：复核页并排显示两张时，教师分不出哪张是现在的。
        assertThat(assetCount("1")).isEqualTo(1);
        assertThat(versionStatus(firstVersion)).isEqualTo("RETURNED");
    }

    // ---------- 流程 ----------

    /**
     * 一份已经确认入库的空白卷，只为了给答卷提供题框几何。
     *
     * <p>与 {@link AnswerExtractionServiceTest} 同一份种子：模板的真实来源是
     * {@code paper_question_candidate} 与 {@code document_region}，直插它们等于把
     * "题框从哪来"这件事固定在它本来的形状上。
     */
    private void seedTemplate() {
        jdbc.update("""
            insert into stored_file(id, teacher_id, storage_key, original_name, mime_type, size_bytes, sha256)
            values (900, 11, 'paper/original.pdf', '空白卷.pdf', 'application/pdf', 3, 'a')
            """);
        jdbc.update("""
            insert into document_upload(id, document_kind, teacher_id, assignment_id, original_file_id,
                status, page_count) values (700, 'EXAM_PAPER', 11, 501, 900, 'CONFIRMED', 2)
            """);
        jdbc.update("""
            insert into document_page(id, document_id, page_no, page_file_id) values
              (701, 700, 1, 900), (702, 700, 2, 900)
            """);
        jdbc.update("""
            insert into document_region(id, page_id, region_type, x, y, width, height) values
              (801, 701, 'ANSWER_BLOCK', 0.05, 0.10, 0.40, 0.20),
              (802, 701, 'ANSWER_BLOCK', 0.55, 0.10, 0.40, 0.20),
              (803, 702, 'ANSWER_BLOCK', 0.05, 0.10, 0.40, 0.20)
            """);
        jdbc.update("""
            insert into paper_question_candidate(id, assignment_id, document_id, document_kind, order_no,
                question_code, source_region_ids, review_status, version, asset_region_ids) values
              (901, 501, 700, 'EXAM_PAPER', 1, '1', '[801]', 'CONFIRMED', 1, '[]'),
              (902, 501, 700, 'EXAM_PAPER', 2, '2', '[802]', 'CONFIRMED', 1, '[]'),
              (903, 501, 700, 'EXAM_PAPER', 3, '3', '[803]', 'CONFIRMED', 1, '[]')
            """);
    }

    /** 建版、传页、提交，返回这一版的 id。 */
    private long submitAnswerSheet(byte[] content, String filename) throws Exception {
        String body = mvc.perform(post("/api/student/assignments/" + ASSIGNMENT_ID + "/submission")
                .header("Authorization", studentToken()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long versionId = objectMapper.readTree(body).get("data").get("id").asLong();
        mvc.perform(multipart("/api/student/submissions/" + versionId + "/pages")
                .file(new MockMultipartFile("files", filename, "image/png", content))
                .header("Authorization", studentToken()))
            .andExpect(status().isOk());
        mvc.perform(post("/api/student/submissions/" + versionId + "/submit")
                .header("Authorization", studentToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
        return versionId;
    }

    /** 已提交、已识别，但一条候选都还没校对。 */
    private long recognisedVersion() throws Exception {
        long versionId = submitAnswerSheet(png(400, 600), "答卷.png");
        process(versionId);
        return versionId;
    }

    /** 已识别、教师也逐条确认过，等着最后那一次"确认入库"。 */
    private long reviewedVersion() throws Exception {
        long versionId = recognisedVersion();
        confirmCandidates(versionId, answersOf(versionId));
        return versionId;
    }

    private JsonNode process(long versionId) throws Exception {
        return dataOf(mvc.perform(post("/api/teacher/submissions/" + versionId + "/process")
                .header("Authorization", teacherToken()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode answersOf(long versionId) throws Exception {
        return dataOf(mvc.perform(get("/api/teacher/submissions/" + versionId + "/answers")
                .header("Authorization", teacherToken()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode confirmOk(long versionId) throws Exception {
        return dataOf(mvc.perform(post("/api/teacher/submissions/" + versionId + "/confirm")
                .header("Authorization", teacherToken()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode confirmError(long versionId, String code) throws Exception {
        String body = mvc.perform(post("/api/teacher/submissions/" + versionId + "/confirm")
                .header("Authorization", teacherToken()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value(code))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("error");
    }

    /** 逐条确认。{@code skipCodes} 里的题已经由用例自己改过了，不再重发一次。 */
    private void confirmCandidates(long versionId, JsonNode data, String... skipCodes) throws Exception {
        List<String> skip = List.of(skipCodes);
        for (JsonNode candidate : data.get("candidates")) {
            if (skip.contains(candidate.get("questionCode").asText())) {
                continue;
            }
            confirmCandidate(versionId, candidate);
        }
    }

    /**
     * 教师认下这一条。
     *
     * <p>空白的那道题带上 {@code blank: true}：教师在校对界面上的动作是"确认学生确实没作答"，
     * 而不是"这条我不管了"。两者在界面文案上不同，在这里也必须走同一条请求。
     */
    private void confirmCandidate(long versionId, JsonNode candidate) throws Exception {
        String body = candidate.get("blank").asBoolean()
            ? "{\"version\":" + candidate.get("version").asInt()
                + ",\"blank\":true,\"reviewStatus\":\"CONFIRMED\"}"
            : "{\"version\":" + candidate.get("version").asInt() + ",\"reviewStatus\":\"CONFIRMED\"}";
        patchAnswer(versionId, candidate, body);
    }

    private void patchAnswer(long versionId, JsonNode candidate, String body) throws Exception {
        mvc.perform(patch("/api/teacher/submissions/" + versionId + "/answers/"
                + candidate.get("candidateId").asLong())
                .header("Authorization", teacherToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
    }

    private static long candidateIdOf(JsonNode data, String questionCode) {
        return candidateOf(data, questionCode).get("candidateId").asLong();
    }

    private static JsonNode candidateOf(JsonNode data, String questionCode) {
        for (JsonNode candidate : data.get("candidates")) {
            if (questionCode.equals(candidate.get("questionCode").asText())) {
                return candidate;
            }
        }
        throw new AssertionError("没有题号为 " + questionCode + " 的候选");
    }

    private JsonNode dataOf(String body) throws Exception {
        return objectMapper.readTree(body).get("data");
    }

    // ---------- 库里读 ----------

    private int answerCount() {
        return count("""
            select count(*) from student_answer sa
            join submission s on s.id = sa.submission_id
            where s.assignment_id = 501
            """);
    }

    private String answerContent(String questionCode) {
        return jdbc.queryForObject("""
            select sa.answer_content from student_answer sa
            join question q on q.id = sa.question_id
            join submission s on s.id = sa.submission_id
            where s.assignment_id = 501 and q.question_code = ?
            """, String.class, questionCode);
    }

    private Timestamp answerUpdatedAt(String questionCode) {
        return jdbc.queryForObject("""
            select sa.updated_at from student_answer sa
            join question q on q.id = sa.question_id
            where q.question_code = ?
            """, Timestamp.class, questionCode);
    }

    /** 库里那套答案的来源版本；为空表示还没有任何答案。 */
    private Long liveAnswerVersionId() {
        return jdbc.queryForObject("""
            select submission_version_id from submission
            where assignment_id = 501 and student_id = 1001
            """, Long.class);
    }

    private String versionStatus(long versionId) {
        return jdbc.queryForObject("select status from submission_version where id = ?",
            String.class, versionId);
    }

    private int auditCount(String action) {
        return count("select count(*) from submission_audit where action = '" + action + "'");
    }

    private int assetCount(String questionCode) {
        return count("""
            select count(*) from student_answer_asset a
            join student_answer sa on sa.id = a.answer_id
            join question q on q.id = sa.question_id
            where q.question_code = '%s'
            """.formatted(questionCode));
    }

    /** 当前全部答案图的一份快照，用来断言"再确认一次没有多出图来"。 */
    private String assetSnapshot() {
        return jdbc.queryForList("""
            select a.id, a.answer_id, a.submission_version_id, a.document_region_id, a.file_id, a.sort_order
            from student_answer_asset a order by a.id
            """).toString();
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    // ---------- 识别结果构造 ----------

    /**
     * 一页答卷：两个题框各有一块作答，第三题没写。
     *
     * <p>题框几何来自模板，区域落在框的左上角内侧。第三题留空是有意的：一路确认到落库的
     * 用例必须覆盖"学生没作答"这一种，否则空白的处理只在被拒的路径上验过。
     */
    private static OcrResult answerSheet(double[] firstBox, String firstText,
                                         double[] secondBox, String secondText) {
        List<OcrRegion> regions = List.of(
            inside(firstBox, "s1", firstText),
            inside(secondBox, "s2", secondText));
        return new OcrResult("v1", "paddleocr", "3.7.0",
            List.of(new OcrResult.Page(1, PAGE_WIDTH, PAGE_HEIGHT, regions, 1, 0.93, null)));
    }

    private static OcrRegion inside(double[] box, String externalId, String text) {
        return new OcrRegion(externalId, "TEXT_BLOCK", box[0] + 0.05, box[1] + 0.05, 0.25, 0.05,
            text, null, 0.95);
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    // ---------- 令牌 ----------

    private String teacherToken() {
        return "Bearer " + jwtService.issueTeacher(1, 11);
    }

    private String otherTeacherToken() {
        return "Bearer " + jwtService.issueTeacher(2, 12);
    }

    private String studentToken() {
        return "Bearer " + jwtService.issueStudent(3, STUDENT_ID, false);
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("homework-answer-confirmation-");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试用存储目录", exception);
        }
    }
}
