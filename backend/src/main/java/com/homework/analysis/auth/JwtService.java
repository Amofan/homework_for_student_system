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

    public String issue(long userId, long teacherId, String role) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(accessMinutes, ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(Long.toString(userId))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("teacherId", teacherId)
            .claim("role", role)
            .build();
        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }

    public AuthPrincipal parse(String token) {
        Jwt jwt = decoder.decode(token);
        return new AuthPrincipal(
            Long.parseLong(jwt.getSubject()),
            ((Number) jwt.getClaim("teacherId")).longValue(),
            jwt.getClaimAsString("role"),
            jwt.getExpiresAt());
    }

    public JwtDecoder decoder() {
        return decoder;
    }
}
