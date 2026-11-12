package com.america.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 실제 gRPC 클라이언트 → 127.0.0.1 mock 비서 서버 경로까지 포함한 통합 테스트입니다. */
@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-1234",
        "ai.grpc.internal-key=test-internal-key",
        "ai.grpc.deadline=5s",
        "ai.mock.enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    private static final int GRPC_PORT = freePort();

    @DynamicPropertySource
    static void grpcEndpoints(DynamicPropertyRegistry registry) {
        for (String name : new String[]{"policy", "promotion", "customer", "management"}) {
            registry.add("ai.grpc.assistants." + name + ".host", () -> "127.0.0.1");
            registry.add("ai.grpc.assistants." + name + ".port", () -> GRPC_PORT);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void storeOwnerCanUseAllFourAssistants() throws Exception {
        String token = signup();
        long storeId = createStore(token);
        addSales(token, storeId, 14);

        mvc.perform(post("/api/stores/{id}/ai/management/forecast", storeId).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"horizonDays\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyDays").value(14))
                .andExpect(jsonPath("$.points.length()").value(5))
                .andExpect(jsonPath("$.points[0].date").value(LocalDate.now().plusDays(1).toString()));

        mvc.perform(post("/api/stores/{id}/ai/policy", storeId).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"정책자금 알려줘\",\"topK\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").isNotEmpty())
                .andExpect(jsonPath("$.sources.length()").value(2));

        mvc.perform(post("/api/stores/{id}/ai/promotion/poster", storeId).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"product\":\"떡볶이\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").isNotEmpty())
                .andExpect(jsonPath("$.hashtags.length()").value(3));

        JsonNode review = json(mvc.perform(post("/api/stores/{id}/reviews", storeId)
                        .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"네이버\",\"rating\":2,\"content\":\"배달이 늦었어요\"}"))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(post("/api/stores/{id}/ai/customer/reply", storeId).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewId\":" + review.get("id").asLong() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentiment").value("negative"))
                .andExpect(jsonPath("$.reviewId").value(review.get("id").asLong()));

        mvc.perform(get("/api/stores/{id}/ai/briefing", storeId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesForecast.status").value("ok"))
                .andExpect(jsonPath("$.policy.status").value("ok"))
                .andExpect(jsonPath("$.unansweredReviews").value(1));

        mvc.perform(get("/api/stores/{id}/ai/logs", storeId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].success").value(true));
    }

    @Test
    void policyStreamRelaysGrpcStreamAsSse() throws Exception {
        String token = signup();
        long storeId = createStore(token);

        MvcResult result = mvc.perform(post("/api/stores/{id}/ai/policy/stream", storeId)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"지원금\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000);

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event:delta").contains("event:sources").contains("event:done");
        assertThat(body.indexOf("event:done")).isGreaterThan(body.lastIndexOf("event:delta"));
    }

    @Test
    void forecastRequiresSalesHistory() throws Exception {
        String token = signup();
        long storeId = createStore(token);
        addSales(token, storeId, 3);

        mvc.perform(post("/api/stores/{id}/ai/management/forecast", storeId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_ENOUGH_SALES"));

        mvc.perform(get("/api/stores/{id}/ai/briefing", storeId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesForecast.status").value("failed"))
                .andExpect(jsonPath("$.salesForecast.errorCode").value("NOT_ENOUGH_SALES"))
                .andExpect(jsonPath("$.policy.status").value("ok"));
    }

    @Test
    void otherUsersCannotAccessStore() throws Exception {
        String owner = signup();
        long storeId = createStore(owner);
        String stranger = signup();

        mvc.perform(get("/api/stores/{id}", storeId).header(HttpHeaders.AUTHORIZATION, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        mvc.perform(post("/api/stores/{id}/ai/policy", storeId).header(HttpHeaders.AUTHORIZATION, stranger)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"정책\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void authenticationIsRequired() throws Exception {
        mvc.perform(get("/api/stores")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stores").header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void validationErrorsUseProblemDetail() throws Exception {
        String token = signup();
        long storeId = createStore(token);

        mvc.perform(post("/api/stores/{id}/ai/policy", storeId).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.question").exists());
        mvc.perform(post("/api/stores/{id}/ai/customer/reply", storeId).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REVIEW_REQUIRED"));
    }

    @Test
    void healthReportsEachAssistant() throws Exception {
        String token = signup();
        mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.aiAssistants.status").value("UP"))
                .andExpect(jsonPath("$.components.aiAssistants.details.management").value("SERVING"));
    }

    private String signup() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@test.com";
        JsonNode body = json(mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\",\"name\":\"사장님\"}"))
                .andExpect(status().isCreated()).andReturn());
        return "Bearer " + body.get("accessToken").asText();
    }

    private long createStore(String token) throws Exception {
        JsonNode body = json(mvc.perform(post("/api/stores").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"에리카 분식\",\"category\":\"분식\",\"region\":\"경기 안산시\"}"))
                .andExpect(status().isCreated()).andReturn());
        return body.get("id").asLong();
    }

    private void addSales(String token, long storeId, int days) throws Exception {
        StringBuilder items = new StringBuilder();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            if (!items.isEmpty()) {
                items.append(',');
            }
            items.append("{\"date\":\"").append(today.minusDays(i)).append("\",\"amount\":")
                    .append(300_000 + i * 1_000).append(",\"orderCount\":30}");
        }
        mvc.perform(put("/api/stores/{id}/sales", storeId).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[" + items + "]}"))
                .andExpect(status().isOk());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
