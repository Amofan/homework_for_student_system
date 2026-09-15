package com.homework.analysis.exercise;

import com.homework.analysis.question.QuestionDifficulty;
import com.homework.analysis.question.RubricView;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导出测试统一"重新用 POI 打开导出的字节数组"再断言：
 * 只断言字节非空的话，连打不开的损坏文档也会通过。
 */
class ExerciseDocumentExporterTest {
    private static final Instant CREATED_AT = Instant.parse("2026-03-04T05:06:07Z");
    private static final String TITLE = "七年级一班 · 第一单元作业 分层练习";
    private static final String REFERENCE_HEADING = "教师参考区（答案与评分细则）";
    /** 降级题目的 run 颜色：红色，和 KaTeX 的错误色一致。 */
    private static final String FALLBACK_COLOR = "CC0000";

    private final ExerciseDocumentExporter exporter = new ExerciseDocumentExporter();

    @Test
    void 导出的文档可以重新打开且包含标题班级来源与三个层级() throws Exception {
        List<String> paragraphs = paragraphs(exporter.write(sample()).content());

        assertThat(paragraphs).contains(TITLE);
        assertThat(paragraphs).anyMatch(text -> text.equals("班级：七年级一班"));
        assertThat(paragraphs).anyMatch(text -> text.equals("来源作业：第一单元作业"));
        assertThat(paragraphs).anyMatch(text -> text.startsWith("生成时间："));
        assertThat(paragraphs).contains("一、基础巩固层", "二、方法纠错层", "三、综合提升层");
    }

    @Test
    void 每道题带题号题干分值与知识点() throws Exception {
        List<String> paragraphs = paragraphs(exporter.write(sample()).content());

        assertThat(paragraphs).anyMatch(text -> text.equals("1．（Q-ALG-007，5 分，知识点：整式乘法）"));
        assertThat(paragraphs).contains("计算 2x²y");
    }

    @Test
    void 答案与评分细则只出现在文末的教师参考区() throws Exception {
        List<String> paragraphs = paragraphs(exporter.write(sample()).content());

        int heading = paragraphs.indexOf(REFERENCE_HEADING);
        assertThat(heading).as("必须有教师参考区").isGreaterThan(-1);
        assertThat(paragraphs.subList(0, heading))
            .as("正文不能出现答案，否则打印给学生就泄题了")
            .noneMatch(text -> text.contains("标准答案"))
            .noneMatch(text -> text.contains("4x²"))
            .noneMatch(text -> text.contains("∠2=65°"));
        assertThat(paragraphs.subList(heading, paragraphs.size()))
            .anyMatch(text -> text.contains("标准答案：4x²"))
            .anyMatch(text -> text.contains("写出 (a+b)/c（4 分）：不得使用"));
    }

    @Test
    void 公式按Unicode子集转换并收集降级题号() throws Exception {
        ExportedDocument exported = exporter.write(sample());
        List<String> paragraphs = paragraphs(exported.content());

        // 段内命令换成 Unicode，段外的裸上标也一样要换（标准答案 4x^2 没有 $）
        assertThat(paragraphs)
            .anyMatch(text -> text.contains("2x²y"))
            .anyMatch(text -> text.contains("标准答案：4x²"))
            .anyMatch(text -> text.contains("∠1=65°"))
            .anyMatch(text -> text.contains("(a+b)/c"));
        assertThat(exported.fallbackQuestionCodes()).containsExactly("Q-ALG-007");
    }

    @Test
    void 降级公式的run标红且同段落其他run不受影响() throws Exception {
        ExportedDocument exported = exporter.write(sample());

        try (XWPFDocument opened = open(exported.content())) {
            assertThat(opened.getParagraphs().stream()
                .flatMap(paragraph -> paragraph.getRuns().stream())
                .filter(run -> run.text().contains("\\vec{a}"))
                .map(XWPFRun::getColor))
                .containsExactly(FALLBACK_COLOR);
            // 同段落里"写出 (a+b)/c"已成功转换，不该跟着变红
            assertThat(opened.getParagraphs().stream()
                .flatMap(paragraph -> paragraph.getRuns().stream())
                .filter(run -> run.text().contains("写出 (a+b)/c"))
                .map(XWPFRun::getColor))
                .containsExactly((String) null);
        }
    }

