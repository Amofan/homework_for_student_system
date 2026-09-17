package com.homework.analysis.auth;

import com.homework.analysis.shared.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

@Configuration
public class SecurityConfiguration {

    /** 令牌中的 {@code role} 声明映射成 Spring Security 角色权限。 */
    private static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/health", "/api/auth/login").permitAll()
                // 角色分区必须写在 /api/** 之前：顺序颠倒时所有已登录用户都能进教师区。
                .requestMatchers("/api/teacher/**").hasRole(AuthPrincipal.ROLE_TEACHER)
                .requestMatchers("/api/student/**").hasRole(AuthPrincipal.ROLE_STUDENT)
                .requestMatchers("/api/auth/**").authenticated()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                    writeError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                        "AUTH_REQUIRED", "请先登录"))
                .accessDeniedHandler((request, response, exception) ->
                    writeError(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                        "ACCESS_DENIED", "无权执行此操作")))
            .oauth2ResourceServer(server -> server
                .authenticationEntryPoint((request, response, exception) ->
                    writeError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                        "AUTH_REQUIRED", "登录状态无效或已过期"))
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
            .build();
    }

    private static void writeError(HttpServletResponse response, ObjectMapper objectMapper, int status,
                                   String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(code, message));
    }

    /**
     * 由 {@code role} 声明生成 {@code ROLE_TEACHER} / {@code ROLE_STUDENT} 权限。
     *
     * <p>不使用默认转换器：默认只认 {@code scope}/{@code scp} 声明，本项目签发的令牌里
     * 没有 scope，权限集会恒为空，于是所有 {@code hasRole} 规则都变成 403。
     */
    private static Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfiguration::authoritiesOf);
        return converter;
    }

    private static Collection<GrantedAuthority> authoritiesOf(Jwt jwt) {
        String role = jwt.getClaimAsString("role");
        if (role == null || role.isBlank()) {
            return List.of();
        }
        return List.of(new SimpleGrantedAuthority(ROLE_AUTHORITY_PREFIX + role));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtService jwtService) {
        return jwtService.decoder();
    }
}
