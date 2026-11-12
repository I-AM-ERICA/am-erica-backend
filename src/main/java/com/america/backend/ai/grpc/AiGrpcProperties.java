package com.america.backend.ai.grpc;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Python AI 서버의 4개 gRPC 비서 접속 정보입니다.
 * 키는 {@code policy | promotion | customer | management} 입니다.
 */
@ConfigurationProperties("ai.grpc")
public record AiGrpcProperties(String internalKey, Duration deadline, Map<AssistantType, Endpoint> assistants) {

    public record Endpoint(String host, int port, boolean tls) {
    }

    public AiGrpcProperties {
        if (internalKey == null || internalKey.isBlank()) {
            throw new IllegalStateException("AI_INTERNAL_KEY(ai.grpc.internal-key)가 필요합니다.");
        }
        if (deadline == null) {
            deadline = Duration.ofSeconds(30);
        }
        assistants = assistants == null ? new EnumMap<>(AssistantType.class) : new EnumMap<>(assistants);
        for (AssistantType type : AssistantType.values()) {
            Endpoint endpoint = assistants.get(type);
            if (endpoint == null || endpoint.host() == null || endpoint.host().isBlank() || endpoint.port() <= 0) {
                throw new IllegalStateException(
                        "ai.grpc.assistants." + type.name().toLowerCase() + " 의 host/port 설정이 필요합니다.");
            }
        }
    }

    public Endpoint endpoint(AssistantType type) {
        return assistants.get(type);
    }
}
