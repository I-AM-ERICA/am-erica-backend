package com.america.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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

@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-1234", "ai.mock.enabled=true",
    "ai.mock.customer-enabled=false", "ai.grpc.internal-key=other-assistants-key", "ai.grpc.deadline=5s",
    "ai.grpc.customer-internal-key=customer-only-key", "ai.grpc.customer-deadline=300s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerRemoteIntegrationTest {
    static final AtomicReference<ReviewReplyRequest> received = new AtomicReference<>();
    static final AtomicReference<String> receivedKey = new AtomicReference<>();
    static final AtomicReference<Long> secondsRemaining = new AtomicReference<>();
    static final Server remote = startRemote();
    static final int mockPort = PolicyRemoteIntegrationTest.freePort();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static Server startRemote() {
        try {
            ServerInterceptor auth = new ServerInterceptor() {
                public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                    Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                    receivedKey.set(headers.get(InternalKeyClientInterceptor.INTERNAL_KEY));
                    return next.startCall(call, headers);
                }
            };
            return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(ServerInterceptors.intercept(new CustomerAssistantGrpc.CustomerAssistantImplBase() {
                    @Override public void draftReply(ReviewReplyRequest req, StreamObserver<ReviewReplyResult> out) {
                        received.set(req);
                        secondsRemaining.set(Context.current().getDeadline().timeRemaining(TimeUnit.SECONDS));
                        if (req.getReviewText().equals("unavailable")) {
                            out.onError(Status.UNAVAILABLE.asRuntimeException()); return;
                        }
                        out.onNext(ReviewReplyResult.newBuilder().setReply("기다리게 해 드려 죄송합니다.")
                            .setSentiment("negative").addKeywords("대기").build()); out.onCompleted();
                    }
                }, auth)).build().start();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        for (String name : new String[]{"policy", "promotion", "customer", "management"}) {
            registry.add("ai.grpc.assistants." + name + ".host", () -> "127.0.0.1");
            registry.add("ai.grpc.assistants." + name + ".port", () -> name.equals("customer") ? remote.getPort() : mockPort);
        }
    }
    @AfterAll static void closeRemote() { remote.shutdownNow(); }
    String signup() throws Exception {
        String response = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\""+UUID.randomUUID()+"@test.com\",\"password\":\"password123\",\"name\":\"사장님\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(response).get("accessToken").asText();
    }
    @Test void customerUsesItsOwnKeyAndDeadlineWithOwnedStoreAndKeepsOtherAssistantsWorking() throws Exception {
        String token = signup();
        String store = mvc.perform(post("/api/stores").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"테스트 카페\",\"category\":\"카페\",\"region\":\"서울\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(store).get("id").asLong();
        String path = "/api/stores/"+id+"/ai/customer/reply";
        String body = "{\"reviewText\":\"음료가 늦었어요.\",\"tone\":\"정중하고 따뜻하게\"}";
        mvc.perform(post(path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("기다리게 해 드려 죄송합니다."));
        assertThat(receivedKey.get()).isEqualTo("customer-only-key");
        assertThat(secondsRemaining.get()).isBetween(270L, 300L);
        assertThat(received.get().getStore().getStoreId()).isEqualTo(Long.toString(id));
        assertThat(received.get().getStore().getName()).isEqualTo("테스트 카페");
        assertThat(received.get().getReviewText()).isEqualTo("음료가 늦었어요.");
        assertThat(received.get().getTone()).isEqualTo("정중하고 따뜻하게");
        assertThat(received.get().getRating()).isZero();
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", signup()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reviewText\":\"unavailable\"}")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/stores/"+id+"/ai/policy").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"지원 정책 알려줘\"}")).andExpect(status().isOk());
    }
}
