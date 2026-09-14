package com.homework.analysis.grading.review;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewCommand(
    @NotNull ReviewDecision decision,
    Integer finalScore,
    @Size(max = 64) String errorType,
    @Size(max = 4000) String feedback,
    @Size(max = 1000) String reason) {
}
