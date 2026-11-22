package com.america.backend.ai;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.america.ai.v1.ForecastPoint;
import com.america.ai.v1.PolicyAnswer;
import com.america.ai.v1.PosterResult;
import com.america.ai.v1.PosterDraft;
import com.america.ai.v1.PosterDrafts;
import com.america.ai.v1.PosterImage;
import com.google.protobuf.ByteString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import com.america.ai.v1.ReviewReplyResult;
import com.america.ai.v1.Source;
import com.america.backend.ai.grpc.AssistantType;
import com.america.backend.common.ApiException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;

public final class AiDtos {

    private AiDtos() {
    }

    // ---------- 정책 ----------

    public record PolicyAskRequest(
            @NotBlank @Size(max = 1000) String question,
            @Min(1) @Max(5) Integer topK,
            @Size(max = 8) List<@NotNull @Valid PolicyChatTurn> history) {

        public PolicyAskRequest(String question, Integer topK) {
            this(question, topK, null);
        }

        public String contextualQuestion() {
            if (history == null || history.isEmpty()) return question;
            StringBuilder prompt = new StringBuilder("다음은 같은 사용자의 이전 대화입니다. 참고하여 마지막 질문에 답하세요.\n<conversation>\n");
            for (PolicyChatTurn turn : history) {
                prompt.append("user".equals(turn.role()) ? "사용자: " : "정책 비서: ")
                        .append(turn.content()).append("\n\n");
            }
            return prompt.append("</conversation>\n현재 사용자 질문: ").append(question).toString();
        }
    }

    public record PolicyChatTurn(
            @NotBlank @Pattern(regexp = "user|assistant") String role,
            @NotBlank @Size(max = 2000) String content) {
    }

    public record SourceDto(String title, String url, String snippet, double score) {
        static SourceDto from(Source source) {
            return new SourceDto(source.getTitle(), source.getUrl(), source.getSnippet(), source.getScore());
        }
    }

    public record PolicyAnswerResponse(String answer, List<SourceDto> sources) {
        static PolicyAnswerResponse from(PolicyAnswer answer) {
            return new PolicyAnswerResponse(answer.getAnswer(),
                    answer.getSourcesList().stream().map(SourceDto::from).toList());
        }
    }

    // ---------- 홍보 ----------

    public record PosterCreateRequest(
            @NotBlank @Size(max = 100) String product,
            @Size(max = 500) String message,
            @Size(max = 30) String tone,
            @Size(max = 30) String channel,
            boolean generateImage,
            @Valid PosterImageInput productImage,
            @Valid PosterImageInput logo,
            @Valid PosterImageInput styleReference,
            @Min(0) @Max(999999999) Long price,
            @Min(0) @Max(999999999) Long originalPrice,
            @Size(max = 60) String period,
            @Size(max = 100) String contact,
            @Pattern(regexp = "auto|remove|keep") String backgroundMode,
            boolean hideFooter) {
        public PosterCreateRequest(String product, String message, String tone, String channel, boolean generateImage) {
            this(product, message, tone, channel, generateImage, null, null, null, null, null, null, null, null, false);
        }
    }

    public record PosterImageInput(
            @NotBlank @Size(max = 6990508) String base64,
            @NotBlank @Pattern(regexp = "image/(png|jpeg|webp)") String mediaType) {
        PosterImage toProto() {
            try {
                byte[] bytes = Base64.getDecoder().decode(base64);
                if (bytes.length == 0 || bytes.length > 5 * 1024 * 1024) throw new IllegalArgumentException();
                return PosterImage.newBuilder().setData(ByteString.copyFrom(bytes)).setMediaType(mediaType).build();
            } catch (IllegalArgumentException error) {
                throw ApiException.badRequest("INVALID_POSTER_IMAGE", "이미지는 PNG/JPG/WEBP, 5MB 이하로 올려 주세요.");
            }
        }
    }

    public record PosterRevisionInput(@NotBlank @Size(max = 500) String instruction,
                                      @NotNull @Min(1) Integer expectedVersion) {}

