package com.america.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.america.ai.v1.*;
import com.america.backend.ai.grpc.InternalKeyClientInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** External policy gRPC fixture + local mocks for the other three assistants. */
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-1234", "ai.mock.enabled=true", "ai.mock.policy-enabled=false",
        "ai.grpc.internal-key=remote-test-key", "ai.grpc.deadline=5s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PolicyRemoteIntegrationTest {
    static final AtomicReference<PolicyQuestion> received = new AtomicReference<>();
    static final AtomicReference<String> receivedKey = new AtomicReference<>();
    static final Server remote = startRemote();
    static final int mockPort = freePort();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static Server startRemote() {
        try {
            ServerInterceptor auth = new ServerInterceptor() {
                public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                    receivedKey.set(headers.get(InternalKeyClientInterceptor.INTERNAL_KEY));
                    return next.startCall(call, headers);
                }
            };
            return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(ServerInterceptors.intercept(new PolicyAssistantGrpc.PolicyAssistantImplBase() {
                    @Override public void askStream(PolicyQuestion req, StreamObserver<PolicyAnswerChunk> out) {
                        received.set(req);
                        if (req.getQuestion().contains("자료 없음")) {
                            out.onError(Status.FAILED_PRECONDITION.withDescription("private provider detail").asRuntimeException());
                            return;
                        }
                        out.onNext(PolicyAnswerChunk.newBuilder().setDelta("외부 정책 서버 답변")
                            .addSources(Source.newBuilder().setTitle("공식 출처").setUrl("https://www.semas.or.kr/"))
                            .setDone(true).build());
                        out.onCompleted();
                    }
                }, auth)).build().start();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry r) {
        for (String name : new String[]{"policy", "promotion", "customer", "management"}) {
            r.add("ai.grpc.assistants." + name + ".host", () -> "127.0.0.1");
            r.add("ai.grpc.assistants." + name + ".port", () -> name.equals("policy") ? remote.getPort() : mockPort);
        }
    }
    @AfterAll static void closeRemote() { remote.shutdownNow(); }

    @Test void externalPolicyStreamsWithAuthAndSourcesWhileOtherAssistantsKeepMocks() throws Exception {
        String signup = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + UUID.randomUUID() + "@test.com\",\"password\":\"password123\",\"name\":\"사장님\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = "Bearer " + json.readTree(signup).get("accessToken").asText();
        String store = mvc.perform(post("/api/stores").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"테스트 가게\",\"category\":\"음식점\",\"region\":\"서울\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(store).get("id").asLong();
        String body = stream(token, id, "공통 지원 자격");
        assertThat(body).contains("외부 정책 서버 답변", "event:sources", "https://www.semas.or.kr/", "event:done").doesNotContain("[MOCK]");
        assertThat(receivedKey.get()).isEqualTo("remote-test-key");
        assertThat(received.get().getStore().getRegion()).isEqualTo("서울");
        assertThat(received.get().getQuestion()).isEqualTo("공통 지원 자격");
        String failure = stream(token, id, "자료 없음");
        assertThat(failure).contains("event:error", "AI_INVALID_REQUEST").doesNotContain("[MOCK]", "private provider detail", "event:done");
        String poster = mvc.perform(post("/api/stores/{id}/ai/promotion/poster", id).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"product\":\"떡볶이\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(poster).contains("[MOCK]");
    }
    String stream(String token, long id, String question) throws Exception {
        MvcResult result = mvc.perform(post("/api/stores/{id}/ai/policy/stream", id).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
            .content(json.writeValueAsString(java.util.Map.of("question", question))))
            .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
