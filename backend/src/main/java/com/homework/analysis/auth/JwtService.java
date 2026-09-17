package com.homework.analysis.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public final class JwtService {
    private final String issuer;
    private final int accessMinutes;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    @Autowired
    public JwtService(
        @Value("${app.jwt.secret}") String secret,
        @Value("${app.jwt.issuer}") String issuer,
        @Value("${app.jwt.access-minutes}") int accessMinutes) {
        this(secret, issuer, accessMinutes, Clock.systemUTC());
    }

    JwtService(String secret, String issuer, int accessMinutes, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 UTF-8 bytes");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.issuer = issuer;
        this.accessMinutes = accessMinutes;
        this.clock = clock;
        this.encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(key)
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
        timestampValidator.setClock(clock);
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            timestampValidator, new JwtIssuerValidator(issuer)));
        this.decoder = jwtDecoder;
    }

    /** 教师令牌：带 {@code teacherId}，不带密码改密标志。 */
    public String issueTeacher(long userId, long teacherId) {
        return issue(userId, teacherId, null, AuthPrincipal.ROLE_TEACHER, false);
    }

    /** 学生令牌：带 {@code studentId} 与首次改密标志，不带姓名或学号。 */
    public String issueStudent(long userId, long studentId, boolean passwordChangeRequired) {
        return issue(userId, null, studentId, AuthPrincipal.ROLE_STUDENT, passwordChangeRequired);
    }

    /**
     * 兼容旧调用点的重载，仅支持教师令牌。
     *
     * <p>保留原因：现有测试与工具类仍以 {@code issue(userId, teacherId, "TEACHER")} 造令牌。
     * 新代码请改用 {@link #issueTeacher(long, long)} 与 {@link #issueStudent(long, long, boolean)}；
     * 传入非教师角色会立即失败，避免把学生令牌错发成教师令牌。
     *
     * @deprecated 使用 {@link #issueTeacher(long, long)} 或 {@link #issueStudent(long, long, boolean)}。
     */
    @Deprecated(since = "2026-09-16", forRemoval = true)
    public String issue(long userId, long teacherId, String role) {
        if (!AuthPrincipal.ROLE_TEACHER.equals(role)) {
            throw new IllegalArgumentException("兼容重载只签发教师令牌，学生令牌请使用 issueStudent");
        }
        return issueTeacher(userId, teacherId);
    }

    private String issue(long userId, Long teacherId, Long studentId, String role,
                         boolean passwordChangeRequired) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(accessMinutes, ChronoUnit.MINUTES);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(Long.toString(userId))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("role", role);
        if (teacherId != null) {
            claims.claim("teacherId", teacherId);
        }
        if (studentId != null) {
            claims.claim("studentId", studentId);
        }
        if (passwordChangeRequired) {
            claims.claim("passwordChangeRequired", true);
        }
        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(headers, claims.build())).getTokenValue();
    }

    public AuthPrincipal parse(String token) {
        return toPrincipal(decoder.decode(token));
    }

    /**
     * 从已解码令牌读取主体。
     *
     * <p>资源服务器在过滤链中已经验签并解码，业务侧再调 {@link #parse(String)} 会重复验签，
     * 因此 {@link CurrentActor} 直接复用本方法。
     */
    static AuthPrincipal toPrincipal(Jwt jwt) {
        Boolean passwordChangeRequired = jwt.getClaim("passwordChangeRequired");
        return new AuthPrincipal(
            Long.parseLong(jwt.getSubject()),
            longClaim(jwt, "teacherId"),
            longClaim(jwt, "studentId"),
            jwt.getClaimAsString("role"),
            Boolean.TRUE.equals(passwordChangeRequired),
            jwt.getExpiresAt());
    }

    private static Long longClaim(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        return value instanceof Number number ? number.longValue() : null;
    }

    public JwtDecoder decoder() {
        return decoder;
    }
}
