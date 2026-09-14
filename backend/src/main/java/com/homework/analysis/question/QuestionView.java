package com.homework.analysis.question;

import java.util.List;

public record QuestionView(
    long id,
    String questionCode,
    QuestionType type,
    String content,
    String standardAnswer,
    int totalScore,
    long primaryKnowledgePointId,
    List<Long> secondaryKnowledgePointIds,
    List<String> acceptedAnswers,
    List<RubricView> rubricItems) {
}
