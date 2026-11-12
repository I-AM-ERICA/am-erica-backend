package com.america.backend.ai.mock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.america.backend.ai.grpc.AiGrpcProperties;
import com.america.backend.ai.grpc.AssistantType;
import com.america.backend.ai.grpc.InternalKeyClientInterceptor;
import io.grpc.BindableService;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * {@code ai.mock.enabled=true}일 때 설정된 비서 포트에 mock gRPC 서버를 127.0.0.1로 띄웁니다.
 * Spring은 실제 운영과 같은 gRPC 클라이언트 경로로 호출하므로 Python 서버 없이 전체 흐름을 확인할 수 있습니다.
 */
@Component
@ConditionalOnProperty(name = "ai.mock.enabled", havingValue = "true")
public class MockAiServers implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MockAiServers.class);

    private final AiGrpcProperties properties;
    private final List<Server> servers = new ArrayList<>();
    private volatile boolean running;

    public MockAiServers(AiGrpcProperties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        Map<Integer, List<AssistantType>> byPort = new LinkedHashMap<>();
        for (AssistantType type : AssistantType.values()) {
            byPort.computeIfAbsent(properties.endpoint(type).port(), port -> new ArrayList<>()).add(type);
        }
        ServerInterceptor auth = new InternalKeyCheck(properties.internalKey());
        byPort.forEach((port, types) -> {
            NettyServerBuilder builder = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", port));
            HealthStatusManager health = new HealthStatusManager();
            types.forEach(type -> builder.addService(ServerInterceptors.intercept(service(type), auth)));
            builder.addService(health.getHealthService());
            health.setStatus("", ServingStatus.SERVING);
            try {
                servers.add(builder.build().start());
            } catch (IOException e) {
                stop();
                throw new UncheckedIOException("mock AI gRPC 서버 시작 실패 (포트 " + port + ")", e);
            }
            log.warn("[MOCK] AI 비서 gRPC 서버 실행: 127.0.0.1:{} {}", port, types);
        });
        running = true;
    }

    @Override
    public synchronized void stop() {
        servers.forEach(Server::shutdown);
        for (Server server : servers) {
            try {
                if (!server.awaitTermination(3, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        servers.clear();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static BindableService service(AssistantType type) {
        return switch (type) {
            case POLICY -> new MockAssistantServices.Policy();
            case PROMOTION -> new MockAssistantServices.Promotion();
            case CUSTOMER -> new MockAssistantServices.Customer();
            case MANAGEMENT -> new MockAssistantServices.Management();
        };
    }

    /** Python AI 서버가 구현해야 하는 내부 키 검사와 동일한 동작입니다. */
    private record InternalKeyCheck(String expected) implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            if (!expected.equals(headers.get(InternalKeyClientInterceptor.INTERNAL_KEY))) {
                call.close(Status.UNAUTHENTICATED.withDescription("invalid internal key"), new Metadata());
                return new ServerCall.Listener<>() {
                };
            }
            return next.startCall(call, headers);
        }
    }
}
