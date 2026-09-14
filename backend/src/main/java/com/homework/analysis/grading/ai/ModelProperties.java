package com.homework.analysis.grading.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.model")
public record ModelProperties(
    boolean enabled,
    String baseUrl,
    String apiPath,
    String name,
    String apiKey,
    int timeoutSeconds) {
}
