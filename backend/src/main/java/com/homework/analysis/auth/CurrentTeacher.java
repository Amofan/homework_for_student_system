package com.homework.analysis.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public final class CurrentTeacher {
    public long id(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("当前请求没有教师身份");
        }
        Number teacherId = token.getToken().getClaim("teacherId");
        if (teacherId == null) {
            throw new IllegalStateException("令牌缺少教师标识");
        }
        return teacherId.longValue();
    }
}
