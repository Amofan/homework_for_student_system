package com.homework.analysis.grading.ai;

import java.util.List;

public record AiGradingRequest(
    String anonymousAnswerId,
    String question,
    String standardAnswer,
    String studentAnswer,
    int totalScore,
    List<Rubric> rubrics,
    List<String> allowedErrorTypes) {

    public record Rubric(int id, String title, String criteria, int maxScore) {}
}
