package com.homework.analysis.paper;

import com.homework.analysis.question.QuestionDifficulty;
import com.homework.analysis.question.QuestionType;
import com.homework.analysis.question.RubricCommand;

import java.util.List;

/**
 * 一道候选题。
 *
 * <p>名字里的"候选"是关键：它可能来自 OCR，也可能来自教师的编辑，但无论如何都还没进
 * {@code question} 表。确认之前的一切操作都只影响候选，这样"识别结果不可信"与
 * "题库数据可信"之间有一条清楚的界线。
 *
 * @param sourceRegionIds 构成这道题的所有来源区域。合并会把多块并进来，拆分会把一块分出去，
 *                        但**任何操作都不会删除来源区域**——区域是校对时唯一能核对像素的依据。
 * @param assetRegionIds  默认作为题图的来源区域，教师可改。
 * @param matchedQuestionCode 答案卷候选匹配到的空白卷题号；空白卷候选恒为空。
 * @param warnings        结构化警告码，见 {@code PaperImportService} 中的常量。
 * @param version         乐观锁版本；教师每改一次加一。
 */
public record PaperQuestionCandidate(
    long id,
    long documentId,
    String documentKind,
    int orderNo,
    String questionCode,
    QuestionType questionType,
    String content,
    String standardAnswer,
    List<String> acceptedAnswers,
    List<RubricCommand> rubricItems,
    Integer totalScore,
    QuestionDifficulty difficulty,
    Long primaryKnowledgePointId,
    List<Long> sourceRegionIds,
    List<Long> assetRegionIds,
    String matchedQuestionCode,
    Double confidence,
    List<String> warnings,
    String reviewStatus,
    int version) {
}
