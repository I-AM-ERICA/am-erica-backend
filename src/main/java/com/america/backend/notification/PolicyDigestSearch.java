package com.america.backend.notification;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import com.america.backend.ai.AiDtos.PolicyAnswerResponse;
import com.america.backend.ai.AiDtos.PolicyAskRequest;
import com.america.backend.ai.AiDtos.SourceDto;
import com.america.backend.ai.AiOrchestrator;
import com.america.backend.common.ApiException;
import com.america.backend.store.Store;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Uses the trained Python model's Ask contract (top_k <= 5), never a canned email answer. */
@Service
public class PolicyDigestSearch {
    private final AiOrchestrator ai;
    public PolicyDigestSearch(AiOrchestrator ai) { this.ai = ai; }

    public PolicyAnswerResponse search(Long userId, Store store, LocalDate today) {
        // Do not reuse yesterday's free-form answer as evidence of today's eligibility.
        return validate(ai.askPolicy(userId, store.getId(), new PolicyAskRequest(question(today), 5)));
    }

    public static String question(LocalDate today) {
        return "오늘은 " + today + " (한국 시간)입니다. 소상공인 지원사업의 최신 공식 공고를 공식 사이트에서 검색해 주세요. "
                + "매장 업종과 지역에 맞는 정책 및 전국 대상 정책의 지원 대상, 지원 내용, 신청 기간, 신청 방법을 정리해 주세요. "
                + "지역이 미설정이면 전국 정책을 찾아주세요. 이전에 안내한 정책도 신청 기간이 남아 있으면 포함하세요. "
                + "오늘 신청 가능한지 공식 공고의 날짜를 근거로 구분하고, 마감 또는 접수 전인 정책은 제외하세요. "
                + "기간이나 자격을 확인할 수 없는 경우 확인 필요라고 명시하고 신청 가능하다고 단정하지 마세요. "
                + "신청 가능한 정책을 찾지 못했다면 그 사실을 알려주고, 확인한 공식 공고 URL을 함께 제공하세요.";
    }

    static PolicyAnswerResponse validate(PolicyAnswerResponse result) {
        if (result == null || result.answer() == null || result.answer().isBlank())
            throw failure("AI_EMPTY_RESULT", "정책 검색 결과를 받지 못해 이메일을 보내지 않았습니다.");
        if (result.answer().contains("[MOCK]"))
            throw failure("AI_MODEL_REQUIRED", "실제 정책 모델 연결이 필요합니다. 예시 응답은 이메일로 보내지 않습니다.");
        List<SourceDto> sources = result.sources() == null ? List.of() : result.sources().stream()
                .filter(source -> source != null && validSourceUrl(source.url()))
                .toList();
        if (sources.isEmpty())
            throw failure("AI_NO_SOURCES", "검색 출처를 확인하지 못해 이메일을 보내지 않았습니다.");
        return new PolicyAnswerResponse(result.answer().strip(), sources);
    }

    private static boolean validSourceUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI url = URI.create(value);
            return ("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                    && url.getHost() != null && url.getUserInfo() == null;
        } catch (IllegalArgumentException error) { return false; }
    }

    private static ApiException failure(String code, String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, code, message);
    }
}
