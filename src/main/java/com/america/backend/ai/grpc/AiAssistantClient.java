package com.america.backend.ai.grpc;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.america.ai.v1.CustomerAssistantGrpc;
import com.america.ai.v1.ForecastRequest;
import com.america.ai.v1.ForecastResult;
import com.america.ai.v1.ManagementAssistantGrpc;
import com.america.ai.v1.PolicyAnswer;
import com.america.ai.v1.PolicyAnswerChunk;
import com.america.ai.v1.PolicyAssistantGrpc;
import com.america.ai.v1.PolicyQuestion;
import com.america.ai.v1.PosterRequest;
import com.america.ai.v1.PosterResult;
import com.america.ai.v1.PromotionAssistantGrpc;
import com.america.ai.v1.ReviewReplyRequest;
import com.america.ai.v1.ReviewReplyResult;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 4개 AI 비서 gRPC 스텁을 감싸 마감 시간과 오류 변환을 일관되게 적용합니다. */
@Component
public class AiAssistantClient {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantClient.class);

    private final PolicyAssistantGrpc.PolicyAssistantBlockingStub policy;
    private final PolicyAssistantGrpc.PolicyAssistantStub policyAsync;
    private final PromotionAssistantGrpc.PromotionAssistantBlockingStub promotion;
    private final CustomerAssistantGrpc.CustomerAssistantBlockingStub customer;
    private final ManagementAssistantGrpc.ManagementAssistantBlockingStub management;
    private final Duration deadline;

    public AiAssistantClient(AiChannels channels, AiGrpcProperties properties) {
        this.policy = PolicyAssistantGrpc.newBlockingStub(channels.get(AssistantType.POLICY));
        this.policyAsync = PolicyAssistantGrpc.newStub(channels.get(AssistantType.POLICY));
        this.promotion = PromotionAssistantGrpc.newBlockingStub(channels.get(AssistantType.PROMOTION));
        this.customer = CustomerAssistantGrpc.newBlockingStub(channels.get(AssistantType.CUSTOMER));
        this.management = ManagementAssistantGrpc.newBlockingStub(channels.get(AssistantType.MANAGEMENT));
        this.deadline = properties.deadline();
    }

    public PolicyAnswer askPolicy(PolicyQuestion request) {
        return call(AssistantType.POLICY, () -> policy.withDeadlineAfter(millis(), TimeUnit.MILLISECONDS).ask(request));
    }

    public void streamPolicy(PolicyQuestion request, StreamObserver<PolicyAnswerChunk> observer) {
        policyAsync.withDeadlineAfter(millis(), TimeUnit.MILLISECONDS).askStream(request, observer);
    }

    public PosterResult createPoster(PosterRequest request) {
        return call(AssistantType.PROMOTION,
                () -> promotion.withDeadlineAfter(millis(), TimeUnit.MILLISECONDS).createPoster(request));
    }

    public ReviewReplyResult draftReply(ReviewReplyRequest request) {
        return call(AssistantType.CUSTOMER,
                () -> customer.withDeadlineAfter(millis(), TimeUnit.MILLISECONDS).draftReply(request));
    }

    public ForecastResult forecastSales(ForecastRequest request) {
        return call(AssistantType.MANAGEMENT,
                () -> management.withDeadlineAfter(millis(), TimeUnit.MILLISECONDS).forecastSales(request));
    }

    public Duration deadline() {
        return deadline;
    }

    private long millis() {
        return deadline.toMillis();
    }

    private static <T> T call(AssistantType type, Supplier<T> call) {
        try {
            return call.get();
        } catch (StatusRuntimeException e) {
            log.warn("{} AI 비서 gRPC 호출 실패: {} {}", type.label(), e.getStatus().getCode(), e.getStatus().getDescription());
            throw GrpcErrors.toApiException(type, e);
        }
    }
}
