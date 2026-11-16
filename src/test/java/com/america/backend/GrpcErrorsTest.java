package com.america.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.america.backend.ai.grpc.AssistantType;
import com.america.backend.ai.grpc.GrpcErrors;
import com.america.backend.common.ApiException;
import io.grpc.Status;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

class GrpcErrorsTest {

    @Test
    void distinguishesSearchQuotaFromBusyOrOfflineWithoutExposingProviderDetails() {
        ApiException mapped = GrpcErrors.toApiException(AssistantType.POLICY,
                Status.UNAVAILABLE.withDescription("Web search unavailable [HTTP_429]; search_id=private").asRuntimeException());
        assertThat(mapped.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(mapped.getCode()).isEqualTo("AI_SEARCH_LIMIT");
        assertThat(mapped.getMessage()).doesNotContain("private", "HTTP_429");
    }

    @ParameterizedTest
    @CsvSource({
            "UNAVAILABLE, SERVICE_UNAVAILABLE, AI_UNAVAILABLE",
            "DEADLINE_EXCEEDED, GATEWAY_TIMEOUT, AI_TIMEOUT",
            "INVALID_ARGUMENT, BAD_REQUEST, AI_INVALID_REQUEST",
            "RESOURCE_EXHAUSTED, TOO_MANY_REQUESTS, AI_BUSY",
            "UNAUTHENTICATED, BAD_GATEWAY, AI_AUTH_FAILED",
            "INTERNAL, BAD_GATEWAY, AI_ERROR"
    })
    void mapsGrpcStatusToHttp(Status.Code code, HttpStatus httpStatus, String errorCode) {
        ApiException mapped = GrpcErrors.toApiException(AssistantType.MANAGEMENT,
                Status.fromCode(code).withDescription("secret internal detail").asRuntimeException());

        assertThat(mapped.getStatus()).isEqualTo(httpStatus);
        assertThat(mapped.getCode()).isEqualTo(errorCode);
        assertThat(mapped.getMessage()).startsWith("경영 AI 비서").doesNotContain("secret");
    }
}
