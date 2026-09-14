package com.homework.analysis.grading.ai;

public interface AiModelClient {
    AiGradingSuggestion grade(AiGradingRequest request);
}
