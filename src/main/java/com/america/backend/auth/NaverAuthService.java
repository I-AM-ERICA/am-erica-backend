package com.america.backend.auth;

import java.util.Locale;
import com.america.backend.auth.AuthDtos.NaverLoginRequest;
import com.america.backend.auth.AuthDtos.TokenResponse;
import com.america.backend.common.ApiException;
import com.america.backend.user.UserAccount;
import com.america.backend.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NaverAuthService {
    private final NaverProperties properties;
    private final NaverClient naver;
    private final UserRepository users;
    private final TokenService tokens;
    private final TransactionTemplate transactions;

    public NaverAuthService(NaverProperties properties, NaverClient naver, UserRepository users,
                            TokenService tokens, TransactionTemplate transactions) {
        this.properties = properties;
        this.naver = naver;
        this.users = users;
        this.tokens = tokens;
        this.transactions = transactions;
    }

    public TokenResponse login(NaverLoginRequest request) {
        if (!properties.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NAVER_NOT_CONFIGURED", "네이버 로그인 연결을 준비 중입니다.");
        }
        if (!properties.redirectUri().equals(request.redirectUri())) {
            throw ApiException.badRequest("NAVER_INVALID_REDIRECT", "등록되지 않은 로그인 콜백 주소입니다.");
        }
        // The SPA verifies its one-use state before submitting. Code exchange/profile lookup
        // happen outside the DB transaction; only provider-verified IDs identify accounts.
        NaverClient.Profile profile = naver.authenticate(request.code(), request.state());
        try {
            return transactions.execute(status -> tokens.issue(findOrCreate(profile)));
        } catch (DataIntegrityViolationException e) {
            // A concurrent first login may have created this exact provider account.
            return transactions.execute(status -> users.findByNaverId(profile.id()).map(tokens::issue)
                    .orElseThrow(NaverAuthService::emailConflict));
        }
    }

    private UserAccount findOrCreate(NaverClient.Profile profile) {
        return users.findByNaverId(profile.id()).orElseGet(() -> {
            String email = profile.email() == null || profile.email().isBlank() ? null
                    : profile.email().trim().toLowerCase(Locale.ROOT);
            if (email != null && email.length() > 255) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "NAVER_INVALID_PROFILE", "네이버 회원 정보를 확인하지 못했습니다.");
            }
            if (email != null && users.existsByEmail(email)) throw emailConflict();
            String name = profile.name();
            if (name == null || name.isBlank()) name = profile.nickname();
            if (name == null || name.isBlank()) name = "사장님";
            name = name.trim();
            if (name.length() > 50) name = name.substring(0, 50);
            return users.saveAndFlush(UserAccount.fromNaver(profile.id(), email, name));
        });
    }

    private static ApiException emailConflict() {
        return new ApiException(HttpStatus.CONFLICT, "NAVER_EMAIL_CONFLICT", "같은 이메일로 가입된 계정이 있습니다. 계정 연결은 별도 확인이 필요합니다.");
    }
}
