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
import java.util.stream.Collectors;
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
 * 确认入库的事务性。
 *
 * <p>这一类测试的重点不是"确认成功了"，而是**确认失败时留下了什么**：整卷导入的失败模式
 * 不是报错，而是"大半题目入库了、两道没有"——教师看到成功提示，学生却少做两道题。
 * 所以每个失败用例都要断言四张表全部为空，而不是只看接口返回了什么。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaperConfirmationTest {

    private static final Path STORAGE_ROOT = createStorageRoot();
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
              (1, 'teacher_a', 'x', 'TEACHER', true)
            """);
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C-101', '七年级一班')");
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

    @Test
    void 确认把候选题写成正式题目并建立顺序() throws Exception {
        long assignmentId = importWithCandidates(false);
        completeAllCandidates(assignmentId, null, true);

        String body = confirm(assignmentId).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode assignment = objectMapper.readTree(body).get("data");

        assertThat(assignment.get("id").asLong()).isEqualTo(assignmentId);
        assertThat(assignment.get("questionIds").size()).isEqualTo(4);

        assertThat(countOf("question")).isEqualTo(4);
        assertThat(jdbc.queryForList("""
            select question_order from assignment_question where assignment_id = ? order by question_order
            """, Integer.class, assignmentId)).containsExactly(1, 2, 3, 4);
        // 前三道是过程题，各有一条评分项；最后一道是客观题，没有评分项。
        assertThat(countOf("rubric_item")).isEqualTo(3);
        assertThat(jdbc.queryForList("""
            select role from question_asset order by sort_order
            """, String.class)).containsExactly("STEM_FIGURE");

        // 导入推进到已确认，但作业本身还留在 OCR_REVIEW：发布是教师的下一步动作。
        assertThat(jdbc.queryForList(
            "select status from document_upload where assignment_id = ?", String.class, assignmentId))
            .containsExactly("CONFIRMED");
        assertThat(jdbc.queryForList(
            "select status from ocr_task", String.class)).containsExactly("CONFIRMED");
        assertThat(jdbc.queryForObject(
            "select status from assignment where id = ?", String.class, assignmentId)).isEqualTo("OCR_REVIEW");
    }

    /**
     * 重复确认返回同一份作业。
     *
     * <p>网络重试、用户连点两下都会走到这里。靠客户端"别点两次"是防不住的，
     * 而重复插入的后果是同一道题在作业里出现两遍，学生要交两次答案。
     */
    @Test
    void 重复确认返回同一份作业且不重复建题() throws Exception {
        long assignmentId = importWithCandidates(false);
        completeAllCandidates(assignmentId, null, true);

        String first = confirm(assignmentId).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String second = confirm(assignmentId).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode firstAssignment = objectMapper.readTree(first).get("data");
        JsonNode secondAssignment = objectMapper.readTree(second).get("data");
        assertThat(secondAssignment.get("id").asLong()).isEqualTo(firstAssignment.get("id").asLong());
        assertThat(secondAssignment.get("questionIds").size()).isEqualTo(firstAssignment.get("questionIds").size());

        assertThat(countOf("question")).isEqualTo(4);
        assertThat(jdbc.queryForObject(
            "select count(*) from assignment_question where assignment_id = ?", Integer.class, assignmentId))
            .isEqualTo(4);
    }

    /**
     * 一道题的评分项不合法，整批回滚。
     *
     * <p>这是整个任务的核心验收点。第 1 题合法、第 2 题的评分项之和与总分不符，
     * 因此第 1 题的题目与评分项确实被写进去过——只有事务回滚才能让它们消失。
     * 如果在确认前统一预检，这个用例会"通过"却什么也没证明。
     */
    @Test
    void 一个非法评分项让整批回滚() throws Exception {
        long assignmentId = importWithCandidates(false);
        // 第 2 道（下标 1）的评分项之和比总分多 1 分。
        completeAllCandidates(assignmentId, 1, true);

        confirm(assignmentId)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("RUBRIC_SCORE_MISMATCH"));

        assertThat(countOf("question")).isZero();
        assertThat(countOf("assignment_question")).isZero();
        assertThat(countOf("rubric_item")).isZero();
        assertThat(countOf("question_asset")).isZero();
        // 候选与原卷不因失败而删除，教师可以修正后重试。
        assertThat(countOf("paper_question_candidate")).isEqualTo(4);
        assertThat(jdbc.queryForList(
            "select status from document_upload where assignment_id = ?", String.class, assignmentId))
            .containsExactly("NEEDS_REVIEW");
    }

    @Test
    void 字段没补齐时确认失败并指出是哪一题() throws Exception {
        long assignmentId = importWithCandidates(false);

        confirm(assignmentId)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("OCR_REVIEW_INCOMPLETE"));

        assertThat(countOf("question")).isZero();
    }

    /**
     * 题图没裁剪就确认，必须被拒绝。
     *
     * <p>静默跳过等于交付一道缺图的题，而教师已经在界面上确认过它。
     * 候选上的题图来源是物化时自动带上的（FIGURE 区域），所以只要不裁剪就会走到这条分支。
     */
    @Test
    void 题图还没裁剪时确认被拒绝() throws Exception {
        long assignmentId = importWithCandidates(false);
        completeAllCandidates(assignmentId, null, false);

        confirm(assignmentId)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("QUESTION_ASSET_NOT_READY"));

        assertThat(countOf("question")).isZero();
    }

    @Test
    void 确认之后不能再修改候选() throws Exception {
        long assignmentId = importWithCandidates(false);
        completeAllCandidates(assignmentId, null, true);
        confirm(assignmentId).andExpect(status().isOk());

        JsonNode candidate = candidatesOf(assignmentId, "EXAM_PAPER").get(0);
        mvc.perform(patch("/api/teacher/paper-imports/" + assignmentId + "/regions/"
                + candidate.get("sourceRegionIds").get(0).asLong())
                .header("Authorization", teacherToken(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateId\":" + candidate.get("id").asLong()
                    + ",\"version\":" + candidate.get("version").asInt()
                    + ",\"candidate\":{\"content\":\"确认之后的修改\"}}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("PAPER_IMPORT_CONFIRMED"));
    }

    /**
     * 答案卷的标准答案按题号自动带入。
     *
     * <p>这是答案卷唯一的作用：教师不必把标准答案从答案卷上再抄一遍。
     */
    @Test
    void 答案卷的标准答案按题号带入题目() throws Exception {
        long assignmentId = importWithCandidates(true);
        completeAllCandidates(assignmentId, null, true);
        confirm(assignmentId).andExpect(status().isOk());

        String standardAnswer = jdbc.queryForObject("""
            select standard_answer from question where question_code = '2'
            """, String.class);
        assertThat(standardAnswer).contains("x = 5");
    }

    // ---------- 流程与辅助 ----------

    /** 建一次已经有候选的导入；{@code withAnswerKey} 决定是否同时上传答案卷。 */
    private long importWithCandidates(boolean withAnswerKey) throws Exception {
        String body = mvc.perform(post("/api/teacher/paper-imports")
                .header("Authorization", teacherToken(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":101,\"title\":\"第一单元测验\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long assignmentId = objectMapper.readTree(body).get("data").get("assignmentId").asLong();

        upload(assignmentId, "EXAM_PAPER", "试卷.pdf", pdf(2));
        if (withAnswerKey) {
            upload(assignmentId, "ANSWER_KEY", "答案.pdf", pdf(1));
        }
        mvc.perform(post("/api/teacher/paper-imports/" + assignmentId + "/process")
                .header("Authorization", teacherToken(11)))
            .andExpect(status().isOk());
        return assignmentId;
    }

    /**
     * 把每道候选补成能确认的内容。
     *
     * <p>{@code badRubricIndex} 非空时，把第 N 道（下标）的评分项之和写成比总分多 1 分，用来
     * 验证"一道题不合法就整批回滚"；为 null 时全部合法。
     *
     * <p>{@code cropAssets} 为真时，对候选自动带上的题图来源逐个裁剪——裁剪会单独占用一次版本，
     * 所以每裁一次都要重新取一次候选，否则后续写入会因为版本过期被拒。为假时只保留来源不裁剪，
     * 用来验证"没裁剪就不许确认"。
     */
    private void completeAllCandidates(long assignmentId, Integer badRubricIndex, boolean cropAssets)
            throws Exception {
        ArrayNode candidates = candidatesOf(assignmentId, "EXAM_PAPER");
        String[] codes = {"1", "2", "3", "4"};
        for (int index = 0; index < candidates.size(); index++) {
            JsonNode candidate = candidates.get(index);
            List<Long> assetRegionIds = regionIdsOf(candidate);
            if (cropAssets) {
                for (Long assetRegionId : assetRegionIds) {
                    cropRegion(assignmentId, candidate.get("id").asLong(),
                        candidate.get("version").asInt(), assetRegionId);
                    candidate = candidatesOf(assignmentId, "EXAM_PAPER").get(index);
                }
            }
            boolean objective = index == candidates.size() - 1;
            String type = objective ? "FILL_BLANK" : "SOLUTION";
            int score = 4 + index * 2;
            Integer rubricTotal = badRubricIndex != null && badRubricIndex == index ? score + 1 : score;
            saveCandidate(assignmentId, candidate, codes[index], type, score, rubricTotal, assetRegionIds);
        }
    }

    private static List<Long> regionIdsOf(JsonNode candidate) {
        JsonNode ids = candidate.get("assetRegionIds");
        if (ids == null || ids.isNull()) {
            return List.of();
        }
        return StreamSupport.stream(ids.spliterator(), false).map(JsonNode::asLong).toList();
    }

    private void saveCandidate(long assignmentId, JsonNode candidate, String code, String type,
                               int score, Integer rubricTotal, List<Long> assetRegionIds) throws Exception {
        long candidateId = candidate.get("id").asLong();
        long regionId = candidate.get("sourceRegionIds").get(0).asLong();
        StringBuilder json = new StringBuilder();
        json.append("{\"candidateId\":").append(candidateId)
            .append(",\"version\":").append(candidate.get("version").asInt())
            .append(",\"candidate\":{\"questionCode\":\"").append(code)
            .append("\",\"questionType\":\"").append(type)
            .append("\",\"content\":\"第 ").append(code).append(" 题的题干\"")
            .append(",\"totalScore\":").append(score)
            .append(",\"difficulty\":\"MEDIUM\",\"primaryKnowledgePointId\":301");
        if (!assetRegionIds.isEmpty()) {
            json.append(",\"assetRegionIds\":[")
                .append(assetRegionIds.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .append(']');
        }
        if ("SOLUTION".equals(type)) {
            json.append(",\"rubricItems\":[{\"orderNo\":1,\"title\":\"步骤\",\"criteria\":\"关键步骤正确\",")
                .append("\"maxScore\":").append(rubricTotal == null ? score : rubricTotal).append("}]");
        } else {
            json.append(",\"acceptedAnswers\":[\"4\"]");
        }
        json.append("}}");

        mvc.perform(patch("/api/teacher/paper-imports/" + assignmentId + "/regions/" + regionId)
                .header("Authorization", teacherToken(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.toString()))
            .andExpect(status().isOk());
    }

    private void cropRegion(long assignmentId, long candidateId, int version, long regionId) throws Exception {
        mvc.perform(patch("/api/teacher/paper-imports/" + assignmentId + "/regions/" + regionId)
                .header("Authorization", teacherToken(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateId\":" + candidateId + ",\"version\":" + version
                    + ",\"createCrop\":true}"))
            .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions confirm(long assignmentId) throws Exception {
        return mvc.perform(post("/api/teacher/paper-imports/" + assignmentId + "/confirm")
            .header("Authorization", teacherToken(11)));
    }

    private void upload(long assignmentId, String kind, String filename, byte[] content) throws Exception {
        mvc.perform(multipart("/api/teacher/paper-imports/" + assignmentId + "/files")
                .file(new MockMultipartFile("file", filename, "application/pdf", content))
                .param("kind", kind)
                .header("Authorization", teacherToken(11)))
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

    private int countOf(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private String teacherToken(long teacherId) {
        return "Bearer " + jwtService.issue(teacherId, teacherId, "TEACHER");
    }

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
            return Files.createTempDirectory("homework-paper-confirm-");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试用存储目录", exception);
        }
    }
}
