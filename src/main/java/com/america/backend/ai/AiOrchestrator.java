package com.america.backend.ai;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import com.america.ai.v1.ForecastRequest;
import com.america.ai.v1.ForecastResult;
import com.america.ai.v1.PolicyAnswerChunk;
import com.america.ai.v1.PolicyQuestion;
import com.america.ai.v1.PosterRequest;
import com.america.ai.v1.ReviewReplyRequest;
import com.america.ai.v1.SalesPoint;
import com.america.ai.v1.StoreContext;
import com.america.backend.ai.AiDtos.AiLogResponse;
import com.america.backend.ai.AiDtos.BriefingResponse;
import com.america.backend.ai.AiDtos.ForecastPointDto;
import com.america.backend.ai.AiDtos.ForecastResponse;
import com.america.backend.ai.AiDtos.PolicyAnswerResponse;
import com.america.backend.ai.AiDtos.PolicyAskRequest;
import com.america.backend.ai.AiDtos.PosterCreateRequest;
import com.america.backend.ai.AiDtos.PosterResponse;
import com.america.backend.ai.AiDtos.ReplyDraftRequest;
import com.america.backend.ai.AiDtos.ReplyDraftResponse;
import com.america.backend.ai.AiDtos.Section;
import com.america.backend.ai.AiDtos.SourceDto;
import com.america.backend.ai.grpc.AiAssistantClient;
import com.america.backend.ai.grpc.AssistantType;
import com.america.backend.ai.grpc.GrpcErrors;
import com.america.backend.common.ApiException;
import com.america.backend.data.DataService;
import com.america.backend.data.Review;
import com.america.backend.data.Sale;
import com.america.backend.store.Store;
import com.america.backend.store.StoreService;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 매장 권한 확인 → 매장/데이터 조회 → gRPC 비서 호출 → 결과 변환 및 이력 저장을 담당합니다.
 * gRPC 호출 중에는 DB 트랜잭션을 열어 두지 않습니다.
 */
