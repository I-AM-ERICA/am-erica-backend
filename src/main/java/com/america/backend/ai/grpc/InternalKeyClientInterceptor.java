package com.america.backend.ai.grpc;

import java.util.UUID;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/** 모든 gRPC 호출에 내부 인증 키와 요청 ID를 메타데이터로 붙입니다. */
public class InternalKeyClientInterceptor implements ClientInterceptor {

    public static final Metadata.Key<String> INTERNAL_KEY =
            Metadata.Key.of("x-internal-key", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> REQUEST_ID =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);

    private final String internalKey;

    public InternalKeyClientInterceptor(String internalKey) {
        this.internalKey = internalKey;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(INTERNAL_KEY, internalKey);
                headers.put(REQUEST_ID, UUID.randomUUID().toString());
                super.start(responseListener, headers);
            }
        };
    }
}
