package com.homework.analysis.grading.ai;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Component
public final class GradingPromptBuilder {
    private final ObjectMapper objectMapper;

    GradingPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String build(AiGradingRequest request) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "anonymousAnswerId", request.anonymousAnswerId(),
                "question", request.question(),
                "standardAnswer", request.standardAnswer() == null ? "" : request.standardAnswer(),
                "studentAnswer", request.studentAnswer(),
                "totalScore", request.totalScore(),
                "rubrics", request.rubrics(),
                "allowedErrorTypes", request.allowedErrorTypes()));
            return """
                你是初中数学作业助教。请严格依据评分项给出建议，不得推测学生身份。
                仅返回 JSON，字段必须为 suggestedScore、scoreDetails、errorType、teacherExplanation、studentFeedback、needsTeacherReview。
                scoreDetails 每项必须包含 rubricId、score、evidence，分项合计必须等于 suggestedScore。
                输入：
                """ + payload;
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法构造 AI 评分请求", exception);
        }
    }
}
