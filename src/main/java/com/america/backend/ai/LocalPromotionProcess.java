package com.america.backend.ai;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.america.backend.ai.grpc.AiGrpcProperties;
import com.america.backend.ai.grpc.AssistantType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Owns one local Python engine. API keys travel through its environment, never command-line arguments. */
@Component
@ConditionalOnProperty(name = "ai.promotion.local.enabled", havingValue = "true")
public class LocalPromotionProcess implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(LocalPromotionProcess.class);
    private final Environment environment;
    private final AiGrpcProperties grpc;
    private Process process;

    public LocalPromotionProcess(Environment environment, AiGrpcProperties grpc) {
        this.environment = environment;
        this.grpc = grpc;
    }

    @Override public int getPhase() { return Integer.MIN_VALUE + 100; }
    @Override public boolean isRunning() { return process != null && process.isAlive(); }

    @Override public synchronized void start() {
        if (isRunning()) return;
        var endpoint = grpc.endpoint(AssistantType.PROMOTION);
        if (!Set.of("localhost", "127.0.0.1").contains(endpoint.host()) || endpoint.tls()) {
            throw new IllegalStateException("홍보 자동 실행은 로컬 주소와 TLS=false에서만 사용할 수 있습니다. 원격 서버는 AI_PROMOTION_AUTOSTART=false로 설정하세요.");
        }
        Path directory = Path.of(environment.getProperty("ai.promotion.local.directory", "../Promotion-AI")).toAbsolutePath().normalize();
        String defaultPython = Path.of(System.getProperty("user.home"), ".local", "share", "Promotion-AI", "venv-py3.12", "bin", "python").toString();
        Path python = Path.of(environment.getProperty("ai.promotion.local.python", defaultPython)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(directory.resolve("promotion_assistant/__main__.py"))) {
            throw new IllegalStateException("AI_PROMOTION_DIR에 Promotion-AI 폴더를 지정하세요: " + directory);
        }
        if (!Files.isExecutable(python)) {
            throw new IllegalStateException("AI_PROMOTION_PYTHON에 준비된 Python 가상환경 실행 파일을 지정하세요. 최초 1회 Promotion-AI에서 python3.12 run.py --setup-only를 실행하세요.");
        }
        int httpPort = environment.getProperty("ai.promotion.local.http-port", Integer.class, 8081);
        if (httpPort < 1 || httpPort > 65535 || httpPort == endpoint.port()) {
            throw new IllegalStateException("홍보 HTTP 포트는 gRPC와 다른 1~65535 포트여야 합니다.");
        }
        assertFree(endpoint.port());
        assertFree(httpPort);
        Path output = Path.of(environment.getProperty("ai.promotion.local.log-file", "build/promotion-ai.log")).toAbsolutePath();
        Duration timeout = environment.getProperty("ai.promotion.local.startup-timeout", Duration.class, Duration.ofMinutes(5));
        List<String> command = new ArrayList<>(List.of(python.toString(), "-m", "promotion_assistant",
                "--host", "127.0.0.1", "--port", Integer.toString(httpPort),
                "--grpc-address", "127.0.0.1:" + endpoint.port(), "--env-file", directory.resolve(".env").toString()));
        if (environment.getProperty("ai.promotion.local.demo", Boolean.class, false)) command.add("--demo");
        if (environment.getProperty("ai.promotion.local.no-cutout", Boolean.class, false)) command.add("--no-cutout");
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(output.toFile()));
        builder.environment().put("PYTHONUNBUFFERED", "1");
        builder.environment().put("AI_INTERNAL_KEY", grpc.internalKey());
        builder.environment().put("POSTER_PUBLIC_BASE_URL", environment.getProperty("POSTER_PUBLIC_BASE_URL", "http://127.0.0.1:" + httpPort));
        for (String name : List.of("CLOVA_STUDIO_API_KEY", "POSTER_DATA_DIR", "POSTER_TIMEOUT_SECONDS", "POSTER_FONT_PATH", "POSTER_BOLD_FONT_PATH")) {
            String value = environment.getProperty(name);
            if (value != null && !value.isBlank()) builder.environment().put(name, value);
        }
        try {
            Files.createDirectories(output.getParent());
            log.info("홍보 엔진 자동 시작: gRPC {}, HTTP {}, 로그 {}", endpoint.port(), httpPort, output);
            process = builder.start();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build();
            HttpRequest ready = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/v1/templates"))
                    .header("X-Internal-Key", grpc.internalKey()).timeout(Duration.ofSeconds(2)).GET().build();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IllegalStateException("홍보 엔진이 종료되었습니다. CLOVA 키와 로그를 확인하세요: " + output);
                try {
                    if (http.send(ready, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                        log.info("홍보 엔진 준비 완료 (PID {}). Spring Boot 종료 시 함께 종료합니다.", process.pid());
                        return;
                    }
                } catch (IOException ignored) { /* Package/model initialization may still be running. */ }
                Thread.sleep(250);
            }
            throw new IllegalStateException("홍보 엔진 준비 시간이 초과되었습니다. 로그를 확인하세요: " + output);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("홍보 엔진 시작이 중단되었습니다.", error);
        } catch (IOException | RuntimeException error) {
            stop();
            throw new IllegalStateException("홍보 엔진 자동 시작 실패: " + error.getMessage(), error);
        }
    }

    private static void assertFree(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", port), 300);
            throw new IllegalStateException("홍보 포트 " + port + "를 다른 프로그램이 사용 중입니다. 별도로 실행한 홍보 서버를 종료하거나 포트 설정을 바꾸세요.");
        } catch (IOException ignored) { /* No reachable listener; also check whether binding is allowed. */ }
        try (ServerSocket socket = new ServerSocket()) {
            // Match Python's reusable listener; recently closed connections are not active servers.
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
        } catch (IOException error) {
            throw new IllegalStateException("홍보 포트 " + port + "를 다른 프로그램이 사용 중입니다. 별도로 실행한 홍보 서버를 종료하거나 포트 설정을 바꾸세요.");
        }
    }

    @Override public synchronized void stop() {
        if (process == null) return;
        Process owned = process;
        owned.destroy();
        try {
            if (!owned.waitFor(10, TimeUnit.SECONDS)) {
                owned.destroyForcibly();
                owned.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) {
            owned.destroyForcibly();
            Thread.currentThread().interrupt();
        } finally {
            process = null;
        }
        log.info("Spring Boot가 시작한 홍보 엔진을 종료했습니다.");
    }
}
