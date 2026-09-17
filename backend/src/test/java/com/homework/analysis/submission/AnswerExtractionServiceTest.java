package com.homework.analysis.submission;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.ocr.OcrProvider;
import com.homework.analysis.ocr.OcrRegion;
import com.homework.analysis.ocr.OcrRequest;
import com.homework.analysis.ocr.OcrResult;
import com.homework.analysis.testing.TestDatabaseCleaner;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * 答卷的答案抽取：归属、警告，以及"绝不静默丢弃"。
 *
 * <p>这个类的重点不是"识别成功时候选长什么样"，而是**每一种对不上号的情形留下了什么**。
 * 答卷链路没有"识别错了"这种整体失败：漏一页、拍重一页、区域落在题框之外都是常态，
 * 而它们的共同失败模式是安静——教师看到的是一份看起来很正常的答卷，学生丢的分却没人知道
 * 是从哪来的。所以每个用例都断言"系统说出了哪一句话"，而不是只看候选有没有生成。
 *
 * <p>种子数据里没有 {@code submission}：建版、传页、提交全部走学生接口，与真实路径一致。
 * 模板那一边走 SQL 直插（{@code paper_question_candidate} + {@code document_region} 几何），
 * 因为要控制的是题框的精确坐标，用整卷导入流程种的模板没法把边界压到歧义阈值上。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnswerExtractionServiceTest {

    private static final Path STORAGE_ROOT = createStorageRoot();

    private static final long ASSIGNMENT_ID = 501L;
    private static final long STUDENT_ID = 1001L;
    /** 空白卷文档；它的第 1、2 页分别载着 Q1/Q2 与 Q3 的题框。 */
    private static final long PAPER_DOCUMENT_ID = 700L;
    private static final int PAGE_WIDTH = 1654;
    private static final int PAGE_HEIGHT = 2339;

    /** 模板第 1 页：Q1 在左半幅、Q2 在右半幅。两个框之间留出 0.45~0.55 的空档。 */
    private static final double[] Q1_BOX = {0.05, 0.10, 0.45, 0.30};
    private static final double[] Q2_BOX = {0.55, 0.10, 0.95, 0.30};
    /** 模板第 2 页：只有 Q3。 */
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

    /** 送到引擎的请求，用来断言模板确实跟着答卷一起发出去了。 */
    private final List<OcrRequest> requests = new ArrayList<>();

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        requests.clear();
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
        // 三道题已经在题库里、也挂在这份作业上——这正是"模板可用"的前提。
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
        studentResult = answerSheet(templatePage(1, regions(
            region("s1", "TEXT_BLOCK", 0.10, 0.15, 0.25, 0.05, "第一题答案", null, 0.95),
            region("s2", "TEXT_BLOCK", 0.60, 0.15, 0.25, 0.05, "第二题答案", null, 0.95))));

        when(provider.analyze(any())).thenAnswer(invocation -> {
            OcrRequest request = invocation.getArgument(0);
            requests.add(request);
            return studentResult;
        });
    }

    // ---------- 用例 ----------

    @Test
    void 按题框把区域归到每道题上并把模板带给了引擎() throws Exception {
        long versionId = submitAnswerSheet(png(400, 600), "答卷.png");

        JsonNode data = process(versionId);

        // 每道作业题各有且只有一条候选——包括学生没作答的第三题。
        assertThat(codesOf(data)).containsExactly("1", "2", "3");
        JsonNode first = candidateOf(data, "1");
        assertThat(first.get("answerText").asText()).isEqualTo("第一题答案");
        assertThat(first.get("blank").asBoolean()).isFalse();
        assertThat(first.get("regions")).hasSize(1);
        assertThat(first.get("regions").get(0).get("candidateId").asLong())
            .isEqualTo(first.get("candidateId").asLong());
        // 区域报的是学生排的页码，不是文档里的页码：教师嘴里说的"第几页"是前者。
        assertThat(first.get("regions").get(0).get("pageNo").asLong()).isEqualTo(1);
        assertThat(candidateOf(data, "2").get("answerText").asText()).isEqualTo("第二题答案");

        JsonNode third = candidateOf(data, "3");
        assertThat(third.get("blank").asBoolean()).isTrue();
        assertThat(third.get("regions")).isEmpty();
        assertThat(candidateWarnings(third)).contains("ANSWER_BLANK");

        // 学生答卷必须先拿到模板：手写答案上没有可读的题号，归属只能靠"落在哪个框里"。
        OcrRequest sent = lastStudentRequest();
        assertThat(sent.template()).isNotNull();
        assertThat(sent.template().pages()).hasSize(2);
        assertThat(sent.template().pages().get(0).questions())
            .extracting(OcrRequest.QuestionBox::questionCode).containsExactly("1", "2");
        assertThat(sent.template().pages().get(1).questions())
            .extracting(OcrRequest.QuestionBox::questionCode).containsExactly("3");
        assertThat(sent.template().pages().get(0).questions().get(0).x()).isEqualTo(Q1_BOX[0]);
    }

    @Test
    void 落在题框外的区域留在界面上并给出警告() throws Exception {
        studentResult = answerSheet(templatePage(1, regions(
            region("s1", "TEXT_BLOCK", 0.10, 0.15, 0.25, 0.05, "第一题答案", null, 0.95),
            region("out", "TEXT_BLOCK", 0.02, 0.80, 0.30, 0.05, "写在题框外的字", null, 0.93))));

        JsonNode data = process(submitAnswerSheet(png(400, 600), "答卷.png"));

        assertThat(versionWarningCodes(data)).contains("ANSWER_OUT_OF_BOX");
        assertThat(versionWarnings(data).toString()).contains("第 1 页");
        // 关键的一半：这块区域没有被丢掉，它在页面上、且明确标着"还没归到任何题"。
        JsonNode unassigned = unassignedRegions(data).get(0);
        assertThat(unassigned.get("ocrText").asText()).isEqualTo("写在题框外的字");
        assertThat(unassigned.get("candidateId")).isNull();
    }

    @Test
    void 同一块区域压在两个题框上时提示归属不确定() throws Exception {
        studentResult = answerSheet(templatePage(1, regions(
            // 横跨 Q1（0.05~0.45）与 Q2（0.55~0.95）两块框：各自压住约三成。
            region("span", "TEXT_BLOCK", 0.34, 0.15, 0.31, 0.05, "写在两题之间", null, 0.94))));

        JsonNode data = process(submitAnswerSheet(png(400, 600), "答卷.png"));

        // 不自动二选一，但也不许两边都不选：它必须挂在某道题上并带着"归属不确定"。
        JsonNode owner = candidateOf(data, "1");
        assertThat(candidateWarnings(owner)).contains("QUESTION_AMBIGUOUS");
        assertThat(owner.get("regions")).hasSize(1);
        assertThat(unassignedRegions(data)).isEmpty();
    }

    @Test
    void 两页都对上同一模板页时提示重复并对跨页作答给出警告() throws Exception {
        studentResult = answerSheet(
            templatePage(1, regions(
                region("p1", "TEXT_BLOCK", 0.10, 0.15, 0.25, 0.05, "第一页上的作答", null, 0.95))),
            templatePage(1, regions(
                region("p2", "TEXT_BLOCK", 0.12, 0.20, 0.25, 0.05, "第二页上的续写", null, 0.95))));

        JsonNode data = process(submitAnswerSheet(pdf(2), "答卷.pdf"));

        // "两页对上了同一页"是整份答卷的问题，"这道题的作答跨了页"是某一道题的问题——
        // 两个码属于两层，处理动作也不同（重拍一页 vs 别只批一半）。
        assertThat(versionWarningCodes(data)).contains("PAGE_DUPLICATE");
        JsonNode first = candidateOf(data, "1");
        assertThat(candidateWarnings(first)).contains("CROSS_PAGE_ANSWER");
        // 两页的区域都还在那道题下面：学生分两处写，教师不能只批一半。
        assertThat(first.get("regions")).hasSize(2);
        assertThat(first.get("regions").get(0).get("pageNo").asLong()).isEqualTo(1);
        assertThat(first.get("regions").get(1).get("pageNo").asLong()).isEqualTo(2);
    }

    @Test
    void 模板上学生没交的那一页会提示缺页且那些题落成空白() throws Exception {
        studentResult = answerSheet(templatePage(2, regions(
            region("s3", "TEXT_BLOCK", 0.10, 0.15, 0.25, 0.05, "第三题答案", null, 0.95))));

        JsonNode data = process(submitAnswerSheet(png(400, 600), "答卷.png"));

        assertThat(versionWarningCodes(data)).contains("PAGE_MISSING");
        assertThat(candidateOf(data, "3").get("answerText").asText()).isEqualTo("第三题答案");
        // 模板第 1 页没交，那上面的两道题只能是空白——但必须说出来，而不是安静地算 0 分。
        assertThat(candidateWarnings(candidateOf(data, "1"))).contains("ANSWER_BLANK");
        assertThat(candidateWarnings(candidateOf(data, "2"))).contains("ANSWER_BLANK");
    }

    @Test
    void 对不上模板的页提示配准失败并把区域留作未归属() throws Exception {
        studentResult = answerSheet(templatePage(null, regions(
            region("s1", "TEXT_BLOCK", 0.10, 0.15, 0.25, 0.05, "认不出是哪一页", null, 0.95))));

        JsonNode data = process(submitAnswerSheet(png(400, 600), "答卷.png"));

        assertThat(versionWarningCodes(data)).contains("TEMPLATE_MISMATCH");
        // 没对上模板就没有题框可比，这块区域只能等教师手工归类。
        assertThat(unassignedRegions(data)).hasSize(1);
        assertThat(candidateWarnings(candidateOf(data, "1"))).contains("ANSWER_BLANK");
    }

    @Test
    void 没有模板时提示缺少模板且题号仍能定归属() throws Exception {
        // 题库里没有这份作业的题目 → 模板取不到。识别照跑，只是没有题框可比。
        jdbc.update("delete from paper_question_candidate");
        studentResult = answerSheet(templatePage(null, regions(
            region("s1", "TEXT_BLOCK", 0.10, 0.15, 0.25, 0.05, "第一题答案", null, 0.95, "1"))));

        long versionId = submitAnswerSheet(png(400, 600), "答卷.png");
        JsonNode data = process(versionId);

        assertThat(lastStudentRequest().template()).isNull();
        assertThat(versionWarningCodes(data)).contains("TEMPLATE_MISSING");
        // 引擎在这块像素上读到了题号——那是证据，比几何更硬。
        assertThat(candidateOf(data, "1").get("answerText").asText()).isEqualTo("第一题答案");
        assertThat(unassignedRegions(data)).isEmpty();
    }

    @Test
    void 公式置信度低时单独提示() throws Exception {
        studentResult = answerSheet(templatePage(1, regions(
            region("s1", "TEXT_BLOCK", 0.10, 0.15, 0.25, 0.05, "第一题答案", null, 0.95),
            region("s2", "FORMULA", 0.10, 0.22, 0.20, 0.04, null, "x=2", 0.55))));

        JsonNode data = process(submitAnswerSheet(png(400, 600), "答卷.png"));

        // 公式认错一个符号整道题就变成另一个意思，所以它与"文字认不准"分开报。
        assertThat(candidateWarnings(candidateOf(data, "1")))
            .contains("LOW_CONFIDENCE_FORMULA")
            .doesNotContain("LOW_CONFIDENCE");
    }

    @Test
    void 重复识别不会覆盖教师已经改过的候选() throws Exception {
        long versionId = submitAnswerSheet(png(400, 600), "答卷.png");
        JsonNode data = process(versionId);
        long candidateId = candidateOf(data, "1").get("candidateId").asLong();

        mvc.perform(patch("/api/teacher/submissions/" + versionId + "/answers/" + candidateId)
                .header("Authorization", teacherToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"answerText\":\"教师改过的答案\",\"reviewStatus\":\"CONFIRMED\"}"))
            .andExpect(status().isOk());

        JsonNode again = process(versionId);

        JsonNode first = candidateOf(again, "1");
        assertThat(first.get("candidateId").asLong()).isEqualTo(candidateId);
        assertThat(first.get("answerText").asText()).isEqualTo("教师改过的答案");
        assertThat(first.get("reviewStatus").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void 改派到别的题会把那道题原来的候选换过来() throws Exception {
        long versionId = submitAnswerSheet(png(400, 600), "答卷.png");
        JsonNode data = process(versionId);
        long firstId = candidateOf(data, "1").get("candidateId").asLong();

        // 学生把第 2 题的答案写进了第 1 题的框：两行的作答要调个位置。
        JsonNode swapped = dataOf(mvc.perform(patch("/api/teacher/submissions/" + versionId
                + "/answers/" + firstId)
                .header("Authorization", teacherToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"questionId\":402}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        // 每道题仍然恰好一条候选、按作业题目顺序排列——互换不能把某道题挤没了。
        assertThat(codesOf(swapped)).containsExactly("1", "2", "3");
        assertThat(candidateOf(swapped, "2").get("answerText").asText()).isEqualTo("第一题答案");
        assertThat(candidateOf(swapped, "1").get("answerText").asText()).isEqualTo("第二题答案");
        // 换的是"属于哪道题"，不是内容：区域跟着它描述的那块作答一起走。
        assertThat(candidateOf(swapped, "2").get("regions").get(0).get("cropFileId").asLong())
            .isEqualTo(candidateOf(data, "1").get("regions").get(0).get("cropFileId").asLong());
        // 两条候选都被改过，版本号都得往前推：本题这条动了两次（自己那次编辑 + 互换），
        // 对面那条动了一次。否则教师手上那份旧副本还能把互换覆盖回去。
        assertThat(candidateOf(swapped, "1").get("version").asInt()).isEqualTo(2);
        assertThat(candidateOf(swapped, "2").get("version").asInt()).isEqualTo(1);
        // 互换之后两边都要重新校对：两行都换掉了一半，没有哪一边还是教师看过的那一对。
        assertThat(candidateOf(swapped, "1").get("reviewStatus").asText()).isEqualTo("PENDING");
    }

    @Test
    void 改派到已经校对确认过的题会被挡住() throws Exception {
        long versionId = submitAnswerSheet(png(400, 600), "答卷.png");
        JsonNode data = process(versionId);
        mvc.perform(patch("/api/teacher/submissions/" + versionId + "/answers/"
                + candidateOf(data, "2").get("candidateId").asLong())
                .header("Authorization", teacherToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"reviewStatus\":\"CONFIRMED\"}"))
            .andExpect(status().isOk());

        mvc.perform(patch("/api/teacher/submissions/" + versionId + "/answers/"
                + candidateOf(data, "1").get("candidateId").asLong())
                .header("Authorization", teacherToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"questionId\":402}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ANSWER_COUNTERPART_CONFIRMED"));
    }

    @Test
    void 改派到一道还没有候选的题是一次普通的移动() throws Exception {
        long versionId = submitAnswerSheet(png(400, 600), "答卷.png");
        JsonNode data = process(versionId);
        // 教师在识别之后往作业里加了一道题：它还没有候选，所以没有东西可以换。
        jdbc.update("""
            insert into question(id, teacher_id, question_code, type, content, standard_answer, total_score,
                primary_knowledge_point_id, accepted_answers)
            values (404, 11, '4', 'SOLUTION', '第 4 题', 'x=9', 6, 301, '[]')
            """);
        jdbc.update("insert into assignment_question(assignment_id, question_id, question_order) values (501, 404, 4)");

        JsonNode moved = dataOf(mvc.perform(patch("/api/teacher/submissions/" + versionId
                + "/answers/" + candidateOf(data, "1").get("candidateId").asLong())
                .header("Authorization", teacherToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"questionId\":404}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(codesOf(moved)).containsExactly("2", "3", "4");
        assertThat(candidateOf(moved, "4").get("answerText").asText()).isEqualTo("第一题答案");
        assertThat(candidateOf(moved, "4").get("questionOrder").asInt()).isEqualTo(4);
    }

    @Test
    void 还没提交的版本不能识别() throws Exception {
        long versionId = draftVersionId();

        mvc.perform(post("/api/teacher/submissions/" + versionId + "/process")
                .header("Authorization", teacherToken()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_SUBMITTED"));
    }

    @Test
    void 别的教师的作业下的答卷返回404() throws Exception {
        long versionId = submitAnswerSheet(png(400, 600), "答卷.png");

        // 版本 id 是连续数字，区分"不存在"与"不是你的"等于把别人的答卷变成可枚举的信息。
        mvc.perform(get("/api/teacher/submissions/" + versionId + "/answers")
                .header("Authorization", otherTeacherToken()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_VERSION_NOT_FOUND"));
    }

    @Test
    void 标准答案单独取且不在校对视图里() throws Exception {
        long versionId = submitAnswerSheet(png(400, 600), "答卷.png");
        JsonNode data = process(versionId);

        // 对着标准答案看学生写的字会让人"看出"那个答案，判分就不再独立。
        assertThat(data.toString()).doesNotContain("standardAnswer");

        String body = mvc.perform(get("/api/teacher/submissions/" + versionId + "/reference-answers")
                .header("Authorization", teacherToken()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode answers = objectMapper.readTree(body).get("data");
        assertThat(answers).hasSize(3);
        assertThat(answers.get(0).get("questionCode").asText()).isEqualTo("1");
        assertThat(answers.get(0).get("standardAnswer").asText()).isEqualTo("x=2");
    }

    // ---------- 流程与辅助 ----------

    /**
     * 一份已经确认入库的空白卷，只为了给答卷提供题框几何。
     *
     * <p>不走整卷导入的接口：这里要的是框的精确坐标（压到歧义阈值的边界上），
     * 而导入流程种出来的框由识别结果的分组决定。{@code paper_question_candidate} 与
     * {@code document_region} 是模板的两个真实来源，直插它们等于把"模板从哪来"这件事
     * 固定在它本来的形状上。
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

    private long draftVersionId() throws Exception {
        String body = mvc.perform(post("/api/student/assignments/" + ASSIGNMENT_ID + "/submission")
                .header("Authorization", studentToken()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data").get("id").asLong();
    }

    /** 建版、传页、提交，返回这一版的 id。 */
    private long submitAnswerSheet(byte[] content, String filename) throws Exception {
        long versionId = draftVersionId();
        String contentType = filename.endsWith(".pdf") ? "application/pdf" : "image/png";
        mvc.perform(multipart("/api/student/submissions/" + versionId + "/pages")
                .file(new MockMultipartFile("files", filename, contentType, content))
                .header("Authorization", studentToken()))
            .andExpect(status().isOk());
        mvc.perform(post("/api/student/submissions/" + versionId + "/submit")
                .header("Authorization", studentToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
        return versionId;
    }

    private JsonNode process(long versionId) throws Exception {
        return dataOf(mvc.perform(post("/api/teacher/submissions/" + versionId + "/process")
                .header("Authorization", teacherToken()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode dataOf(String body) throws Exception {
        return objectMapper.readTree(body).get("data");
    }

    // ---------- 断言辅助 ----------

    /** 候选的题号，按返回顺序。它同时验证了"按作业题目顺序排列"。 */
    private static List<String> codesOf(JsonNode data) {
        return data.get("candidates").findValues("questionCode").stream().map(JsonNode::asText).toList();
    }

    private static JsonNode candidateOf(JsonNode data, String questionCode) {
        for (JsonNode candidate : data.get("candidates")) {
            if (questionCode.equals(candidate.get("questionCode").asText())) {
                return candidate;
            }
        }
        throw new AssertionError("没有题号为 " + questionCode + " 的候选");
    }

    /** 候选上的警告码。 */
    private static List<String> candidateWarnings(JsonNode candidate) {
        List<String> codes = new ArrayList<>();
        candidate.get("warnings").forEach(warning -> codes.add(warning.asText()));
        return codes;
    }

    private static JsonNode versionWarnings(JsonNode data) {
        return data.get("warnings");
    }

    private static List<String> versionWarningCodes(JsonNode data) {
        List<String> codes = new ArrayList<>();
        versionWarnings(data).forEach(warning -> codes.add(warning.get("code").asText()));
        return codes;
    }

    /** 页面上没有归属的区域的文本，用来断言"识别出来的东西没有被悄悄丢掉"。 */
    private static List<JsonNode> unassignedRegions(JsonNode data) {
        List<JsonNode> unassigned = new ArrayList<>();
        for (JsonNode page : data.get("pages")) {
            for (JsonNode region : page.get("regions")) {
                if (region.get("candidateId") == null || region.get("candidateId").isNull()) {
                    unassigned.add(region);
                }
            }
        }
        return unassigned;
    }

    private OcrRequest lastStudentRequest() {
        return requests.stream().filter(request -> "STUDENT_SUBMISSION".equals(request.documentKind()))
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("没有把答卷送去识别"));
    }

    private String teacherToken() {
        return "Bearer " + jwtService.issueTeacher(1, 11);
    }

    private String otherTeacherToken() {
        return "Bearer " + jwtService.issueTeacher(2, 12);
    }

    private String studentToken() {
        return "Bearer " + jwtService.issueStudent(3, STUDENT_ID, false);
    }

    // ---------- 识别结果构造 ----------

    /** 一页答卷：页码由在结果里的位置决定，学生交的第 1 页结果里就是第 1 页。 */
    private record PageSpec(Integer templatePageNo, List<OcrRegion> regions) {
    }

    /** 一页答卷：{@code templatePageNo} 是引擎配准出来的模板页码，{@code null} 表示没对上。 */
    private static PageSpec templatePage(Integer templatePageNo, List<OcrRegion> regions) {
        return new PageSpec(templatePageNo, regions);
    }

    private static List<OcrRegion> regions(OcrRegion... regions) {
        return List.of(regions);
    }

    private static OcrRegion region(String externalId, String type, double x, double y, double width,
                                    double height, String text, String latex, double confidence) {
        return new OcrRegion(externalId, type, x, y, width, height, text, latex, confidence);
    }

    /** 带题号的区域：题号是引擎在那块像素上读到的字，比几何更硬。 */
    private static OcrRegion region(String externalId, String type, double x, double y, double width,
                                    double height, String text, String latex, double confidence,
                                    String questionCode) {
        return new OcrRegion(externalId, type, x, y, width, height, text, latex, confidence, questionCode);
    }

    private static OcrResult answerSheet(PageSpec... pages) {
        List<OcrResult.Page> result = new ArrayList<>();
        for (int index = 0; index < pages.length; index++) {
            PageSpec page = pages[index];
            result.add(new OcrResult.Page(index + 1, PAGE_WIDTH, PAGE_HEIGHT, page.regions(),
                page.templatePageNo(), page.templatePageNo() == null ? null : 0.93, null));
        }
        return new OcrResult("v1", "paddleocr", "3.7.0", List.copyOf(result));
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
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
            return Files.createTempDirectory("homework-answer-extraction-");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试用存储目录", exception);
        }
    }
}
