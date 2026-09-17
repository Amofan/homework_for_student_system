package com.homework.analysis.auth;

import com.homework.analysis.classroom.account.PasswordChangeCommand;
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
    private final CurrentActor currentActor;

    AuthController(AuthService service, CurrentActor currentActor) {
        this.service = service;
        this.currentActor = currentActor;
    }

    @PostMapping("/login")
    ApiResponse<AuthService.LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(service.login(request));
    }

    /** 教师与学生共用，按令牌角色返回对应资料。 */
    @GetMapping("/me")
    ApiResponse<AuthService.MeResponse> me(Authentication authentication) {
        return ApiResponse.ok(service.me(currentActor.require(authentication)));
    }

    /**
     * 首次改密与自助改密。
     *
     * <p>不按角色分两个接口：两者要做的事完全相同——校验当前密码、拒绝复用、换发新令牌，
     * 拆开只会让“学生改密”和“教师改密”的密码策略有机会各自演化。
     */
    @PostMapping("/password/change")
    ApiResponse<AuthService.LoginResponse> changePassword(
        @Valid @RequestBody PasswordChangeCommand command, Authentication authentication) {
        return ApiResponse.ok(service.changePassword(currentActor.require(authentication), command));
    }
}
