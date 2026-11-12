package com.america.backend.data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class DataDtos {

    private DataDtos() {
    }

    public record SaleInput(
            @NotNull LocalDate date,
            @NotNull @PositiveOrZero Long amount,
            @PositiveOrZero Integer orderCount) {
    }

    /** 같은 날짜가 이미 있으면 덮어씁니다. */
    public record SalesUpsertRequest(@NotEmpty @Size(max = 366) List<@Valid @NotNull SaleInput> items) {
    }

    public record SaleResponse(LocalDate date, long amount, int orderCount) {
        public static SaleResponse from(Sale sale) {
            return new SaleResponse(sale.getSaleDate(), sale.getAmount(), sale.getOrderCount());
        }
    }

    public record SalesSummary(LocalDate from, LocalDate to, long totalAmount, long averageAmount,
                               LocalDate bestDay, List<SaleResponse> items) {
    }

    public record ReviewRequest(
            @Size(max = 30) String platform,
            @NotNull @Min(1) @Max(5) Integer rating,
            @NotBlank @Size(max = 2000) String content) {
    }

    public record ReplyRequest(@NotBlank @Size(max = 2000) String reply) {
    }

    public record ReviewResponse(Long id, String platform, int rating, String content, String reply,
                                 Instant createdAt) {
        public static ReviewResponse from(Review review) {
            return new ReviewResponse(review.getId(), review.getPlatform(), review.getRating(),
                    review.getContent(), review.getReply(), review.getCreatedAt());
        }
    }

    public record PolicyRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 100) String agency,
            @NotBlank @Size(max = 50) String region,
            @NotBlank @Size(max = 50) String category,
            @NotBlank @Size(max = 2000) String summary,
            @Size(max = 500) String url,
            LocalDate applyEnd) {
    }

    public record PolicyResponse(Long id, String title, String agency, String region, String category,
                                 String summary, String url, LocalDate applyEnd) {
        public static PolicyResponse from(Policy policy) {
            return new PolicyResponse(policy.getId(), policy.getTitle(), policy.getAgency(),
                    policy.getRegion(), policy.getCategory(), policy.getSummary(), policy.getUrl(),
                    policy.getApplyEnd());
        }
    }
}
