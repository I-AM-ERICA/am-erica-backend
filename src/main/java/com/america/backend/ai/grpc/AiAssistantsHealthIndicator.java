package com.america.backend.ai.grpc;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * 표준 gRPC Health Checking Protocol로 4개 비서 상태를 확인합니다.
 * 일부 비서가 내려가도 중앙 API는 동작하므로 전체 상태를 DOWN이 아닌 DEGRADED로 표시합니다.
 */
@Component("aiAssistants")
public class AiAssistantsHealthIndicator implements HealthIndicator {

    static final Status DEGRADED = new Status("DEGRADED");

    private final AiChannels channels;

    public AiAssistantsHealthIndicator(AiChannels channels) {
        this.channels = channels;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        boolean allServing = true;
        for (AssistantType type : AssistantType.values()) {
            String state;
            try {
                state = HealthGrpc.newBlockingStub(channels.get(type))
                        .withDeadlineAfter(2, TimeUnit.SECONDS)
                        .check(HealthCheckRequest.getDefaultInstance())
                        .getStatus()
                        .name();
            } catch (StatusRuntimeException e) {
                state = e.getStatus().getCode().name();
            }
            allServing &= HealthCheckResponse.ServingStatus.SERVING.name().equals(state);
            builder.withDetail(type.name().toLowerCase(Locale.ROOT), state);
        }
        return allServing ? builder.build() : builder.status(DEGRADED).build();
    }
}
