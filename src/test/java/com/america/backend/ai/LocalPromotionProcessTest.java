package com.america.backend.ai;

import static org.assertj.core.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.concurrent.CountDownLatch;
import com.america.backend.ai.grpc.AiGrpcProperties;
import com.america.backend.ai.grpc.AssistantType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.mock.env.MockEnvironment;

@EnabledOnOs({OS.MAC, OS.LINUX})
class LocalPromotionProcessTest {
    @TempDir Path temporary;

    int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
    AiGrpcProperties grpc(int port, String host) {
        var endpoints = new EnumMap<AssistantType, AiGrpcProperties.Endpoint>(AssistantType.class);
        for (var type : AssistantType.values()) endpoints.put(type, new AiGrpcProperties.Endpoint(host, port, false));
        return new AiGrpcProperties("test-internal-key", Duration.ofSeconds(2), endpoints);
    }
    MockEnvironment environment(int httpPort) throws Exception {
        Files.createDirectories(temporary.resolve("promotion_assistant"));
        Files.writeString(temporary.resolve("promotion_assistant/__main__.py"), "# fixture");
        Path launcher = temporary.resolve("python fixture");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(EngineFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Files.writeString(launcher, "#!/bin/sh\nexec " + quote(java) + " -cp " + quote(classes) + " "
                + quote(EngineFixture.class.getName()) + " \"$@\"\n");
        assertThat(launcher.toFile().setExecutable(true)).isTrue();
        var env = new MockEnvironment().withProperty("ai.promotion.local.directory", temporary.toString())
                .withProperty("ai.promotion.local.python", launcher.toString())
                .withProperty("ai.promotion.local.http-port", Integer.toString(httpPort))
                .withProperty("ai.promotion.local.log-file", temporary.resolve("engine.log").toString())
                .withProperty("ai.promotion.local.startup-timeout", "8s")
                .withProperty("CLOVA_STUDIO_API_KEY", "test-clova-key")
                .withProperty("POSTER_PUBLIC_BASE_URL", "http://localhost:" + httpPort);
        env.setConversionService(new ApplicationConversionService());
        return env;
    }
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    @Test void startsWithServerCredentialsWaitsForReadinessAndStopsOnlyItsOwnedChild() throws Exception {
        int port = freePort(), http = freePort();
        var service = new LocalPromotionProcess(environment(http), grpc(port, "127.0.0.1"));
        try {
            service.start();
            assertThat(service.isRunning()).isTrue();
            // Calling start twice does not create a second engine on the same ports.
            service.start();
            assertThat(Files.readString(temporary.resolve("engine.log"))).contains("backend-clova=true", "public-url=http://localhost:" + http);
        } finally { service.stop(); }
        assertThat(service.isRunning()).isFalse();
        // A normal restart must not confuse recently closed connections with an active server.
        try { service.start(); assertThat(service.isRunning()).isTrue(); }
        finally { service.stop(); }
        try (ServerSocket grpcSocket = new ServerSocket(); ServerSocket httpSocket = new ServerSocket()) {
            grpcSocket.setReuseAddress(true); httpSocket.setReuseAddress(true);
            grpcSocket.bind(new InetSocketAddress("127.0.0.1", port));
            httpSocket.bind(new InetSocketAddress("127.0.0.1", http));
        }
    }
    @Test void occupiedPortIsReportedWithoutKillingItsExistingOwner() throws Exception {
        int http = freePort();
        try (ServerSocket owner = new ServerSocket(0)) {
            var service = new LocalPromotionProcess(environment(http), grpc(owner.getLocalPort(), "127.0.0.1"));
            assertThatThrownBy(service::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("다른 프로그램");
            service.stop();
            assertThat(owner.isClosed()).isFalse();
            assertThat(service.isRunning()).isFalse();
        }
    }
    @Test void earlyExitAndReadinessTimeoutLeaveNoChildRunning() throws Exception {
        var env = environment(freePort());
        Path script = Path.of(env.getProperty("ai.promotion.local.python"));
        Files.writeString(script, "#!/bin/sh\nexit 7\n");
        var service = new LocalPromotionProcess(env, grpc(freePort(), "localhost"));
        assertThatThrownBy(service::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("종료");
        assertThat(service.isRunning()).isFalse();

        env = environment(freePort());
        env.setProperty("ai.promotion.local.startup-timeout", "500ms");
        env.setProperty("CLOVA_STUDIO_API_KEY", "fixture-never-ready");
        service = new LocalPromotionProcess(env, grpc(freePort(), "localhost"));
        assertThatThrownBy(service::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("초과");
        assertThat(service.isRunning()).isFalse();
    }
    @Test void remoteEndpointCannotSpawnALocalEngine() throws Exception {
        var service = new LocalPromotionProcess(environment(freePort()), grpc(freePort(), "remote.example"));
        assertThatThrownBy(service::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("로컬 주소");
    }

    /** JDK-only subprocess fixture: the launch wrapper accepts the same CLI and env as Python. */
    public static class EngineFixture {
        public static void main(String[] args) throws Exception {
            int port = 0, grpcPort = 0;
            for (int i = 0; i < args.length - 1; i++) {
                if (args[i].equals("--port")) port = Integer.parseInt(args[i + 1]);
                if (args[i].equals("--grpc-address")) grpcPort = Integer.parseInt(args[i + 1].split(":")[1]);
            }
            ServerSocket grpc = new ServerSocket(grpcPort);
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            http.createContext("/v1/templates", exchange -> {
                boolean ready = "test-clova-key".equals(System.getenv("CLOVA_STUDIO_API_KEY"));
                boolean authorized = System.getenv("AI_INTERNAL_KEY").equals(exchange.getRequestHeaders().getFirst("X-Internal-Key"));
                exchange.sendResponseHeaders(authorized && ready ? 200 : 503, -1);
                exchange.close();
            });
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { http.stop(0); try { grpc.close(); } catch (Exception ignored) {} }));
            http.start();
            System.out.println("backend-clova=" + "test-clova-key".equals(System.getenv("CLOVA_STUDIO_API_KEY")));
            System.out.println("public-url=" + System.getenv("POSTER_PUBLIC_BASE_URL"));
            new CountDownLatch(1).await();
        }
    }
}
