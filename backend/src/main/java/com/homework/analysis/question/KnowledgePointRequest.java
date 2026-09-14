package com.homework.analysis.question;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgePointRequest(
    Long parentId,
    @NotBlank @Size(max = 64) String code,
    @NotBlank @Size(max = 128) String name,
    @Min(7) @Max(9) int grade,
    boolean active) {
}
