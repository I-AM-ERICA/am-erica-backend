package com.america.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import com.america.backend.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:naver;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=test-secret-test-secret-test-secret-1234",
        "ai.grpc.internal-key=test-internal-key",
        "app.naver.client-id=test-client", "app.naver.client-secret=test-secret",
        "app.naver.redirect-uri=http://localhost:7001/api/auth/callback/naver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NaverAuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @MockitoBean NaverClient naver;

    private static final String REQUEST = """
            {"code":"one-use-code","state":"random-state","redirectUri":"http://localhost:7001/api/auth/callback/naver"}
            """;

    @Test
    void firstLoginCreatesAccountAndJwtWorksForMeAndProtectedApis() throws Exception {
        String providerId = UUID.randomUUID().toString();
        when(naver.authenticate(anyString(), anyString())).thenReturn(new NaverClient.Profile(providerId, "naver-" + providerId + "@example.com", "사장님", null));
        JsonNode token = login();
        assertThat(token.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(token.get("expiresIn").asLong()).isPositive();
        String bearer = "Bearer " + token.get("accessToken").asText();
        mvc.perform(get("/api/users/me").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("사장님"));
        mvc.perform(post("/api/stores").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"네이버 가게\",\"category\":\"카페\",\"region\":\"서울\"}"))
                .andExpect(status().isCreated());
        assertThat(users.findByNaverId(providerId).orElseThrow().getPasswordHash()).isNull();
    }

    @Test
    void returningUserIsIdentifiedByProviderIdEvenIfEmailChanges() throws Exception {
        String providerId = UUID.randomUUID().toString();
        when(naver.authenticate(anyString(), anyString())).thenReturn(new NaverClient.Profile(providerId, "old-" + providerId + "@example.com", "처음", null));
        login();
        long userId = users.findByNaverId(providerId).orElseThrow().getId();
        when(naver.authenticate(anyString(), anyString())).thenReturn(new NaverClient.Profile(providerId, "new-" + providerId + "@example.com", "변경", null));
        JsonNode token = login();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token.get("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(userId));
    }

    @Test
    void optionalProfileFieldsAreNotRequiredForLogin() throws Exception {
        String providerId = UUID.randomUUID().toString();
        when(naver.authenticate(anyString(), anyString())).thenReturn(new NaverClient.Profile(providerId, null, null, null));
        JsonNode token = login();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token.get("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(""))
                .andExpect(jsonPath("$.name").value("사장님"));
    }

    @Test
    void matchingEmailDoesNotLinkToAnExistingPasswordAccount() throws Exception {
        String email = "existing-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", email, "password", "password123!", "name", "기존"))))
                .andExpect(status().isCreated());
        String providerId = UUID.randomUUID().toString();
        when(naver.authenticate(anyString(), anyString())).thenReturn(new NaverClient.Profile(providerId, email, "네이버", null));
        mvc.perform(post("/api/auth/naver").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAVER_EMAIL_CONFLICT"));
        assertThat(users.findByNaverId(providerId)).isEmpty();
        assertThat(users.findByEmail(email).orElseThrow().getNaverId()).isNull();
    }

    @Test
    void naverAccountCannotUsePasswordLogin() throws Exception {
        String providerId = UUID.randomUUID().toString();
        String email = "social-" + providerId + "@example.com";
        when(naver.authenticate(anyString(), anyString())).thenReturn(new NaverClient.Profile(providerId, email, "사장", null));
        login();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", email, "password", "anything"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unregisteredRedirectAndEmptyStateAreRejectedBeforeCallingNaver() throws Exception {
        mvc.perform(post("/api/auth/naver").contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST.replace("http://localhost:7001/api/auth/callback/naver", "https://evil.example/callback")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NAVER_INVALID_REDIRECT"));
        mvc.perform(post("/api/auth/naver").contentType(MediaType.APPLICATION_JSON).content(REQUEST.replace("random-state", "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(naver);
    }

    @Test
    void missingCredentialsFailClosedWithoutCallingNaver() {
        var service = new NaverAuthService(new NaverProperties("", "", "http://localhost:7001/api/auth/callback/naver"), naver, users, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.login(new AuthDtos.NaverLoginRequest("code", "state", "http://localhost:7001/api/auth/callback/naver")))
                .isInstanceOfSatisfying(com.america.backend.common.ApiException.class, error -> assertThat(error.getCode()).isEqualTo("NAVER_NOT_CONFIGURED"));
        verifyNoInteractions(naver);
    }

    private JsonNode login() throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/naver").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
}
