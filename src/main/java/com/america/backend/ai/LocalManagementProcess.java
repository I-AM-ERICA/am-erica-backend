package com.america.backend.ai;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.america.backend.ai.grpc.AiGrpcProperties;
import com.america.backend.ai.grpc.AssistantType;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Start the local real model once; propagate the internal key only through the environment. */
@Component
@ConditionalOnProperty(name = "ai.management.local.enabled", havingValue = "true")
public class LocalManagementProcess implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(LocalManagementProcess.class);
    private final Environment environment;
    private final AiGrpcProperties grpc;
    private Process process;

    public LocalManagementProcess(Environment environment, AiGrpcProperties grpc) {
        this.environment = environment;
        this.grpc = grpc;
    }

    @Override public int getPhase() { return Integer.MIN_VALUE + 101; }
    @Override public boolean isRunning() { return process != null && process.isAlive(); }

    @Override public synchronized void start() {
        if (isRunning()) return;
        var endpoint = grpc.endpoint(AssistantType.MANAGEMENT);
        if (!Set.of("localhost", "127.0.0.1").contains(endpoint.host()) || endpoint.tls()) {
            throw new IllegalStateException("Chronos 자동 실행은 로컬 주소와 TLS=false에서만 사용할 수 있습니다.");
        }
        Path directory = Path.of(environment.getProperty("ai.management.local.directory", "../Chronos")).toAbsolutePath().normalize();
        Path python = Path.of(environment.getProperty("ai.management.local.python", "../Chronos/.venv/bin/python")).toAbsolutePath().normalize();
        Path model = Path.of(environment.getProperty("ai.management.local.model-path", "../Chronos/models")).toAbsolutePath().normalize();
        if (!Files.isExecutable(python) || !Files.isRegularFile(directory.resolve("src/chronos_sales/management_grpc.py"))) {
            throw new IllegalStateException("Chronos 가상환경에 pip install -e '.[management]'를 실행하고 AI_MANAGEMENT_DIR/PYTHON 경로를 확인하세요.");
        }
        for (String name : List.of("config.json", "generation_config.json", "model.safetensors")) {
            if (!Files.isRegularFile(model.resolve(name))) throw new IllegalStateException("Chronos 모델 파일이 없습니다: " + model.resolve(name));
        }
        if (grpc.internalKey().isBlank()) throw new IllegalStateException("AI_INTERNAL_KEY가 필요합니다.");
        Path output = Path.of("build/chronos-ai.log").toAbsolutePath();
        ManagedChannel probe = null;
        try {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("127.0.0.1", endpoint.port()));
            }
            Files.createDirectories(output.getParent());
            ProcessBuilder builder = new ProcessBuilder(python.toString(), "-m", "chronos_sales.management_grpc",
                    "--address", "127.0.0.1:" + endpoint.port(), "--model-path", model.toString())
                    .directory(directory.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(output.toFile()));
            builder.environment().put("AI_INTERNAL_KEY", grpc.internalKey());
            builder.environment().put("PYTHONUNBUFFERED", "1");
            builder.environment().put("HF_HUB_OFFLINE", "1");
            process = builder.start();
            probe = ManagedChannelBuilder.forAddress("127.0.0.1", endpoint.port()).usePlaintext().build();
            Duration timeout = environment.getProperty("ai.management.local.startup-timeout", Duration.class, Duration.ofSeconds(90));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IllegalStateException("Chronos가 종료되었습니다. 로그: " + output);
                try {
                    var result = HealthGrpc.newBlockingStub(probe).withDeadlineAfter(1, TimeUnit.SECONDS)
                            .check(HealthCheckRequest.newBuilder().setService("america.ai.v1.ManagementAssistant").build());
                    if (result.getStatus() == HealthCheckResponse.ServingStatus.SERVING) {
                        log.info("Chronos 모델 준비 완료: port={}, PID={}", endpoint.port(), process.pid());
                        return;
                    }
                } catch (StatusRuntimeException ignored) { /* Wait until model loading finishes. */ }
                Thread.sleep(250);
            }
            throw new IllegalStateException("Chronos 준비 시간 초과. 로그: " + output);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("Chronos 시작이 중단되었습니다.", error);
        } catch (IOException | RuntimeException error) {
            stop();
            throw new IllegalStateException("Chronos 자동 실행 실패: " + error.getMessage(), error);
        } finally {
            if (probe != null) probe.shutdownNow();
        }
    }

    @Override public synchronized void stop() {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        } finally { process = null; }
    }
}
