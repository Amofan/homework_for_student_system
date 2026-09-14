package com.homework.analysis.grading.ai;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

@Component
public final class AiResultValidator {
    public AiGradingSuggestion validate(AiGradingRequest request, AiGradingSuggestion suggestion) {
        if (suggestion.suggestedScore() < 0 || suggestion.suggestedScore() > request.totalScore()) {
            throw new DomainException("AI_SCORE_OUT_OF_RANGE", "AI_SCORE_OUT_OF_RANGE");
        }
        if (!request.allowedErrorTypes().contains(suggestion.errorType())) {
            throw new DomainException("AI_ERROR_TYPE_INVALID", "AI_ERROR_TYPE_INVALID");
        }
        if (suggestion.scoreDetails() == null ||
            new HashSet<>(suggestion.scoreDetails().stream().map(AiGradingSuggestion.RubricScoreDetail::rubricId).toList()).size()
                != request.rubrics().size()) {
            throw new DomainException("AI_RUBRIC_DETAIL_INVALID", "AI_RUBRIC_DETAIL_INVALID");
        }
        Map<Integer, Integer> maximums = new HashMap<>();
        request.rubrics().forEach(rubric -> maximums.put(rubric.id(), rubric.maxScore()));
        int detailTotal = 0;
        for (var detail : suggestion.scoreDetails()) {
            Integer maximum = maximums.get(detail.rubricId());
            if (maximum == null || detail.score() < 0 || detail.score() > maximum ||
                detail.evidence() == null || detail.evidence().isBlank()) {
                throw new DomainException("AI_RUBRIC_DETAIL_INVALID", "AI_RUBRIC_DETAIL_INVALID");
            }
            detailTotal += detail.score();
        }
        if (detailTotal != suggestion.suggestedScore()) {
            throw new DomainException("AI_SCORE_DETAIL_MISMATCH", "AI_SCORE_DETAIL_MISMATCH");
        }
        return suggestion;
    }
}
