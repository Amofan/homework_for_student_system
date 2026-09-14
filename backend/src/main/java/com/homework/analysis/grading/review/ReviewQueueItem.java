package com.homework.analysis.grading.review;

public record ReviewQueueItem(
    long resultId,
    long answerId,
    String studentNo,
    String studentName,
    String questionCode,
    String questionContent,
    String answerContent,
    String source,
    int suggestedScore,
    int totalScore,
    String errorType,
    String teacherExplanation,
    String studentFeedback,
    String scoreDetails) {
}
