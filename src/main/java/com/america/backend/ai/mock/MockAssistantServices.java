package com.america.backend.ai.mock;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.america.ai.v1.CustomerAssistantGrpc;
import com.america.ai.v1.ForecastPoint;
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
import com.america.ai.v1.SalesPoint;
import com.america.ai.v1.Source;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * 로컬 개발용 고정 응답 비서입니다. 실제 모델(Search-R1, PosterLlama, SCRABLE, Chronos)을 대신하지 않으며
 * 모든 텍스트 결과에 [MOCK] 표시를 붙입니다.
 */
final class MockAssistantServices {

    private MockAssistantServices() {
    }

    static final class Policy extends PolicyAssistantGrpc.PolicyAssistantImplBase {

        @Override
        public void ask(PolicyQuestion request, StreamObserver<PolicyAnswer> responseObserver) {
            responseObserver.onNext(PolicyAnswer.newBuilder()
                    .setAnswer(answer(request))
                    .addAllSources(sources(request))
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void askStream(PolicyQuestion request, StreamObserver<PolicyAnswerChunk> responseObserver) {
            for (String token : answer(request).split("(?<= )")) {
                responseObserver.onNext(PolicyAnswerChunk.newBuilder().setDelta(token).build());
            }
            responseObserver.onNext(PolicyAnswerChunk.newBuilder().addAllSources(sources(request)).setDone(true).build());
            responseObserver.onCompleted();
        }

        private static String answer(PolicyQuestion request) {
            return "[MOCK] %s %s 업종 기준으로 \"%s\"에 대한 참고 답변입니다. 소상공인 정책자금과 지역 지원 사업을 먼저 확인해 보세요."
                    .formatted(request.getStore().getRegion(), request.getStore().getCategory(), request.getQuestion());
        }

        private static List<Source> sources(PolicyQuestion request) {
            int topK = request.getTopK() <= 0 ? 3 : request.getTopK();
            return List.of(
                    Source.newBuilder().setTitle("[MOCK] 소상공인 정책자금").setUrl("https://www.sbiz24.kr")
                            .setSnippet("운영자금·시설자금 융자 지원").setScore(0.92).build(),
                    Source.newBuilder().setTitle("[MOCK] " + request.getStore().getRegion() + " 소상공인 지원사업")
                            .setUrl("https://www.sbiz.or.kr").setSnippet("지역별 경영 개선 지원").setScore(0.81).build(),
                    Source.newBuilder().setTitle("[MOCK] 온라인 판로 지원").setUrl("https://www.sbiz.or.kr")
                            .setSnippet("온라인 입점·홍보 지원").setScore(0.74).build()
            ).subList(0, Math.min(3, topK));
        }
    }

    static final class Promotion extends PromotionAssistantGrpc.PromotionAssistantImplBase {

        @Override
        public void createPoster(PosterRequest request, StreamObserver<PosterResult> responseObserver) {
            String storeName = request.getStore().getName();
            responseObserver.onNext(PosterResult.newBuilder()
                    .setHeadline("[MOCK] " + storeName + "의 " + request.getProduct())
                    .setBody(request.getMessage().isBlank()
                            ? "지금 " + storeName + "에서 " + request.getProduct() + "을(를) 만나보세요!"
                            : request.getMessage())
                    .addHashtags("#" + storeName.replace(" ", ""))
                    .addHashtags("#" + request.getProduct().replace(" ", ""))
                    .addHashtags("#" + request.getStore().getRegion() + "맛집")
                    .build());
            responseObserver.onCompleted();
        }
    }

    static final class Customer extends CustomerAssistantGrpc.CustomerAssistantImplBase {

        @Override
        public void draftReply(ReviewReplyRequest request, StreamObserver<ReviewReplyResult> responseObserver) {
            int rating = request.getRating();
            String sentiment = rating >= 4 ? "positive" : rating == 3 || rating == 0 ? "neutral" : "negative";
            String reply = switch (sentiment) {
                case "positive" -> "소중한 리뷰 감사합니다! 다음 방문도 만족하실 수 있도록 준비하겠습니다.";
                case "negative" -> "불편을 드려 죄송합니다. 말씀해 주신 부분은 바로 개선하겠습니다.";
                default -> "리뷰 남겨 주셔서 감사합니다. 더 나은 모습으로 보답하겠습니다.";
            };
            responseObserver.onNext(ReviewReplyResult.newBuilder()
                    .setReply("[MOCK] " + request.getStore().getName() + "입니다. " + reply)
                    .setSentiment(sentiment)
                    .build());
            responseObserver.onCompleted();
        }
    }

    /** 요일별 평균으로 단순 예측합니다. */
    static final class Management extends ManagementAssistantGrpc.ManagementAssistantImplBase {

        @Override
        public void forecastSales(ForecastRequest request, StreamObserver<ForecastResult> responseObserver) {
            if (request.getHistoryCount() == 0 || request.getHorizonDays() <= 0) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("history and horizon_days are required").asRuntimeException());
                return;
            }
            List<SalesPoint> history = request.getHistoryList();
            double overall = history.stream().mapToLong(SalesPoint::getAmount).average().orElse(0);
            Map<DayOfWeek, Double> byWeekday = history.stream().collect(Collectors.groupingBy(
                    point -> LocalDate.parse(point.getDate()).getDayOfWeek(),
                    Collectors.averagingLong(SalesPoint::getAmount)));

            LocalDate last = LocalDate.parse(history.get(history.size() - 1).getDate());
            ForecastResult.Builder result = ForecastResult.newBuilder().setModel("mock-weekday-average");
            double total = 0;
            for (int i = 1; i <= request.getHorizonDays(); i++) {
                LocalDate date = last.plusDays(i);
                double predicted = Math.round(byWeekday.getOrDefault(date.getDayOfWeek(), overall));
                total += predicted;
                result.addPoints(ForecastPoint.newBuilder()
                        .setDate(date.toString())
                        .setPredicted(predicted)
                        .setLower(Math.round(predicted * 0.85))
                        .setUpper(Math.round(predicted * 1.15)));
            }
            result.setSummary("[MOCK] 향후 %d일 예상 매출 합계는 약 %,d원입니다."
                    .formatted(request.getHorizonDays(), Math.round(total)));
            responseObserver.onNext(result.build());
            responseObserver.onCompleted();
        }
    }
}
