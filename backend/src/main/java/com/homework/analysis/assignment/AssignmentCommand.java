package com.homework.analysis.assignment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AssignmentCommand(
    long classId,
    @NotBlank @Size(max = 128) String title,
    @NotEmpty List<Long> questionIds) {
}
