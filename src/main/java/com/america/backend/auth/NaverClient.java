package com.america.backend.auth;

import com.america.backend.common.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class NaverClient {
    static final String TOKEN_URL = "https://nid.naver.com/oauth2.0/token";
    static final String PROFILE_URL = "https://openapi.naver.com/v1/nid/me";
    private final RestClient client;
    private final NaverProperties properties;

    public NaverClient(@Qualifier("naverRestClientBuilder") RestClient.Builder builder, NaverProperties properties) {
        this.client = builder.build();
        this.properties = properties;
    }

    public Profile authenticate(String code, String state) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", code);
        form.add("state", state);
        form.add("redirect_uri", properties.redirectUri());
        try {
            Token token = client.post().uri(TOKEN_URL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().onStatus(status -> status.is4xxClientError(), (request, response) -> {
                        throw rejected();
                    }).body(Token.class);
            if (token == null || token.error() != null || token.accessToken() == null || token.accessToken().isBlank()) {
                throw rejected();
            }
            ProfileResponse result = client.get().uri(PROFILE_URL)
                    .headers(headers -> headers.setBearerAuth(token.accessToken()))
                    .retrieve().onStatus(status -> status.is4xxClientError(), (request, response) -> {
                        throw rejected();
                    }).body(ProfileResponse.class);
            if (result == null || !"00".equals(result.resultcode()) || result.response() == null
                    || result.response().id() == null || result.response().id().isBlank()
                    || result.response().id().length() > 255) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "NAVER_INVALID_PROFILE", "네이버 회원 정보를 확인하지 못했습니다.");
            }
            return result.response();
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NAVER_UNAVAILABLE", "네이버 로그인 서버에 연결하지 못했습니다. 다시 시도해 주세요.");
        } catch (RestClientException e) {
            // Never expose upstream bodies, tokens, credentials or request details.
            throw new ApiException(HttpStatus.BAD_GATEWAY, "NAVER_UPSTREAM_ERROR", "네이버 로그인 처리 중 오류가 발생했습니다. 다시 시도해 주세요.");
        }
    }

    private static ApiException rejected() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "NAVER_AUTH_FAILED", "네이버 인증이 만료되었거나 거부되었습니다. 다시 로그인해 주세요.");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Token(@JsonProperty("access_token") String accessToken, String error) {
        @Override public String toString() { return "NaverToken[REDACTED]"; }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProfileResponse(String resultcode, Profile response) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(String id, String email, String name, String nickname) {}
}
