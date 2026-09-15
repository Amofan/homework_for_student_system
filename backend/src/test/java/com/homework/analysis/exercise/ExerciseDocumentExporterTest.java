package com.homework.analysis.exercise;

import com.homework.analysis.question.QuestionDifficulty;
import com.homework.analysis.question.RubricView;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
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

    private final ExerciseDocumentExporter exporter = new ExerciseDocumentExporter();

    @Test
    void 导出的文档可以重新打开且包含标题班级来源与三个层级() throws Exception {
        List<String> paragraphs = paragraphs(exporter.write(sample()));

        assertThat(paragraphs).contains(TITLE);
        assertThat(paragraphs).anyMatch(text -> text.equals("班级：七年级一班"));
        assertThat(paragraphs).anyMatch(text -> text.equals("来源作业：第一单元作业"));
        assertThat(paragraphs).anyMatch(text -> text.startsWith("生成时间："));
        assertThat(paragraphs).contains("一、基础巩固层", "二、方法纠错层", "三、综合提升层");
    }

    @Test
    void 每道题带题号题干分值与知识点() throws Exception {
        List<String> paragraphs = paragraphs(exporter.write(sample()));

        assertThat(paragraphs).anyMatch(text -> text.equals("1．（Q-401，5 分，知识点：一元一次方程）"));
        assertThat(paragraphs).contains("解方程 2x+1=5");
    }

    @Test
    void 答案与评分细则只出现在文末的教师参考区() throws Exception {
        List<String> paragraphs = paragraphs(exporter.write(sample()));

        int heading = paragraphs.indexOf(REFERENCE_HEADING);
        assertThat(heading).as("必须有教师参考区").isGreaterThan(-1);
        assertThat(paragraphs.subList(0, heading))
            .as("正文不能出现答案，否则打印给学生就泄题了")
            .noneMatch(text -> text.contains("标准答案"))
            .noneMatch(text -> text.contains("x=2"));
        assertThat(paragraphs.subList(heading, paragraphs.size()))
            .anyMatch(text -> text.contains("标准答案：x=2"))
            .anyMatch(text -> text.contains("列方程（4 分）：等量关系正确"));
    }

    @Test
    void 没有题目时仍然生成可打开的文档() throws Exception {
        ExerciseSetView empty = new ExerciseSetView(9, 101, "七年级一班", 501, "第一单元作业", TITLE,
            ExerciseStatus.APPROVED, CREATED_AT, null, List.of(), List.of());

        List<String> paragraphs = paragraphs(exporter.write(empty));

        assertThat(paragraphs).contains(TITLE, REFERENCE_HEADING);
    }

    /** 用 POI 重新解析字节数组：能解析出段落，才说明生成的是合法 docx。 */
    private static List<String> paragraphs(byte[] document) throws Exception {
        try (XWPFDocument opened = new XWPFDocument(new ByteArrayInputStream(document))) {
            return opened.getParagraphs().stream().map(XWPFParagraph::getText).toList();
        }
    }

    private static ExerciseSetView sample() {
        return new ExerciseSetView(7, 101, "七年级一班", 501, "第一单元作业", TITLE,
            ExerciseStatus.APPROVED, CREATED_AT, CREATED_AT.plusSeconds(60),
            List.of(
                new ExerciseItemView(ExerciseTier.FOUNDATION, 1, 401, "Q-401", "解方程 2x+1=5", 5,
                    QuestionDifficulty.BASIC, 301, "一元一次方程", "x=2",
                    List.of(new RubricView(1, 1, "列方程", "等量关系正确", 4))),
                new ExerciseItemView(ExerciseTier.CORRECTION, 1, 402, "Q-402", "计算 (x+1)(x-1)", 5,
                    QuestionDifficulty.MEDIUM, 302, "整式乘法", "x²-1", List.of()),
                new ExerciseItemView(ExerciseTier.IMPROVEMENT, 1, 403, "Q-403", "证明两三角形全等", 8,
                    QuestionDifficulty.ADVANCED, 303, "全等三角形", null, List.of())),
            List.of());
    }
}
