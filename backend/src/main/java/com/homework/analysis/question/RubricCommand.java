package com.homework.analysis.question;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RubricCommand(
    @Min(1) int orderNo,
    @NotBlank @Size(max = 128) String title,
    @NotBlank @Size(max = 1000) String criteria,
    @Min(1) int maxScore) {
}
