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
import java.util.List;

/**
 * 把一张已确认的练习单排成可打印的 Word 文档。
 *
 * <p>正文只出现题号、题干、分值和知识点；标准答案与评分细则统一放在文末的
 * 教师参考区，并以分页符隔开，学生拿到打印稿时不会看到答案。
 */
@Component
public class ExerciseDocumentExporter {

    private static final String[] TIER_NUMERALS = {"一", "二", "三"};
    private static final DateTimeFormatter GENERATED_AT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public byte[] write(ExerciseSetView exercise) {
        try (XWPFDocument document = new XWPFDocument()) {
            writeHeader(document, exercise);
            writeItems(document, exercise.items());
            writeTeacherReference(document, exercise.items());

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
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

    private static void writeItems(XWPFDocument document, List<ExerciseItemView> items) {
        for (ExerciseTier tier : ExerciseTier.values()) {
            List<ExerciseItemView> tierItems = itemsOf(items, tier);
            if (tierItems.isEmpty()) {
                continue;
            }
            styled(document.createParagraph(), 14, true)
                .setText(tierNumber(tier) + "、" + tier.label());
            styled(document.createParagraph(), 10, false).setText(tier.description());

            for (ExerciseItemView item : tierItems) {
                styled(document.createParagraph(), 12, true).setText(item.sortOrder() + "．（"
                    + item.questionCode() + "，" + item.totalScore() + " 分，知识点："
                    + item.knowledgePointName() + "）");
                styled(document.createParagraph(), 12, false).setText(item.content());
            }
        }
    }

    private static void writeTeacherReference(XWPFDocument document, List<ExerciseItemView> items) {
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
                styled(line, 11, false).setText(item.standardAnswer() == null ? "略" : item.standardAnswer());
                for (RubricView rubric : item.rubricItems()) {
                    styled(document.createParagraph(), 11, false).setText("　　" + rubric.orderNo() + ". "
                        + rubric.title() + "（" + rubric.maxScore() + " 分）：" + rubric.criteria());
                }
            }
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
