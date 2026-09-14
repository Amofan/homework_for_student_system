package com.homework.analysis.auth;

import com.homework.analysis.shared.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
final class AuthController {
    private final AuthService service;
    private final CurrentTeacher currentTeacher;

    AuthController(AuthService service, CurrentTeacher currentTeacher) {
        this.service = service;
        this.currentTeacher = currentTeacher;
    }

    @PostMapping("/login")
    ApiResponse<AuthService.LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(service.login(request));
    }

    @GetMapping("/me")
    ApiResponse<AuthService.MeResponse> me(Authentication authentication) {
        return ApiResponse.ok(service.currentTeacher(currentTeacher.id(authentication)));
    }
}