    @Test
    void 题目重复出现同一次降级只点名一次() throws Exception {
        ExerciseItemView item = algebraItem(new RubricView(1, 1, "写出 $\\frac{a+b}{c}$", "不得使用 $\\vec{a}$", 4));
        ExerciseSetView repeated = new ExerciseSetView(7, 101, "七年级一班", 501, "第一单元作业", TITLE,
            ExerciseStatus.APPROVED, CREATED_AT, CREATED_AT.plusSeconds(60),
            List.of(item, item), List.of());

        assertThat(exporter.write(repeated).fallbackQuestionCodes()).containsExactly("Q-ALG-007");
    }

    @Test
    void 全部落在子集内时不返回降级题号() throws Exception {
        ExportedDocument exported = exporter.write(supportedSample());

        assertThat(exported.fallbackQuestionCodes()).isEmpty();
        assertThat(paragraphs(exported.content())).anyMatch(text -> text.contains("标准答案：4x²"));
    }

    @Test
    void 没有题目时仍然生成可打开的文档() throws Exception {
        ExerciseSetView empty = new ExerciseSetView(9, 101, "七年级一班", 501, "第一单元作业", TITLE,
            ExerciseStatus.APPROVED, CREATED_AT, null, List.of(), List.of());

        List<String> paragraphs = paragraphs(exporter.write(empty).content());

        assertThat(paragraphs).contains(TITLE, REFERENCE_HEADING);
        assertThat(exporter.write(empty).fallbackQuestionCodes()).isEmpty();
    }

    /** 用 POI 重新解析字节数组：能解析出段落，才说明生成的是合法 docx。 */
    private static List<String> paragraphs(byte[] document) throws Exception {
        try (XWPFDocument opened = open(document)) {
            return opened.getParagraphs().stream().map(XWPFParagraph::getText).toList();
        }
    }

    private static XWPFDocument open(byte[] document) throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(document));
    }

    /** 含一处降级公式（评分标准里的 \vec{a}）的真实体例练习单。 */
    private static ExerciseSetView sample() {
        return new ExerciseSetView(7, 101, "七年级一班", 501, "第一单元作业", TITLE,
            ExerciseStatus.APPROVED, CREATED_AT, CREATED_AT.plusSeconds(60),
            List.of(
                algebraItem(new RubricView(1, 1, "写出 $\\frac{a+b}{c}$", "不得使用 $\\vec{a}$", 4)),
                geometryItem(),
                new ExerciseItemView(ExerciseTier.IMPROVEMENT, 1, 403, "Q-403", "证明两三角形全等", 8,
                    QuestionDifficulty.ADVANCED, 303, "全等三角形", null, List.of())),
            List.of());
    }

    /** 同样的题目，但所有公式都在子集内。 */
    private static ExerciseSetView supportedSample() {
        return new ExerciseSetView(8, 101, "七年级一班", 501, "第一单元作业", TITLE,
            ExerciseStatus.APPROVED, CREATED_AT, CREATED_AT.plusSeconds(60),
            List.of(
                algebraItem(new RubricView(1, 1, "写出 $\\frac{a+b}{c}$", "步骤完整", 4)),
                geometryItem()),
            List.of());
    }

    private static ExerciseItemView algebraItem(RubricView rubric) {
        return new ExerciseItemView(ExerciseTier.FOUNDATION, 1, 401, "Q-ALG-007",
            "计算 $2x^2y$", 5, QuestionDifficulty.BASIC, 301, "整式乘法", "4x^2", List.of(rubric));
    }

    private static ExerciseItemView geometryItem() {
        return new ExerciseItemView(ExerciseTier.CORRECTION, 1, 402, "Q-GEO-005",
            "已知 $\\angle 1=65°$，$AB \\parallel CD$", 5, QuestionDifficulty.MEDIUM,
            302, "平行线", "$\\angle 2=65°$", List.of());
    }
}
