package com.homework.analysis.exercise;

import com.homework.analysis.question.RubricView;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把一张已确认的练习单排成可打印的 Word 文档。
 *
 * <p>正文只出现题号、题干、分值和知识点；标准答案与评分细则统一放在文末的
 * 教师参考区，并以分页符隔开，学生拿到打印稿时不会看到答案。
 */
@Component
public class ExerciseDocumentExporter {

    private static final String[] TIER_NUMERALS = {"一", "二", "三"};
    /** 降级公式的 run 颜色，与页面端 KaTeX 的错误色一致。 */
    private static final String FALLBACK_COLOR = "CC0000";
    private static final DateTimeFormatter GENERATED_AT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public ExportedDocument write(ExerciseSetView exercise) {
        // LinkedHashSet：题号要按在文档里出现的顺序去重，同一题多次降级只点名一次。
        Set<String> fallbackCodes = new LinkedHashSet<>();
        try (XWPFDocument document = new XWPFDocument()) {
            writeHeader(document, exercise);
            writeItems(document, exercise.items(), fallbackCodes);
            writeTeacherReference(document, exercise.items(), fallbackCodes);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return new ExportedDocument(output.toByteArray(), List.copyOf(fallbackCodes));
        } catch (IOException exception) {
            throw new UncheckedIOException("练习单 Word 文档生成失败", exception);
        }
    }

    private static void writeHeader(XWPFDocument document, ExerciseSetView exercise) {
        XWPFParagraph title = document.createParagraph();
        title.setAlignment(ParagraphAlignment.CENTER);
        styled(title, 18, true).setText(exercise.title());

        meta(document, "班级：" + exercise.className());
        meta(document, "来源作业：" + exercise.sourceAssignmentTitle());
        meta(document, "生成时间：" + GENERATED_AT.format(exercise.createdAt()));

        styled(document.createParagraph(), 10, false)
            .setText("说明：本练习单按班级已确认的学情画像分层组题，题目全部取自现有题库。");
    }

    private static void writeItems(XWPFDocument document, List<ExerciseItemView> items,
                                   Set<String> fallbackCodes) {
        for (ExerciseTier tier : ExerciseTier.values()) {
            List<ExerciseItemView> tierItems = itemsOf(items, tier);
            if (tierItems.isEmpty()) {
                continue;
            }
            styled(document.createParagraph(), 14, true)
                .setText(tierNumber(tier) + "、" + tier.label());
            styled(document.createParagraph(), 10, false).setText(tier.description());

            for (ExerciseItemView item : tierItems) {
                // 题号、分值、知识点都是系统生成的，不参与公式转换，保持普通 run。
                styled(document.createParagraph(), 12, true).setText(item.sortOrder() + "．（"
                    + item.questionCode() + "，" + item.totalScore() + " 分，知识点："
                    + item.knowledgePointName() + "）");
                writeText(document.createParagraph(), 12, false, item.content(), fallbackCodes,
                    item.questionCode());
            }
        }
    }

    private static void writeTeacherReference(XWPFDocument document, List<ExerciseItemView> items,
                                              Set<String> fallbackCodes) {
        XWPFParagraph heading = document.createParagraph();
        heading.setPageBreak(true);
        styled(heading, 16, true).setText("教师参考区（答案与评分细则）");

        for (ExerciseTier tier : ExerciseTier.values()) {
            List<ExerciseItemView> tierItems = itemsOf(items, tier);
            if (tierItems.isEmpty()) {
                continue;
            }
            styled(document.createParagraph(), 13, true).setText(tier.label());
            for (ExerciseItemView item : tierItems) {
                XWPFParagraph line = document.createParagraph();
                styled(line, 11, true).setText(item.questionCode() + "　标准答案：");
                writeText(line, 11, false,
                    item.standardAnswer() == null ? "略" : item.standardAnswer(),
                    fallbackCodes, item.questionCode());
                for (RubricView rubric : item.rubricItems()) {
                    // 拆成五条 run：只有评分项标题与得分标准是用户文本，标红不能波及序号和分值。
                    XWPFParagraph paragraph = document.createParagraph();
                    styled(paragraph, 11, false).setText("　　" + rubric.orderNo() + ". ");
                    writeText(paragraph, 11, false, rubric.title(), fallbackCodes, item.questionCode());
                    styled(paragraph, 11, false).setText("（" + rubric.maxScore() + " 分）：");
                    writeText(paragraph, 11, false, rubric.criteria(), fallbackCodes, item.questionCode());
                }
            }
        }
    }

    /**
     * 写入一段可能含公式的用户文本。
     *
     * <p>子集内的公式换成 Unicode；子集外的命令保留原文并把该 run 标红，
     * 同时记下题号，让教师在下载时知道是哪些题。
     */
    private static void writeText(XWPFParagraph paragraph, int fontSize, boolean bold, String raw,
                                  Set<String> fallbackCodes, String questionCode) {
        LatexToUnicode.Conversion conversion = LatexToUnicode.convert(raw == null ? "" : raw);
        XWPFRun run = styled(paragraph, fontSize, bold);
        run.setText(conversion.text());
        if (conversion.fellBack()) {
            run.setColor(FALLBACK_COLOR);
            fallbackCodes.add(questionCode);
        }
    }

    private static void meta(XWPFDocument document, String text) {
        styled(document.createParagraph(), 10, false).setText(text);
    }

    private static List<ExerciseItemView> itemsOf(List<ExerciseItemView> items, ExerciseTier tier) {
        return items.stream().filter(item -> item.tier() == tier).toList();
    }

    /** 层级序号用中文数字；层级多于预设个数时退回阿拉伯数字，避免越界。 */
    private static String tierNumber(ExerciseTier tier) {
        return tier.ordinal() < TIER_NUMERALS.length
            ? TIER_NUMERALS[tier.ordinal()]
            : String.valueOf(tier.ordinal() + 1);
    }

    /** 中英文分开设置字体：西文用 Times New Roman，中文用宋体，打印稿才不会出现方块字。 */
    private static XWPFRun styled(XWPFParagraph paragraph, int fontSize, boolean bold) {
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Times New Roman");
        run.setFontFamily("宋体", XWPFRun.FontCharRange.eastAsia);
        run.setFontSize(fontSize);
        run.setBold(bold);
        return run;
    }
}
