package com.homework.analysis.auth;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
final class AuthService {
    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    AuthService(AuthRepository repository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    LoginResponse login(LoginRequest request) {
        AuthRepository.LoginAccount account = repository.findLoginAccount(request.username())
            .filter(AuthRepository.LoginAccount::enabled)
            .filter(found -> passwordEncoder.matches(request.password(), found.passwordHash()))
            .orElseThrow(() -> new DomainException(
                "AUTH_INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED));
        String token = jwtService.issue(account.userId(), account.teacherId(), account.role());
        return new LoginResponse(token, "Bearer", 30 * 60L);
    }

    MeResponse currentTeacher(long teacherId) {
        AuthRepository.TeacherProfile teacher = repository.findTeacher(teacherId)
            .orElseThrow(() -> new DomainException(
                "TEACHER_NOT_FOUND", "教师不存在或已停用", HttpStatus.NOT_FOUND));
        return new MeResponse(teacher.teacherId(), teacher.displayName(), teacher.schoolName());
    }

    record LoginResponse(String accessToken, String tokenType, long expiresIn) {}
    record MeResponse(long teacherId, String displayName, String schoolName) {}
}
