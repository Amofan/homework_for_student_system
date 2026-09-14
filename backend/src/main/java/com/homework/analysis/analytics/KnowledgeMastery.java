package com.homework.analysis.analytics;

public record KnowledgeMastery(long knowledgePointId, String code, String name, long earnedScore,
                               long possibleScore, double masteryRatio, long answerCount) {
}
