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
    // 允许为空：为空时按 MEDIUM 保存，使改动前已存在的请求继续可用
    QuestionDifficulty difficulty,
    long primaryKnowledgePointId,
    List<Long> secondaryKnowledgePointIds,
    List<@NotBlank @Size(max = 256) String> acceptedAnswers,
    List<@Valid RubricCommand> rubricItems) {
}
