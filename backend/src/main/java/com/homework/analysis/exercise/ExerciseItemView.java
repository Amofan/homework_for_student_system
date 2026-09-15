package com.homework.analysis.exercise;

import com.homework.analysis.question.QuestionDifficulty;
import com.homework.analysis.question.RubricView;

import java.util.List;

/**
 * 练习单中的一道题。标准答案与评分细则一并返回：
 * 调用方是题库所有者本人，导出 Word 的教师参考区需要这些内容，
 * 单次查询同时供接口展示与文档导出使用，避免两处各写一份取数逻辑。
 */
public record ExerciseItemView(
    ExerciseTier tier,
    int sortOrder,
    long questionId,
    String questionCode,
    String content,
    int totalScore,
    QuestionDifficulty difficulty,
    long knowledgePointId,
    String knowledgePointName,
    String standardAnswer,
    List<RubricView> rubricItems) {
}
