package com.america.backend.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, String issuer, Duration ttl) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET(app.jwt.secret)은 32바이트 이상이어야 합니다.");
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "am-erica";
        }
        if (ttl == null) {
            ttl = Duration.ofHours(12);
        }
    }
}
