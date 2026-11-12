package com.america.backend.ai.grpc;

import com.america.backend.common.ApiException;
import io.grpc.Status;
import org.springframework.http.HttpStatus;

/** gRPC 상태 코드를 클라이언트용 HTTP 오류로 변환합니다. 내부 오류 메시지는 응답에 노출하지 않습니다. */
public final class GrpcErrors {

    private GrpcErrors() {
    }

    public static ApiException toApiException(AssistantType type, Throwable error) {
        Status status = Status.fromThrowable(error);
        String name = type.label() + " AI 비서";
        return switch (status.getCode()) {
            case INVALID_ARGUMENT, FAILED_PRECONDITION, OUT_OF_RANGE ->
                    new ApiException(HttpStatus.BAD_REQUEST, "AI_INVALID_REQUEST", name + "가 요청을 처리할 수 없습니다.");
            case NOT_FOUND ->
                    new ApiException(HttpStatus.NOT_FOUND, "AI_NOT_FOUND", name + "가 결과를 찾지 못했습니다.");
            case RESOURCE_EXHAUSTED ->
                    new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_BUSY", name + "가 혼잡합니다. 잠시 후 다시 시도하세요.");
            case DEADLINE_EXCEEDED ->
                    new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT", name + " 응답 시간이 초과되었습니다.");
            case UNAVAILABLE ->
                    new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", name + "에 연결할 수 없습니다.");
            case UNAUTHENTICATED, PERMISSION_DENIED ->
                    new ApiException(HttpStatus.BAD_GATEWAY, "AI_AUTH_FAILED", name + " 내부 인증에 실패했습니다.");
            default ->
                    new ApiException(HttpStatus.BAD_GATEWAY, "AI_ERROR", name + " 처리 중 오류가 발생했습니다.");
        };
    }
}
