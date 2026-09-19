package com.hourhive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hourhive")
public record AppProperties(
        String databaseUrl,
        String jwtSecret,
        long tokenTtlHours,
        String allowedOrigins,
        int signupBonusMinutes) {
}