@Service
public class AiOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AiOrchestrator.class);

    static final int HISTORY_DAYS = 90;
    static final int MIN_HISTORY_DAYS = 7;
    private static final int DEFAULT_HORIZON_DAYS = 7;
    private static final int DEFAULT_TOP_K = 3;
    private static final String BRIEFING_QUESTION = "우리 매장이 지금 신청할 수 있는 소상공인 지원 정책을 알려주세요.";

    private final StoreService storeService;
    private final DataService dataService;
    private final AiAssistantClient client;
    private final AiLogRepository logs;
    private final ExecutorService aiExecutor;

    public AiOrchestrator(StoreService storeService, DataService dataService, AiAssistantClient client,
                          AiLogRepository logs, ExecutorService aiExecutor) {
        this.storeService = storeService;
        this.dataService = dataService;
        this.client = client;
        this.logs = logs;
        this.aiExecutor = aiExecutor;
    }

    // ---------- 정책 ----------

    public PolicyAnswerResponse askPolicy(Long userId, Long storeId, PolicyAskRequest request) {
        Store store = storeService.requireOwned(userId, storeId);
        PolicyQuestion question = policyQuestion(store, request.question(), request.topK());
        return logged(userId, storeId, AssistantType.POLICY, "ask",
                () -> PolicyAnswerResponse.from(client.askPolicy(question)));
    }

    /**
     * 정책 답변을 SSE로 중계합니다. 이벤트: {@code delta}(text), {@code sources}, {@code done}, {@code error}(code, message).
     * 클라이언트 연결이 끊기면 gRPC 호출도 취소합니다.
     */
    public SseEmitter streamPolicy(Long userId, Long storeId, PolicyAskRequest request) {
        Store store = storeService.requireOwned(userId, storeId);
        PolicyQuestion question = policyQuestion(store, request.question(), request.topK());
        SseEmitter emitter = new SseEmitter(client.deadline().plusSeconds(5).toMillis());
        long started = System.nanoTime();

        client.streamPolicy(question, new ClientResponseObserver<PolicyQuestion, PolicyAnswerChunk>() {
            private ClientCallStreamObserver<PolicyQuestion> call;

            @Override
            public void beforeStart(ClientCallStreamObserver<PolicyQuestion> requestStream) {
                this.call = requestStream;
                Runnable cancel = () -> requestStream.cancel("SSE 연결 종료", null);
                emitter.onCompletion(cancel);
                emitter.onTimeout(cancel);
                emitter.onError(error -> cancel.run());
            }

            @Override
            public void onNext(PolicyAnswerChunk chunk) {
                try {
                    if (!chunk.getDelta().isEmpty()) {
                        emitter.send(SseEmitter.event().name("delta").data(Map.of("text", chunk.getDelta())));
                    }
                    if (chunk.getSourcesCount() > 0) {
                        emitter.send(SseEmitter.event().name("sources")
                                .data(chunk.getSourcesList().stream().map(SourceDto::from).toList()));
                    }
                } catch (IOException | IllegalStateException e) {
                    call.cancel("SSE 전송 실패", e);
                }
            }

            @Override
            public void onError(Throwable error) {
                if (Status.fromThrowable(error).getCode() == Status.Code.CANCELLED) {
                    record(userId, storeId, AssistantType.POLICY, "stream", false, "CLIENT_CANCELLED", started);
                    return;
                }
                ApiException mapped = GrpcErrors.toApiException(AssistantType.POLICY, error);
                log.warn("정책 AI 비서 스트림 실패: {}", Status.fromThrowable(error));
                record(userId, storeId, AssistantType.POLICY, "stream", false, mapped.getCode(), started);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("code", mapped.getCode(), "message", mapped.getMessage())));
                } catch (IOException | IllegalStateException ignored) {
                    // 클라이언트가 이미 끊긴 경우
                }
                emitter.complete();
            }

            @Override
            public void onCompleted() {
                record(userId, storeId, AssistantType.POLICY, "stream", true, null, started);
                try {
                    emitter.send(SseEmitter.event().name("done").data(Map.of()));
                } catch (IOException | IllegalStateException ignored) {
                    // 클라이언트가 이미 끊긴 경우
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    // ---------- 홍보 ----------

    public PosterResponse createPoster(Long userId, Long storeId, PosterCreateRequest request) {
        Store store = storeService.requireOwned(userId, storeId);
        PosterRequest proto = PosterRequest.newBuilder()
                .setStore(storeContext(store))
                .setProduct(request.product().trim())
                .setMessage(nullToEmpty(request.message()))
                .setTone(nullToEmpty(request.tone()))
                .setChannel(nullToEmpty(request.channel()))
                .setGenerateImage(request.generateImage())
                .build();
        return logged(userId, storeId, AssistantType.PROMOTION, "poster",
                () -> PosterResponse.from(client.createPoster(proto)));
    }

    // ---------- 고객 응대 ----------

    public ReplyDraftResponse draftReply(Long userId, Long storeId, ReplyDraftRequest request) {
        Store store = storeService.requireOwned(userId, storeId);
        String text;
        int rating;
        if (request.reviewId() != null) {
            Review review = dataService.requireReview(storeId, request.reviewId());
            text = review.getContent();
            rating = review.getRating();
        } else if (request.reviewText() != null && !request.reviewText().isBlank()) {
            text = request.reviewText().trim();
            rating = request.rating() == null ? 0 : request.rating();
        } else {
            throw ApiException.badRequest("REVIEW_REQUIRED", "reviewId 또는 reviewText가 필요합니다.");
        }
        ReviewReplyRequest proto = ReviewReplyRequest.newBuilder()
                .setStore(storeContext(store))
                .setReviewText(text)
                .setRating(rating)
                .setTone(nullToEmpty(request.tone()))
                .build();
        return logged(userId, storeId, AssistantType.CUSTOMER, "reply",
                () -> ReplyDraftResponse.from(request.reviewId(), client.draftReply(proto)));
    }

    // ---------- 경영 ----------

    public ForecastResponse forecast(Long userId, Long storeId, Integer horizonDays) {
        Store store = storeService.requireOwned(userId, storeId);
        ForecastRequest proto = forecastRequest(store, horizonDays == null ? DEFAULT_HORIZON_DAYS : horizonDays);
        return logged(userId, storeId, AssistantType.MANAGEMENT, "forecast",
                () -> toForecast(proto.getHistoryCount(), client.forecastSales(proto)));
    }

    // ---------- 통합 ----------

    /** 경영(매출 예측)과 정책 비서를 병렬 호출합니다. 한 비서가 실패해도 나머지 결과는 반환합니다. */
    public BriefingResponse briefing(Long userId, Long storeId) {
        Store store = storeService.requireOwned(userId, storeId);
        PolicyQuestion question = policyQuestion(store, BRIEFING_QUESTION, DEFAULT_TOP_K);

        CompletableFuture<Section<ForecastResponse>> forecast;
        try {
            ForecastRequest proto = forecastRequest(store, DEFAULT_HORIZON_DAYS);
            forecast = CompletableFuture.supplyAsync(() -> section(() -> logged(
                    userId, storeId, AssistantType.MANAGEMENT, "briefing",
                    () -> toForecast(proto.getHistoryCount(), client.forecastSales(proto)))), aiExecutor);
        } catch (ApiException e) {
            forecast = CompletableFuture.completedFuture(Section.failed(e));
        }
        CompletableFuture<Section<PolicyAnswerResponse>> policy = CompletableFuture.supplyAsync(
                () -> section(() -> logged(userId, storeId, AssistantType.POLICY, "briefing",
                        () -> PolicyAnswerResponse.from(client.askPolicy(question)))), aiExecutor);

        long unanswered = dataService.unansweredReviewCount(storeId);
        return new BriefingResponse(storeId, forecast.join(), policy.join(), unanswered);
    }

    public List<AiLogResponse> history(Long userId, Long storeId, int limit) {
        storeService.requireOwned(userId, storeId);
        return logs.findByStoreIdOrderByIdDesc(storeId, PageRequest.of(0, Math.max(1, Math.min(100, limit))))
                .stream().map(AiLogResponse::from).toList();
    }

    // ---------- 내부 ----------

    private PolicyQuestion policyQuestion(Store store, String question, Integer topK) {
        return PolicyQuestion.newBuilder()
                .setStore(storeContext(store))
                .setQuestion(question.trim())
                .setTopK(topK == null ? DEFAULT_TOP_K : topK)
                .build();
    }

    private ForecastRequest forecastRequest(Store store, int horizonDays) {
        List<Sale> history = dataService.recentSales(store.getId(), HISTORY_DAYS);
        if (history.size() < MIN_HISTORY_DAYS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_ENOUGH_SALES",
                    "매출 예측에는 최근 " + HISTORY_DAYS + "일 중 최소 " + MIN_HISTORY_DAYS + "일의 매출 데이터가 필요합니다.");
        }
        ForecastRequest.Builder builder = ForecastRequest.newBuilder()
                .setStore(storeContext(store))
                .setHorizonDays(horizonDays);
        history.forEach(sale -> builder.addHistory(SalesPoint.newBuilder()
                .setDate(sale.getSaleDate().toString())
                .setAmount(sale.getAmount())
                .setOrderCount(sale.getOrderCount())));
        return builder.build();
    }

    private static ForecastResponse toForecast(int historyDays, ForecastResult result) {
        List<ForecastPointDto> points = result.getPointsList().stream().map(ForecastPointDto::from).toList();
        return new ForecastResponse(historyDays, points, result.getSummary());
    }

    private static StoreContext storeContext(Store store) {
        return StoreContext.newBuilder()
                .setStoreId(store.getId().toString())
                .setName(store.getName())
                .setCategory(store.getCategory())
                .setRegion(store.getRegion())
                .setAddress(nullToEmpty(store.getAddress()))
                .build();
    }

    private <T> T logged(Long userId, Long storeId, AssistantType type, String operation, Supplier<T> call) {
        long started = System.nanoTime();
        try {
            T result = call.get();
            record(userId, storeId, type, operation, true, null, started);
            return result;
        } catch (ApiException e) {
            record(userId, storeId, type, operation, false, e.getCode(), started);
            throw e;
        }
    }

    private void record(Long userId, Long storeId, AssistantType type, String operation, boolean success,
                        String errorCode, long startedNanos) {
        long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
        try {
            logs.save(new AiLog(userId, storeId, type, operation, success, errorCode, latencyMs));
        } catch (RuntimeException e) {
            log.warn("AI 호출 이력 저장 실패", e);
        }
    }

    private static <T> Section<T> section(Supplier<T> call) {
        try {
            return Section.ok(call.get());
        } catch (ApiException e) {
            return Section.failed(e);
        } catch (RuntimeException e) {
            log.error("브리핑 처리 실패", e);
            return Section.failed(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "처리 중 오류가 발생했습니다."));
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
