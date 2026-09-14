package com.homework.analysis.grading.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiResultValidatorTest {
    private final AiResultValidator validator = new AiResultValidator();

    @Test
    void rejectsScoreDetailMismatchUnknownLabelsAndOverflow() {
        var request = new AiGradingRequest("answer-99", "题目", "标准答案", "学生答案", 10,
            List.of(new AiGradingRequest.Rubric(1, "列式", "正确列式", 4),
                new AiGradingRequest.Rubric(2, "求解", "正确求解", 6)),
            List.of("CALCULATION_ERROR", "METHOD_ERROR", "CORRECT"));
        var mismatch = new AiGradingSuggestion(8,
            List.of(new AiGradingSuggestion.RubricScoreDetail(1, 3, "部分正确"),
                new AiGradingSuggestion.RubricScoreDetail(2, 4, "部分正确")),
            "CALCULATION_ERROR", "说明", "反馈", true);
        assertThatThrownBy(() -> validator.validate(request, mismatch))
            .hasMessageContaining("AI_SCORE_DETAIL_MISMATCH");

        var unknown = new AiGradingSuggestion(10,
            List.of(new AiGradingSuggestion.RubricScoreDetail(1, 4, "正确"),
                new AiGradingSuggestion.RubricScoreDetail(2, 6, "正确")),
            "UNKNOWN", "说明", "反馈", false);
        assertThatThrownBy(() -> validator.validate(request, unknown))
            .hasMessageContaining("AI_ERROR_TYPE_INVALID");
    }
}
