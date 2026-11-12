package com.america.backend.ai;

import java.util.List;

import com.america.backend.ai.AiDtos.AiLogResponse;
import com.america.backend.ai.AiDtos.BriefingResponse;
import com.america.backend.ai.AiDtos.ForecastCreateRequest;
import com.america.backend.ai.AiDtos.ForecastResponse;
import com.america.backend.ai.AiDtos.PolicyAnswerResponse;
import com.america.backend.ai.AiDtos.PolicyAskRequest;
import com.america.backend.ai.AiDtos.PosterCreateRequest;
import com.america.backend.ai.AiDtos.PosterResponse;
import com.america.backend.ai.AiDtos.ReplyDraftRequest;
import com.america.backend.ai.AiDtos.ReplyDraftResponse;
import com.america.backend.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "4. AI 비서", description = "AI Orchestrator · gRPC로 정책 · 홍보 · 고객 응대 · 경영 비서 호출")
@RestController
@RequestMapping("/api/stores/{storeId}/ai")
public class AiController {

    private final AiOrchestrator orchestrator;

    public AiController(AiOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Operation(summary = "정책 비서 질문 (Search-R1)")
    @PostMapping("/policy")
    public PolicyAnswerResponse askPolicy(@AuthenticationPrincipal Jwt jwt, @PathVariable Long storeId,
                                          @Valid @RequestBody PolicyAskRequest request) {
        return orchestrator.askPolicy(CurrentUser.id(jwt), storeId, request);
    }

    @Operation(summary = "정책 비서 질문 · SSE 스트리밍",
            description = "이벤트: delta {text} → sources [..] → done {}. 실패 시 error {code, message}. "
                    + "Authorization 헤더가 필요하므로 브라우저에서는 EventSource 대신 fetch 스트림을 사용하세요.")
    @PostMapping(value = "/policy/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPolicy(@AuthenticationPrincipal Jwt jwt, @PathVariable Long storeId,
                                   @Valid @RequestBody PolicyAskRequest request) {
        return orchestrator.streamPolicy(CurrentUser.id(jwt), storeId, request);
    }

    @Operation(summary = "홍보 비서 · 포스터 문구/이미지 생성 (PosterLlama)")
    @PostMapping("/promotion/poster")
    public PosterResponse createPoster(@AuthenticationPrincipal Jwt jwt, @PathVariable Long storeId,
                                       @Valid @RequestBody PosterCreateRequest request) {
        return orchestrator.createPoster(CurrentUser.id(jwt), storeId, request);
    }

    @Operation(summary = "고객 응대 비서 · 리뷰 답글 초안 (SCRABLE)",
            description = "reviewId(저장된 리뷰) 또는 reviewText 중 하나를 보내세요. 초안은 저장되지 않습니다.")
    @PostMapping("/customer/reply")
    public ReplyDraftResponse draftReply(@AuthenticationPrincipal Jwt jwt, @PathVariable Long storeId,
                                         @Valid @RequestBody ReplyDraftRequest request) {
        return orchestrator.draftReply(CurrentUser.id(jwt), storeId, request);
    }

    @Operation(summary = "경영 비서 · 매출 예측 (Chronos)",
            description = "최근 90일 매출 중 최소 7일이 필요합니다. horizonDays 기본값 7, 최대 30.")
    @PostMapping("/management/forecast")
    public ForecastResponse forecast(@AuthenticationPrincipal Jwt jwt, @PathVariable Long storeId,
                                     @Valid @RequestBody(required = false) ForecastCreateRequest request) {
        return orchestrator.forecast(CurrentUser.id(jwt), storeId, request == null ? null : request.horizonDays());
    }

    @Operation(summary = "통합 브리핑", description = "매출 예측과 정책 추천을 병렬 호출해 하나로 합칩니다. 일부 실패 시 해당 섹션만 failed입니다.")
    @GetMapping("/briefing")
    public BriefingResponse briefing(@AuthenticationPrincipal Jwt jwt, @PathVariable Long storeId) {
        return orchestrator.briefing(CurrentUser.id(jwt), storeId);
    }

    @Operation(summary = "AI 호출 이력")
    @GetMapping("/logs")
    public List<AiLogResponse> history(@AuthenticationPrincipal Jwt jwt, @PathVariable Long storeId,
                                       @RequestParam(defaultValue = "20") int limit) {
        return orchestrator.history(CurrentUser.id(jwt), storeId, limit);
    }
}
