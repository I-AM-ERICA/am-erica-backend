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
        // Python emits only this enum-like provider code, never provider bodies/credentials.
        if (type == AssistantType.POLICY && status.getCode() == Status.Code.UNAVAILABLE
                && status.getDescription() != null && status.getDescription().contains("[HTTP_429]")) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_SEARCH_LIMIT",
                    "정책 검색 서비스의 이용 한도에 도달했습니다. 한도 복구 후 다시 이용해 주세요.");
        }
        return switch (status.getCode()) {
            case INVALID_ARGUMENT, FAILED_PRECONDITION, OUT_OF_RANGE ->
                    new ApiException(HttpStatus.BAD_REQUEST, "AI_INVALID_REQUEST", name + "가 요청을 처리할 수 없습니다.");
            case ABORTED ->
                    new ApiException(HttpStatus.CONFLICT, "AI_VERSION_CONFLICT", "다른 수정이 먼저 저장되었습니다. 최신 시안을 불러온 후 다시 수정해 주세요.");
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
