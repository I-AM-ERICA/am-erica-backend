package com.america.backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.naver")
public record NaverProperties(String clientId, String clientSecret, String redirectUri) {
    public boolean configured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank()
                && redirectUri != null && !redirectUri.isBlank();
    }

    @Override
    public String toString() {
        return "NaverProperties[credentials=REDACTED]";
    }
}
