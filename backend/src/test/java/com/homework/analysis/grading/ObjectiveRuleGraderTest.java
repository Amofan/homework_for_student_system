package com.homework.analysis.grading;

import com.homework.analysis.question.QuestionType;
import com.homework.analysis.shared.error.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectiveRuleGraderTest {
    private final ObjectiveRuleGrader grader = new ObjectiveRuleGrader();

    @ParameterizedTest
    @CsvSource({"' A ','A',5", "'ｘ = 4','x=4',5", "'3.0','3',0"})
    void gradesOnlyDeclaredNormalizedAnswers(String actual, String accepted, int expected) {
        RuleGrade grade = grader.grade(QuestionType.FILL_BLANK, actual, List.of(accepted), 5);
        assertThat(grade.score()).isEqualTo(expected);
    }

    @Test
    void solutionQuestionsCannotUseRuleGrader() {
        assertThatThrownBy(() -> grader.grade(QuestionType.SOLUTION, "过程", List.of(), 10))
            .isInstanceOf(DomainException.class)
            .extracting("code").isEqualTo("RULE_GRADING_UNSUPPORTED");
    }
}
