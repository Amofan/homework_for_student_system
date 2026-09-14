package com.homework.analysis.question;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record QuestionCommand(
    @NotBlank @Size(max = 64) String questionCode,
    @NotNull QuestionType type,
    @NotBlank @Size(max = 4000) String content,
    @Size(max = 4000) String standardAnswer,
    @Min(1) int totalScore,
    long primaryKnowledgePointId,
    List<Long> secondaryKnowledgePointIds,
    List<@NotBlank @Size(max = 256) String> acceptedAnswers,
    List<@Valid RubricCommand> rubricItems) {
}
