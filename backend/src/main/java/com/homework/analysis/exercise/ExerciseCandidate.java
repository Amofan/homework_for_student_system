package com.homework.analysis.exercise;

import com.homework.analysis.question.QuestionDifficulty;

/** 来源作业里可供组题的一道题，连同它的主知识点与难度。 */
public record ExerciseCandidate(
    long questionId,
    String questionCode,
    String content,
    String standardAnswer,
    int totalScore,
    QuestionDifficulty difficulty,
    long knowledgePointId,
    String knowledgePointName) {
}
