package com.america.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.IOException;
import com.america.backend.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

class NaverClientTest {
    MockRestServiceServer server;
    NaverClient client;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new NaverClient(builder, new NaverProperties("test-client", "test-secret", "http://localhost:7001/api/auth/callback/naver"));
    }

    @Test
    void exchangesCodeUsingPostBodyAndReadsProfileWithBearerToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", "test-client");
        form.add("client_secret", "test-secret");
        form.add("code", "one-use-code");
        form.add("state", "random-state");
        form.add("redirect_uri", "http://localhost:7001/api/auth/callback/naver");
        server.expect(requestTo(NaverClient.TOKEN_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(form))
                .andRespond(withSuccess("{\"access_token\":\"provider-token\",\"refresh_token\":\"ignored\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(NaverClient.PROFILE_URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer provider-token"))
                .andRespond(withSuccess("{\"resultcode\":\"00\",\"response\":{\"id\":\"provider-id\",\"name\":\"사장님\",\"email\":\"owner@example.com\"}}", MediaType.APPLICATION_JSON));
        assertThat(client.authenticate("one-use-code", "random-state").id()).isEqualTo("provider-id");
        server.verify();
    }

    @Test
    void providerErrorInSuccessfulHttpResponseIsRejectedWithoutLeakingDetails() {
        server.expect(requestTo(NaverClient.TOKEN_URL)).andRespond(withSuccess("{\"error\":\"invalid_grant\",\"error_description\":\"SECRET DETAIL\"}", MediaType.APPLICATION_JSON));
        assertFailure("NAVER_AUTH_FAILED");
    }

    @Test
    void providerHttpErrorIsSanitized() {
        server.expect(requestTo(NaverClient.TOKEN_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("SECRET DETAIL").contentType(MediaType.TEXT_PLAIN));
        assertFailure("NAVER_UPSTREAM_ERROR");
    }

    @Test
    void providerNetworkFailureIsRetryable() {
        server.expect(requestTo(NaverClient.TOKEN_URL)).andRespond(withException(new IOException("SECRET DETAIL")));
        assertFailure("NAVER_UNAVAILABLE");
    }

    @Test
    void malformedJsonIsRejected() {
        server.expect(requestTo(NaverClient.TOKEN_URL)).andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));
        assertFailure("NAVER_UPSTREAM_ERROR");
    }

    @Test
    void missingProviderIdCannotCreateAnAccount() {
        server.expect(requestTo(NaverClient.TOKEN_URL)).andRespond(withSuccess("{\"access_token\":\"token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(NaverClient.PROFILE_URL)).andRespond(withSuccess("{\"resultcode\":\"00\",\"response\":{\"email\":\"owner@example.com\"}}", MediaType.APPLICATION_JSON));
        assertFailure("NAVER_INVALID_PROFILE");
    }

    private void assertFailure(String code) {
        assertThatThrownBy(() -> client.authenticate("one-use-code", "random-state"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(code);
                    assertThat(error.getMessage()).doesNotContain("SECRET", "test-secret", "provider-token");
                });
        server.verify();
    }
}
