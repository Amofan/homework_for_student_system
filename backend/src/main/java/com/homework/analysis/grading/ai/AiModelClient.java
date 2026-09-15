package com.homework.analysis.grading.ai;

public interface AiModelClient {
    ModelCall grade(AiGradingRequest request);
}
