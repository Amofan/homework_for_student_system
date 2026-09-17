package com.homework.analysis.auth;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * 当前请求主体的统一入口。
 *
 * <p>过滤链上的 {@code /api/teacher/**} 与 {@code /api/student/**} 规则是第一道门；
 * 本类提供第二道门，供仍挂在旧路径（{@code /api/classes}、{@code /api/questions} 等）
 * 上的教师接口使用。两道门都必须存在：只靠 URL 规则，学生一旦访问兼容期的旧教师路径
 * 就会读到别人的数据。
 */
@Component
public final class CurrentActor {

    /** 解析当前主体，不检查角色。 */
    public AuthPrincipal require(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) {
            throw new DomainException("AUTH_REQUIRED", "请先登录", HttpStatus.UNAUTHORIZED);
        }
        return JwtService.toPrincipal(token.getToken());
    }

    /** 要求教师身份，返回其 {@code teacherId}。 */
    public AuthPrincipal requireTeacher(Authentication authentication) {
        AuthPrincipal principal = require(authentication);
        if (!principal.isTeacher() || principal.teacherId() == null) {
            throw new DomainException("AUTH_TEACHER_REQUIRED", "该操作需要教师账号", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /** 要求学生身份，返回其 {@code studentId}。 */
    public AuthPrincipal requireStudent(Authentication authentication) {
        AuthPrincipal principal = require(authentication);
        if (!principal.isStudent() || principal.studentId() == null) {
            throw new DomainException("AUTH_STUDENT_REQUIRED", "该操作需要学生账号", HttpStatus.FORBIDDEN);
        }
        return principal;
    }
}
