package com.homework.analysis.grading;

import com.homework.analysis.question.QuestionType;
import com.homework.analysis.shared.error.DomainException;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;

@Component
public final class ObjectiveRuleGrader {
    public RuleGrade grade(QuestionType type, String actualAnswer, List<String> acceptedAnswers, int totalScore) {
        if (type == QuestionType.SOLUTION) {
            throw new DomainException("RULE_GRADING_UNSUPPORTED", "解答题不能使用客观题规则评分");
        }
        String actual = normalize(actualAnswer);
        boolean correct = acceptedAnswers != null && acceptedAnswers.stream()
            .map(this::normalize)
            .anyMatch(actual::equals);
        return new RuleGrade(correct ? totalScore : 0,
            correct ? ErrorType.CORRECT : ErrorType.ANSWER_MISMATCH);
    }

    String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim()
            .replaceAll("\\s*=\\s*", "=");
    }
}
