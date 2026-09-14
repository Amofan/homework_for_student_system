package com.homework.analysis.auth;

import java.time.Instant;

public record AuthPrincipal(long userId, long teacherId, String role, Instant expiresAt) {}
