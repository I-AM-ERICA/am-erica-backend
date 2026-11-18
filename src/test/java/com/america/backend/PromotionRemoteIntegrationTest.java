package com.america.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.Map;
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

@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-1234", "ai.mock.enabled=true",
    "ai.mock.promotion-enabled=false", "ai.grpc.internal-key=poster-test-key", "ai.grpc.deadline=5s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PromotionRemoteIntegrationTest {
    static final AtomicReference<PosterRequest> received = new AtomicReference<>();
    static final AtomicReference<PosterRevisionRequest> revision = new AtomicReference<>();
    static final AtomicReference<String> receivedKey = new AtomicReference<>();
    static final Server remote = startRemote();
    static final int mockPort = PolicyRemoteIntegrationTest.freePort();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static PosterDraft draft(String id, int version) {
        return PosterDraft.newBuilder().setId(id).setVersion(version).setMode("clova").setWidth(1080).setHeight(1350)
            .setResult(PosterResult.newBuilder().setHeadline("커피").setBody("매장 소식").setImageUrl("http://localhost:8081/images/test.png"))
            .setDesignJson("{\"template_id\":\"editorial\"}")
            .setImageProcessingJson("{\"background\":\"removed\"}").build();
    }
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
                .addService(ServerInterceptors.intercept(new PromotionAssistantGrpc.PromotionAssistantImplBase() {
                    @Override public void createDrafts(PosterRequest req, StreamObserver<PosterDrafts> out) {
                        received.set(req);
                        out.onNext(PosterDrafts.newBuilder().setMode("clova").addDrafts(draft("one", 1))
                            .addDrafts(draft("two", 1)).addDrafts(draft("three", 1)).build()); out.onCompleted();
                    }
                    @Override public void revisePoster(PosterRevisionRequest req, StreamObserver<PosterDraft> out) {
                        revision.set(req);
                        if (req.getExpectedVersion() != 1) { out.onError(Status.ABORTED.asRuntimeException()); return; }
                        out.onNext(draft(req.getDraftId(), 2)); out.onCompleted();
                    }
                    @Override public void getPoster(PosterGetRequest req, StreamObserver<PosterDraft> out) {
                        out.onNext(draft(req.getDraftId(), 2)); out.onCompleted();
                    }
                }, auth)).build().start();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        for (String name : new String[]{"policy", "promotion", "customer", "management"}) {
            registry.add("ai.grpc.assistants." + name + ".host", () -> "127.0.0.1");
            registry.add("ai.grpc.assistants." + name + ".port", () -> name.equals("promotion") ? remote.getPort() : mockPort);
        }
    }
    @AfterAll static void closeRemote() { remote.shutdownNow(); }
    String signup() throws Exception {
        var response = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
            Map.of("email", UUID.randomUUID()+"@test.com", "password", "password123", "name", "사장님"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(response).get("accessToken").asText();
    }
    @Test void imageDraftsAndRevisionsTravelThroughOwnedStoreWithVersionAndAuthentication() throws Exception {
        String token = signup();
        var store = mvc.perform(post("/api/stores").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"테스트 가게\",\"category\":\"카페\",\"region\":\"서울\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(store).get("id").asLong();
        String path = "/api/stores/"+id+"/ai/promotion/drafts";
        byte[] bytes = { (byte)137, 80, 78, 71, 13, 10, 26, 10 };
        String body = json.writeValueAsString(Map.of("product", "커피", "price", 0, "originalPrice", 4000,
            "productImage", Map.of("base64", Base64.getEncoder().encodeToString(bytes), "mediaType", "image/png"), "backgroundMode", "keep", "hideFooter", true));
        var response = mvc.perform(post(path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response).get("drafts").size()).isEqualTo(3);
        assertThat(receivedKey.get()).isEqualTo("poster-test-key");
        assertThat(received.get().getProductImage().getData().toByteArray()).isEqualTo(bytes);
        assertThat(received.get().hasPrice()).isTrue();
        assertThat(received.get().getPrice()).isZero();
        assertThat(received.get().getStore().getStoreId()).isEqualTo(Long.toString(id));
        assertThat(received.get().getStore().getName()).isEqualTo("테스트 가게");
        assertThat(received.get().getBackgroundMode()).isEqualTo("keep");
        assertThat(received.get().getHideFooter()).isTrue();
        mvc.perform(post(path+"/one/revisions").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"instruction\":\"초록색으로\",\"expectedVersion\":1}"))
            .andExpect(status().isOk());
        assertThat(revision.get().getStoreId()).isEqualTo(Long.toString(id));
        mvc.perform(post(path+"/one/revisions").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"instruction\":\"초록색으로\",\"expectedVersion\":2}"))
            .andExpect(status().isConflict());
        mvc.perform(get(path+"/one").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        String other = signup();
        mvc.perform(post(path).header("Authorization", other).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(get(path+"/one").header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(post(path+"/one/revisions").header("Authorization", other).contentType(MediaType.APPLICATION_JSON)
            .content("{\"instruction\":\"초록색으로\",\"expectedVersion\":1}")).andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"product\":\"커피\",\"productImage\":{\"base64\":\"!invalid\",\"mediaType\":\"image/png\"}}"))
            .andExpect(status().isBadRequest());
    }
}
