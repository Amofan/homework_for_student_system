package com.homework.analysis.classroom;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClassroomRequest(
    @NotBlank @Size(max = 64) String classCode,
    @NotBlank @Size(max = 64) String name,
    @Min(7) @Max(9) Integer grade,
    @Size(max = 32) String semester) {
}
