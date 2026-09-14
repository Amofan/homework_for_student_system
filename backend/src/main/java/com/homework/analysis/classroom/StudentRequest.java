package com.homework.analysis.classroom;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record StudentRequest(
    @NotBlank @Size(max = 64) String studentNo,
    @NotBlank @Size(max = 64) String name) {
}
