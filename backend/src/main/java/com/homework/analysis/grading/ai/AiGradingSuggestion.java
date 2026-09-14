package com.homework.analysis.grading.ai;

import java.util.List;

public record AiGradingSuggestion(
    int suggestedScore,
    List<RubricScoreDetail> scoreDetails,
    String errorType,
    String teacherExplanation,
    String studentFeedback,
    boolean needsTeacherReview) {

    public record RubricScoreDetail(int rubricId, int score, String evidence) {}
}
