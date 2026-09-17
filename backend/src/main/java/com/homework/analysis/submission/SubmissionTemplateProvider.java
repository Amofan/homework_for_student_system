package com.homework.analysis.submission;

import com.homework.analysis.ocr.OcrRequest;
import com.homework.analysis.ocr.OcrTemplateProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 学生答卷的模板几何：把教师确认过的试卷题目框送给 OCR。
 *
 * <p>为什么答卷需要模板：手写答案上没有可靠的题号锚点（题号是印在卷面上的，学生不会抄一遍），
 * 所以"这一块属于第几题"只能靠"这一块落在第几题的框里"。整卷导入不需要模板，因为那时
 * 要认的正是卷面上印着的题号本身，先告诉引擎答案反而是替它把答案说了。
 *
 * <p>模板只取**已经确认进题库**的题目（{@code assignment_question} 里有的那些），
 * 不直接取 {@code paper_question_candidate} 的全部候选：候选是教师还没认过的分组，
 * 拿它当标准版面，等于把一份未校对的识别结果变成学生答卷的判定依据。教师没确认过的题，
 * 学生在答卷上写了也会落成"未归属区域"，由教师手工归类——这是宁可多干活也不能错判的那一侧。
 *
 * <p>模板一页都取不到时返回空：识别照常进行，只是区域不带题号（见
 * {@code OcrTaskWorker#templateOf}）。
 */
@Component
class SubmissionTemplateProvider implements OcrTemplateProvider {

    private static final String KIND_EXAM_PAPER = "EXAM_PAPER";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    SubmissionTemplateProvider(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<OcrRequest.Template> templateFor(long documentId) {
        Long assignmentId = assignmentOf(documentId);
        if (assignmentId == null) {
            return Optional.empty();
        }
        List<ConfirmedQuestion> questions = confirmedQuestions(assignmentId);
        if (questions.isEmpty()) {
            return Optional.empty();
        }
        Map<Integer, List<OcrRequest.QuestionBox>> boxesByPage = boxesByPage(questions);
        if (boxesByPage.isEmpty()) {
            return Optional.empty();
        }
        List<OcrRequest.TemplatePage> pages = new ArrayList<>();
        boxesByPage.forEach((pageNo, boxes) ->
            pages.add(new OcrRequest.TemplatePage(pageNo, List.copyOf(boxes))));
        return Optional.of(new OcrRequest.Template(List.copyOf(pages)));
    }

    /** 这份文档挂在哪份作业上；不是学生答卷（例如整卷导入的空白卷）时返回 {@code null}。 */
    private Long assignmentOf(long documentId) {
        return jdbc.sql("""
                select sv.assignment_id
                from document_upload du
                join submission_version sv on sv.id = du.submission_version_id
                where du.id = :documentId and du.deleted_at is null
                """)
            .param("documentId", documentId)
            .query(Long.class)
            .optional()
            .orElse(null);
    }

    /**
     * 这道作业里已经确认进题库的题，以及它们的题号与来源区域。
     *
     * <p>按题号匹配候选而不是按候选主键：候选会在重新识别时整体重建，主键不稳定，
     * 题号是教师看得见、也认得出的键。
     */
    private List<ConfirmedQuestion> confirmedQuestions(long assignmentId) {
        return jdbc.sql("""
                select q.question_code, c.source_region_ids
                from paper_question_candidate c
                join question q on q.question_code = c.question_code
                join assignment_question aq on aq.question_id = q.id
                where c.assignment_id = :assignmentId
                  and aq.assignment_id = :assignmentId
                  and c.document_kind = :kind
                  and c.question_code is not null
                order by aq.question_order
                """)
            .param("assignmentId", assignmentId)
            .param("kind", KIND_EXAM_PAPER)
            .query((rs, rowNum) -> new ConfirmedQuestion(
                rs.getString("question_code"), readRegionIds(rs.getString("source_region_ids"))))
            .list();
    }

    /**
     * 每个模板页上每个题目的外接框。
     *
     * <p>多块区域组成一道题时给外接框，而不是逐块下发：答卷上的答案只要落在这个范围内就算
     * 这道题的，逐块下发会让"学生写在两块之间的空白处"落到所有框之外。外接框会盖住相邻题的
     * 一部分，那种重叠由 Java 侧的歧义检测提示教师，比整块丢失好处理。
     *
     * <p>一道题的区域跨页时，每一页各得一个框——配准是逐页比对，把跨页的外接框塞进某一页
     * 只会让那一页的框大到没有意义。
     */
    private Map<Integer, List<OcrRequest.QuestionBox>> boxesByPage(List<ConfirmedQuestion> questions) {
        List<Long> regionIds = questions.stream()
            .flatMap(question -> question.regionIds().stream())
            .distinct()
            .toList();
        if (regionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, RegionBox> boxes = new LinkedHashMap<>();
        jdbc.sql("""
                select dr.id, dp.page_no, dr.x, dr.y, dr.width, dr.height
                from document_region dr
                join document_page dp on dp.id = dr.page_id
                where dr.id in (:regionIds)
                """)
            .param("regionIds", regionIds)
            .query((rs, rowNum) -> new RegionBox(rs.getLong("id"), rs.getInt("page_no"),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("width"), rs.getDouble("height")))
            .list()
            .forEach(box -> boxes.put(box.regionId(), box));

        Map<Integer, List<OcrRequest.QuestionBox>> byPage = new LinkedHashMap<>();
        for (ConfirmedQuestion question : questions) {
            // 同一道题在同一页上的多块区域合成一个外接框，所以先按页归拢再取并集。
            Map<Integer, double[]> bounds = new LinkedHashMap<>();
            for (Long regionId : question.regionIds()) {
                RegionBox box = boxes.get(regionId);
                if (box == null) {
                    // 区域被删了（重新识别会整体重建）。少一个框不影响其它题，跳过而不是让整份模板取不到。
                    continue;
                }
                bounds.merge(box.pageNo(),
                    new double[] {box.x(), box.y(), box.x() + box.width(), box.y() + box.height()},
                    SubmissionTemplateProvider::union);
            }
            bounds.forEach((pageNo, rect) -> byPage
                .computeIfAbsent(pageNo, key -> new ArrayList<>())
                .add(new OcrRequest.QuestionBox(question.questionCode(),
                    clamp(rect[0]), clamp(rect[1]), clamp(rect[2] - rect[0]), clamp(rect[3] - rect[1]))));
        }
        return byPage;
    }

    private static double[] union(double[] left, double[] right) {
        return new double[] {
            Math.min(left[0], right[0]), Math.min(left[1], right[1]),
            Math.max(left[2], right[2]), Math.max(left[3], right[3]),
        };
    }

    /**
     * 夹到 {@code 0..1}。
     *
     * <p>来源区域是教师拖出来的，可能被拖到页面外（越界框在候选阶段是允许的）。
     * 契约里坐标必须落在 0..1，不夹的话一个越界的框会让整个 OCR 请求 422，
     * 而那份答卷本可以正常识别。
     */
    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private List<Long> readRegionIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (JacksonException exception) {
            throw new IllegalStateException("候选的来源区域无法解析：" + json, exception);
        }
    }

    private record ConfirmedQuestion(String questionCode, List<Long> regionIds) {
    }

    private record RegionBox(long regionId, int pageNo, double x, double y, double width, double height) {
    }
}
