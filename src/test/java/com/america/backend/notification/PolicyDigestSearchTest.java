package com.america.backend.notification;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import com.america.backend.ai.AiDtos.PolicyAnswerResponse;
import com.america.backend.ai.AiDtos.SourceDto;
import com.america.backend.common.ApiException;
import org.junit.jupiter.api.Test;

class PolicyDigestSearchTest {
    @Test void sourceFilteringPreservesModelAnswerAndOnlyUsableLinks() {
        var result = PolicyDigestSearch.validate(new PolicyAnswerResponse("  오늘 확인된 정책 없음  ", Arrays.asList(
                null, new SourceDto("정상 출처", "https://www.mss.go.kr/notice", "본문", 1),
                new SourceDto("잘못된 출처", "not a URL", "", 1),
                new SourceDto("잘못된 출처", "https://user:secret@example.com/", "", 1))));
        assertThat(result.answer()).isEqualTo("오늘 확인된 정책 없음");
        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources().getFirst().url()).isEqualTo("https://www.mss.go.kr/notice");
    }
    @Test void emptyModelResultIsNotAClaimThatNoPoliciesExist() {
        for (var result : Arrays.asList(null, new PolicyAnswerResponse(null, List.of()), new PolicyAnswerResponse(" ", List.of())))
            assertThatThrownBy(() -> PolicyDigestSearch.validate(result)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.getCode()).isEqualTo("AI_EMPTY_RESULT"));
    }
    @Test void refreshPromptUsesTodaysDateAndDoesNotTreatUnverifiedDatesAsEligibility() {
        String prompt = PolicyDigestSearch.question(LocalDate.of(2026, 11, 17));
        assertThat(prompt).contains("2026-11-17", "이전에 안내한 정책", "확인 필요", "공식 공고 URL");
        assertThat(prompt.length()).isLessThanOrEqualTo(1000);
    }
}