    public record PosterDraftResponse(String id, int version, PosterResponse result, String mode,
                                      int width, int height, JsonNode design, JsonNode imageProcessing) {
        private static final ObjectMapper JSON = new ObjectMapper();
        static PosterDraftResponse from(PosterDraft draft) {
            try {
                return new PosterDraftResponse(draft.getId(), draft.getVersion(), PosterResponse.from(draft.getResult()),
                        draft.getMode(), draft.getWidth(), draft.getHeight(),
                        JSON.readTree(draft.getDesignJson().isBlank() ? "{}" : draft.getDesignJson()),
                        JSON.readTree(draft.getImageProcessingJson().isBlank() ? "{}" : draft.getImageProcessingJson()));
            } catch (java.io.IOException error) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_ERROR", "포스터 디자인 응답을 확인하지 못했습니다.");
            }
        }
    }

    public record PosterDraftsResponse(List<PosterDraftResponse> drafts, String mode) {
        static PosterDraftsResponse from(PosterDrafts response) {
            return new PosterDraftsResponse(response.getDraftsList().stream().map(PosterDraftResponse::from).toList(), response.getMode());
        }
    }

    public record PosterResponse(String headline, String body, List<String> hashtags, String imageUrl) {
        static PosterResponse from(PosterResult result) {
            return new PosterResponse(result.getHeadline(), result.getBody(), List.copyOf(result.getHashtagsList()),
                    result.getImageUrl().isEmpty() ? null : result.getImageUrl());
        }
    }

    // ---------- 고객 응대 ----------

    /** reviewId 또는 reviewText 중 하나가 필요합니다. reviewId가 있으면 저장된 리뷰를 사용합니다. */
    public record ReplyDraftRequest(
            Long reviewId,
            @Size(max = 2000) String reviewText,
            @Min(0) @Max(5) Integer rating,  // 0 = 별점 모름 (프론트의 "모름 / 선택 안 함")
            @Size(max = 30) String tone,
            @Size(max = 2000) String storeInfo) {
    }

    public record ReplyDraftResponse(Long reviewId, String reply, String sentiment, List<String> keywords) {
        static ReplyDraftResponse from(Long reviewId, ReviewReplyResult result) {
            return new ReplyDraftResponse(reviewId, result.getReply(), result.getSentiment(),
                    List.copyOf(result.getKeywordsList()));
        }
    }

    // ---------- 경영 ----------

    public record ForecastCreateRequest(@Min(1) @Max(30) Integer horizonDays,
                                        @Size(min = 1, max = 512) List<@NotNull @Valid ForecastHistoryPoint> history) {
        public ForecastCreateRequest(Integer horizonDays) { this(horizonDays, null); }
    }

    public record ForecastHistoryPoint(@NotNull @jakarta.validation.constraints.PastOrPresent LocalDate date,
                                       @NotNull @Min(0) @Max(9_007_199_254_740_991L) Long amount) {
    }

    public record ForecastPointDto(LocalDate date, double predicted, double lower, double upper) {
        static ForecastPointDto from(ForecastPoint point) {
            LocalDate date;
            try {
                date = LocalDate.parse(point.getDate());
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_ERROR",
                        "경영 AI 비서가 잘못된 날짜 형식을 반환했습니다.");
            }
            return new ForecastPointDto(date, point.getPredicted(), point.getLower(), point.getUpper());
        }
    }

    public record ForecastResponse(int historyDays, List<ForecastPointDto> points, String summary, String model) {
    }

    // ---------- 통합 브리핑 ----------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Section<T>(String status, T data, String errorCode, String message) {
        static <T> Section<T> ok(T data) {
            return new Section<>("ok", data, null, null);
        }

        static <T> Section<T> failed(ApiException e) {
            return new Section<>("failed", null, e.getCode(), e.getMessage());
        }
    }

    public record BriefingResponse(
            Long storeId,
            Section<ForecastResponse> salesForecast,
            Section<PolicyAnswerResponse> policy,
            long unansweredReviews) {
    }

    // ---------- 이력 ----------

    public record AiLogResponse(Long id, AssistantType assistant, String operation, boolean success,
                                String errorCode, long latencyMs, Instant createdAt) {
        static AiLogResponse from(AiLog log) {
            return new AiLogResponse(log.getId(), log.getAssistant(), log.getOperation(), log.isSuccess(),
                    log.getErrorCode(), log.getLatencyMs(), log.getCreatedAt());
        }
    }
}
