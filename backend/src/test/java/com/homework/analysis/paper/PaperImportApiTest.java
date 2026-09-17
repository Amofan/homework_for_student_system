package com.homework.analysis.paper;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;

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
 * 整卷导入的接口行为。
 *
 * <p>OCR 服务被替换成固定返回值的假实现：这里要验证的是"拿到识别结果之后系统做了什么"，
 * 而不是识别本身准不准。假实现按 {@code documentKind} 返回不同结果，因此同一套流程
 * 能同时覆盖空白卷与答案卷两条路径。
 *
 * <p>上传的是真正的两页 PDF，不走 PNG 捷径——页面渲染、缩略图、页面图落库这几步只有走 PDF
 * 才会被执行，而它们正是"区域坐标能不能对上像素"的前提。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaperImportApiTest {

    private static final Path STORAGE_ROOT = createStorageRoot();

    /** 200 DPI 下 A4 的像素尺寸，与 OCR 返回的页面尺寸保持一致。 */
    private static final int PAGE_WIDTH = 1654;
    private static final int PAGE_HEIGHT = 2339;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local-root", STORAGE_ROOT::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean OcrProvider provider;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("""
            insert into app_user(id, username, password_hash, role, enabled) values
              (1, 'teacher_a', 'x', 'TEACHER', true),
              (2, 'teacher_b', 'x', 'TEACHER', true),
              (3, 'stu_one', 'x', 'STUDENT', true)
            """);
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲'), (22, 2, '教师乙')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C-101', '七年级一班')");
        jdbc.update("insert into student(id, class_id, student_no, name, user_id) values (1001, 101, '001', '张三', 3)");
        jdbc.update("""
            insert into knowledge_point(id, teacher_id, code, name, grade, active)
            values (301, 11, 'ALG', '代数', 7, true)
            """);

        when(provider.analyze(any())).thenAnswer(invocation -> {
            OcrRequest request = invocation.getArgument(0);
            return PaperImportService.KIND_ANSWER_KEY.equals(request.documentKind())
                ? answerKeyResult() : examPaperResult();
        });
    }

    // ---------- 创建与上传 ----------

    @Test
    void 创建导入得到OCR_REVIEW状态的作业() throws Exception {
        long assignmentId = createImport("第一单元测验");

        assertThat(jdbc.queryForObject(
            "select status from assignment where id = ?", String.class, assignmentId)).isEqualTo("OCR_REVIEW");
        assertThat(jdbc.queryForObject(
            "select class_id from assignment where id = ?", Long.class, assignmentId)).isEqualTo(101L);

        mvc.perform(get("/api/teacher/paper-imports/" + assignmentId).header("Authorization", teacherToken(11)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("第一单元测验"))
            .andExpect(jsonPath("$.data.confirmed").value(false))
            .andExpect(jsonPath("$.data.documents").isEmpty())
            .andExpect(jsonPath("$.data.candidates").isEmpty());
    }

    @Test
    void 上传空白卷与答案卷后文档挂到这次导入上() throws Exception {
        long assignmentId = createImport("第一单元测验");

        upload(assignmentId, "EXAM_PAPER", "试卷.pdf", pdf(2));
        upload(assignmentId, "ANSWER_KEY", "答案.pdf", pdf(1));

        List<String> kinds = jdbc.queryForList("""
            select document_kind from document_upload where assignment_id = ? order by document_kind
            """, String.class, assignmentId);
        assertThat(kinds).containsExactly("ANSWER_KEY", "EXAM_PAPER");
    }

    @Test
    void 重复上传同类型文件被拒绝() throws Exception {
        long assignmentId = createImport("第一单元测验");
        upload(assignmentId, "EXAM_PAPER", "试卷.pdf", pdf(1));

        mvc.perform(multipart("/api/teacher/paper-imports/" + assignmentId + "/files")
                .file(pdfFile("试卷2.pdf"))
                .param("kind", "EXAM_PAPER")
                .header("Authorization", teacherToken(11)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("PAPER_IMPORT_DOCUMENT_EXISTS"));
    }

    @Test
    void 不受支持的文档类型被拒绝() throws Exception {
        long assignmentId = createImport("第一单元测验");

        mvc.perform(multipart("/api/teacher/paper-imports/" + assignmentId + "/files")
                .file(pdfFile("学生答卷.pdf"))
                .param("kind", "STUDENT_SUBMISSION")
                .header("Authorization", teacherToken(11)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("PAPER_IMPORT_KIND_INVALID"));
    }

    // ---------- 识别与候选 ----------

    @Test
    void 没有空白卷时不能开始识别() throws Exception {
        long assignmentId = createImport("第一单元测验");

        mvc.perform(post("/api/teacher/paper-imports/" + assignmentId + "/process")
                .header("Authorization", teacherToken(11)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("PAPER_IMPORT_EXAM_PAPER_REQUIRED"));
    }

    /**
     * 每个文档只建一条 OCR 任务。
     *
     * <p>教师看到"还在识别"时最常见的动作就是再点一次，而重复识别不只是浪费算力：
     * 第二次的结果会覆盖第一次，教师可能正在校对第一份候选。
     */
    @Test
    void 识别任务按文档幂等且重复调用不新增候选() throws Exception {
        long assignmentId = createImport("第一单元测验");
        upload(assignmentId, "EXAM_PAPER", "试卷.pdf", pdf(2));
        upload(assignmentId, "ANSWER_KEY", "答案.pdf", pdf(1));

        process(assignmentId);
        int candidateCount = countOf("paper_question_candidate");

        process(assignmentId);
        process(assignmentId);

        assertThat(jdbc.queryForObject("select count(*) from ocr_task", Integer.class)).isEqualTo(2);
        assertThat(countOf("paper_question_candidate")).isEqualTo(candidateCount);
        assertThat(jdbc.queryForObject("select count(*) from document_page", Integer.class)).isEqualTo(3);
    }

    /**
     * 识别结果只写候选。
     *
     * <p>这是整卷导入最重要的一条边界：OCR 的结果不可信，未经教师确认不得进入题库。
     * 一旦这里破口，学生就会拿到一道教师从没看过、题干可能错乱的题。
     */
    @Test
    void 候选物化不写任何正式题目() throws Exception {
        long assignmentId = createImport("第一单元测验");
        upload(assignmentId, "EXAM_PAPER", "试卷.pdf", pdf(2));
        process(assignmentId);

        assertThat(countOf("paper_question_candidate")).isPositive();
        assertThat(countOf("question")).isZero();
        assertThat(countOf("assignment_question")).isZero();
        assertThat(countOf("rubric_item")).isZero();
        assertThat(countOf("question_asset")).isZero();
    }

    @Test
    void 候选保留全部来源区域并给出结构化警告() throws Exception {
        long assignmentId = createImport("第一单元测验");
        upload(assignmentId, "EXAM_PAPER", "试卷.pdf", pdf(2));
        process(assignmentId);

        JsonNode candidates = candidatesOf(assignmentId, "EXAM_PAPER");
        assertThat(candidates.size()).isEqualTo(4);

        // 每一个识别出的区域都必须在某道候选里出现，一块都不能丢。
        int regionTotal = jdbc.queryForObject("select count(*) from document_region", Integer.class);
        int referenced = 0;
        for (JsonNode candidate : candidates) {
            referenced += candidate.get("sourceRegionIds").size();
        }
        assertThat(referenced).isEqualTo(regionTotal);

        JsonNode first = candidates.get(0);
        assertThat(textOf(first, "questionCode")).isNull();
        assertThat(warningsOf(first))
            .contains("MISSING_QUESTION_CODE", "QUESTION_TYPE_UNKNOWN", "SCORE_NOT_DETECTED",
                "AMBIGUOUS_CROSS_PAGE_GROUP");

        // 重叠的题号锚点会被标出来：两个框指向同一片像素时，题干会重复两遍。
        assertThat(warningsOf(candidates.get(3))).contains("OVERLAPPING_QUESTION_BOX");

        JsonNode lowConfidence = candidates.get(3);
        assertThat(warningsOf(lowConfidence)).contains("LOW_CONFIDENCE");
        assertThat(lowConfidence.get("totalScore").asInt()).isEqualTo(6);

        // 题图来源被自动识别为候选的题图区域。
        assertThat(candidates.get(2).get("assetRegionIds").size()).isEqualTo(1);
    }

    @Test
    void 答案卷按题号匹配并标出对不上的题() throws Exception {
        long assignmentId = createImport("第一单元测验");
        upload(assignmentId, "EXAM_PAPER", "试卷.pdf", pdf(2));
        upload(assignmentId, "ANSWER_KEY", "答案.pdf", pdf(1));
        process(assignmentId);

        JsonNode exam = candidatesOf(assignmentId, "EXAM_PAPER");
        // 第 1、2 题在答案卷里有对应答案，第 3 题没有。
        assertThat(warningsOf(exam.get(1))).doesNotContain("ANSWER_KEY_MISSING");
        assertThat(warningsOf(exam.get(2))).doesNotContain("ANSWER_KEY_MISSING");
        assertThat(warningsOf(exam.get(3))).contains("ANSWER_KEY_MISSING");

        JsonNode answerKey = candidatesOf(assignmentId, "ANSWER_KEY");
        assertThat(answerKey.size()).isEqualTo(3);
        // 答案卷的第 4 题在空白卷里找不到：多半是空白卷漏检，必须让教师看到。
        assertThat(warningsOf(answerKey.get(2))).contains("ANSWER_KEY_UNMATCHED");
        assertThat(textOf(answerKey.get(0), "matchedQuestionCode")).isEqualTo("1");
        // 答案卷候选永远不会变成题目，所以它只填标准答案，不填题干。
        assertThat(textOf(answerKey.get(0), "content")).isNull();
        assertThat(textOf(answerKey.get(0), "standardAnswer")).contains("x = 2");
    }

    // ---------- 文档状态 ----------

    /**
     * 文档视图要带上 OCR 任务状态。
     *
     * <p>可重试失败（识别服务不可用）时任务回到 {@code RETRY_WAIT}，而文档状态仍是
     * {@code PENDING}——只看文档状态，"稍后会自动重试"和"还没开始识别"完全一样。
     */
    @Test
    void 文档视图带上OCR任务状态() throws Exception {
        long assignmentId = readyImport();

        mvc.perform(get("/api/teacher/paper-imports/" + assignmentId).header("Authorization", teacherToken(11)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.documents[0].ocrStatus").value("NEEDS_REVIEW"));

        jdbc.update("update ocr_task set status = 'RETRY_WAIT', failure_code = 'OCR_UNAVAILABLE'");
        jdbc.update("update document_upload set status = 'PENDING'");

        mvc.perform(get("/api/teacher/paper-imports/" + assignmentId).header("Authorization", teacherToken(11)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.documents[0].status").value("PENDING"))
            .andExpect(jsonPath("$.data.documents[0].ocrStatus").value("RETRY_WAIT"))
            .andExpect(jsonPath("$.data.documents[0].ocrFailureCode").value("OCR_UNAVAILABLE"));
    }

    // ---------- 校对编辑 ----------

    /**
     * 拆分：把一块区域从原题拆出来，单独组成一道新题。
     *
     * <p>OCR 把两道题并成一组是常见情况（后一题的题号没被识别出来），
     * 只让教师"把区域移出成未归属"是不够的，他需要一道能填内容的新题。
     */
    @Test
    void 拆分生成一道只含该区域的新候选题() throws Exception {
        long assignmentId = readyImport();
        JsonNode owner = candidatesOf(assignmentId, "EXAM_PAPER").get(2);
        long ownerId = owner.get("id").asLong();
        long regionId = owner.get("sourceRegionIds").get(1).asLong();

        mvc.perform(patch("/api/teacher/paper-imports/" + assignmentId + "/regions/" + regionId)
                .header("Authorization", teacherToken(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateId\":" + ownerId + ",\"version\":" + owner.get("version").asInt()
                    + ",\"split\":true}"))
            .andExpect(status().isOk());

        JsonNode after = candidatesOf(assignmentId, "EXAM_PAPER");
        assertThat(after.size()).isEqualTo(5);
        JsonNode created = after.get(4);
        assertThat(created.get("sourceRegionIds").size()).isEqualTo(1);
        assertThat(created.get("sourceRegionIds").get(0).asLong()).isEqualTo(regionId);
        // 新题不继承题号与分值：继承题号会撞唯一约束，继承分值会让"评分项之和等于总分"提前失效。
        assertThat(textOf(created, "questionCode")).isNull();
        assertThat(textOf(created, "totalScore")).isNull();

        assertThat(after.get(2).get("sourceRegionIds").size()).isEqualTo(1);
        assertThat(after.get(2).get("version").asInt()).isEqualTo(1);
    }

    @Test
    void 只剩一块区域时不能拆分() throws Exception {
        long assignmentId = readyImport();
        JsonNode owner = candidatesOf(assignmentId, "EXAM_PAPER").get(3);
        long regionId = owner.get("sourceRegionIds").get(0).asLong();

        mvc.perform(patch("/api/teacher/paper-imports/" + assignmentId + "/regions/" + regionId)
                .header("Authorization", teacherToken(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateId\":" + owner.get("id").asLong()
                    + ",\"version\":" + owner.get("version").asInt() + ",\"split\":true}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("SPLIT_WOULD_EMPTY_CANDIDATE"));

        assertThat(candidatesOf(assignmentId, "EXAM_PAPER").size()).isEqualTo(4);
    }

    @Test
    void 修改候选题字段后版本递增() throws Exception {
        long assignmentId = readyImport();
        JsonNode candidate = candidatesOf(assignmentId, "EXAM_PAPER").get(1);
        long candidateId = candidate.get("id").asLong();
        long regionId = candidate.get("sourceRegionIds").get(0).asLong();

        patchCandidate(assignmentId, regionId, candidateId, candidate.get("version").asInt(),
            "\"candidate\":{\"questionCode\":\"1\",\"questionType\":\"SOLUTION\",\"content\":\"改过的题干\","
                + "\"totalScore\":10,\"difficulty\":\"BASIC\",\"primaryKnowledgePointId\":301,"
                + "\"rubricItems\":[{\"orderNo\":1,\"title\":\"步骤\",\"criteria\":\"变形正确\",\"maxScore\":10}]}");

        assertThat(jdbc.queryForObject(
            "select version from paper_question_candidate where id = ?", Integer.class, candidateId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select content from paper_question_candidate where id = ?", String.class, candidateId))
            .isEqualTo("改过的题干");
        assertThat(jdbc.queryForObject(
            "select review_status from paper_question_candidate where id = ?", String.class, candidateId))
            .isEqualTo("PENDING");
    }

    /**
     * 版本过期的编辑必须被拒绝。
     *
     * <p>两个窗口同时校对同一份卷子是常态。没有这道闸，后保存的一方会静默覆盖前一方的工作，
     * 而且两者都会以为保存成功。
     */
    @Test
    void 版本过期时返回冲突且不改动数据() throws Exception {
        long assignmentId = readyImport();
        JsonNode candidate = candidatesOf(assignmentId, "EXAM_PAPER").get(1);
        long candidateId = candidate.get("id").asLong();
        long regionId = candidate.get("sourceRegionIds").get(0).asLong();

        // 第一次编辑成功，版本从 0 变成 1。
        patchCandidate(assignmentId, regionId, candidateId, 0,
            "\"candidate\":{\"questionCode\":\"1\",\"totalScore\":10,\"primaryKnowledgePointId\":301}");

        mvc.perform(patch("/api/teacher/paper-imports/" + assignmentId + "/regions/" + regionId)
                .header("Authorization", teacherToken(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"candidateId":%d,"version":0,"candidate":{"content":"后到的编辑"}}
                    """.formatted(candidateId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("OCR_REVIEW_CONFLICT"));

        assertThat(jdbc.queryForObject(
            "select content from paper_question_candidate where id = ?", String.class, candidateId))
            .doesNotContain("后到的编辑");
    }

    @Test
    void 缺少版本号时拒绝修改() throws Exception {
        long assignmentId = readyImport();
        JsonNode candidate = candidatesOf(assignmentId, "EXAM_PAPER").get(1);

        mvc.perform(patch("/api/teacher/paper-imports/" + assignmentId + "/regions/"
                + candidate.get("sourceRegionIds").get(0).asLong())
                .header("Authorization", teacherToken(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateId\":" + candidate.get("id").asLong()
                    + ",\"candidate\":{\"content\":\"没有版本号\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("PAPER_CANDIDATE_VERSION_REQUIRED"));
    }

    @Test
    void 拖动题干框会清掉已经过期的裁剪图() throws Exception {
        long assignmentId = readyImport();
        JsonNode candidate = candidatesOf(assignmentId, "EXAM_PAPER").get(1);
        long regionId = candidate.get("sourceRegionIds").get(0).asLong();
        long candidateId = candidate.get("id").asLong();

        patchCandidate(assignmentId, regionId, candidateId, candidate.get("version").asInt(),
            "\"createCrop\":true");
        Long cropFileId = jdbc.queryForObject(
            "select crop_file_id from document_region where id = ?", Long.class, regionId);
        assertThat(cropFileId).isNotNull();

        patchCandidate(assignmentId, regionId, candidateId, 1, "\"x\":0.2,\"y\":0.2");

        assertThat(jdbc.queryForObject(
            "select crop_file_id from document_region where id = ?", Long.class, regionId)).isNull();
    }

    // ---------- 越权与未登录 ----------

    @Test
    void 跨教师读取导入返回404() throws Exception {
        long assignmentId = createImport("第一单元测验");

        mvc.perform(get("/api/teacher/paper-imports/" + assignmentId).header("Authorization", teacherToken(22)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_NOT_FOUND"));
    }

    @Test
    void 跨教师不能上传文件也不能开始识别() throws Exception {
        long assignmentId = createImport("第一单元测验");

        mvc.perform(multipart("/api/teacher/paper-imports/" + assignmentId + "/files")
                .file(pdfFile("试卷.pdf"))
                .param("kind", "EXAM_PAPER")
                .header("Authorization", teacherToken(22)))
            .andExpect(status().isNotFound());

        mvc.perform(post("/api/teacher/paper-imports/" + assignmentId + "/process")
                .header("Authorization", teacherToken(22)))
            .andExpect(status().isNotFound());
    }

    @Test
    void 学生令牌不能访问整卷导入接口() throws Exception {
        long assignmentId = createImport("第一单元测验");

        mvc.perform(get("/api/teacher/paper-imports/" + assignmentId).header("Authorization", studentToken(1001)))
            .andExpect(status().isForbidden());
    }

    @Test
    void 未登录访问整卷导入接口被拒绝() throws Exception {
        mvc.perform(post("/api/teacher/paper-imports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":101,\"title\":\"偷渡\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    // ---------- 辅助 ----------

    private long createImport(String title) throws Exception {
        String body = mvc.perform(post("/api/teacher/paper-imports")
                .header("Authorization", teacherToken(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":101,\"title\":\"" + title + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data").get("assignmentId").asLong();
    }

    /** 建一次已经有候选的导入，供校对相关用例复用。 */
    private long readyImport() throws Exception {
        long assignmentId = createImport("第一单元测验");
        upload(assignmentId, "EXAM_PAPER", "试卷.pdf", pdf(2));
        process(assignmentId);
        return assignmentId;
    }

    private void upload(long assignmentId, String kind, String filename, byte[] content) throws Exception {
        mvc.perform(multipart("/api/teacher/paper-imports/" + assignmentId + "/files")
                .file(new MockMultipartFile("file", filename, "application/pdf", content))
                .param("kind", kind)
                .header("Authorization", teacherToken(11)))
            .andExpect(status().isOk());
    }

    private void process(long assignmentId) throws Exception {
        mvc.perform(post("/api/teacher/paper-imports/" + assignmentId + "/process")
                .header("Authorization", teacherToken(11)))
            .andExpect(status().isOk());
    }

    private void patchCandidate(long assignmentId, long regionId, long candidateId, int version,
                                String extraFields) throws Exception {
        mvc.perform(patch("/api/teacher/paper-imports/" + assignmentId + "/regions/" + regionId)
                .header("Authorization", teacherToken(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateId\":" + candidateId + ",\"version\":" + version
                    + "," + extraFields + "}"))
            .andExpect(status().isOk());
    }

    private ArrayNode candidatesOf(long assignmentId, String documentKind) throws Exception {
        String body = mvc.perform(get("/api/teacher/paper-imports/" + assignmentId)
                .header("Authorization", teacherToken(11)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode candidate : objectMapper.readTree(body).get("data").get("candidates")) {
            if (documentKind.equals(candidate.get("documentKind").asText())) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static List<String> warningsOf(JsonNode candidate) {
        return StreamSupport.stream(candidate.get("warnings").spliterator(), false)
            .map(JsonNode::asText)
            .toList();
    }

    /**
     * 读取一个可能不存在的字段。
     *
     * <p>响应体按 {@code default-property-inclusion: non_null} 序列化，空值字段会整个消失，
     * 直接 {@code node.get(f).isNull()} 会在空字段上抛空指针，而"字段不存在"恰恰是这里要断言的情形。
     */
    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private int countOf(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private MockMultipartFile pdfFile(String filename) throws IOException {
        return new MockMultipartFile("file", filename, "application/pdf", pdf(1));
    }

    private String teacherToken(long teacherId) {
        return "Bearer " + jwtService.issue(teacherId, teacherId, "TEACHER");
    }

    private String studentToken(long studentId) {
        long userId = jdbc.queryForObject("select user_id from student where id = ?", Long.class, studentId);
        return "Bearer " + jwtService.issueStudent(userId, studentId, false);
    }

    /**
     * 空白卷的识别结果：两页、四道题、五种警告各有来源。
     *
     * <p>刻意让第一页第一个区域没有题号（考生须知），第二页第一个区域也没有题号——
     * 后者会被并入上一题，从而产生跨页分组的歧义警告。
     */
    private static OcrResult examPaperResult() {
        return new OcrResult("v1", "paddleocr", "3.7.0", List.of(
            new OcrResult.Page(1, PAGE_WIDTH, PAGE_HEIGHT, List.of(
                new OcrRegion("p1-r1", "TEXT_BLOCK", 0.08, 0.05, 0.84, 0.04,
                    "考生须知：本卷共三大题", null, 0.95))),
            new OcrResult.Page(2, PAGE_WIDTH, PAGE_HEIGHT, List.of(
                new OcrRegion("p2-r1", "TEXT_BLOCK", 0.08, 0.04, 0.84, 0.04,
                    "请用黑色签字笔作答", null, 0.96),
                new OcrRegion("p2-r2", "TEXT_BLOCK", 0.08, 0.10, 0.84, 0.04,
                    "1. 计算下列各题。", null, 0.97),
                new OcrRegion("p2-r3", "FORMULA", 0.12, 0.16, 0.30, 0.04,
                    "x + 1 = 3", null, 0.93),
                new OcrRegion("p2-r4", "TEXT_BLOCK", 0.08, 0.24, 0.84, 0.04,
                    "2. 解方程（8分）", null, 0.94),
                new OcrRegion("p2-r5", "FIGURE", 0.12, 0.30, 0.30, 0.18, null, null, 0.88),
                new OcrRegion("p2-r6", "TEXT_BLOCK", 0.08, 0.26, 0.40, 0.04,
                    "3. 证明：三角形内角和为180度（6分）", null, 0.62)))));
    }

    /**
     * 答案卷的识别结果：三题，其中第 4 题在空白卷里没有对应题目。
     */
    private static OcrResult answerKeyResult() {
        return new OcrResult("v1", "paddleocr", "3.7.0", List.of(
            new OcrResult.Page(1, PAGE_WIDTH, PAGE_HEIGHT, List.of(
                new OcrRegion("k1", "TEXT_BLOCK", 0.08, 0.10, 0.84, 0.04,
                    "1. 答案：x = 2", null, 0.95),
                new OcrRegion("k2", "TEXT_BLOCK", 0.08, 0.20, 0.84, 0.04,
                    "2. 答案：x = 5", null, 0.95),
                new OcrRegion("k3", "TEXT_BLOCK", 0.08, 0.30, 0.84, 0.04,
                    "4. 答案：这道题空白卷里没有", null, 0.95)))));
    }

    private static byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pages; index++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("homework-paper-import-");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试用存储目录", exception);
        }
    }
}
