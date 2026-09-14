package com.homework.analysis.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record LoginRequest(
    @NotBlank @Size(max = 64) String username,
    @NotBlank @Size(max = 128) String password) {
}
