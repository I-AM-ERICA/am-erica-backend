package com.america.backend.data;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.america.backend.common.ApiException;
import com.america.backend.data.DataDtos.PolicyRequest;
import com.america.backend.data.DataDtos.PolicyResponse;
import com.america.backend.data.DataDtos.ReviewRequest;
import com.america.backend.data.DataDtos.ReviewResponse;
import com.america.backend.data.DataDtos.SaleInput;
import com.america.backend.data.DataDtos.SaleResponse;
import com.america.backend.data.DataDtos.SalesSummary;
import com.america.backend.store.Store;
import com.america.backend.store.StoreService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataService {

    private static final int MAX_RANGE_DAYS = 366;

    private final StoreService storeService;
    private final SaleRepository sales;
    private final ReviewRepository reviews;
    private final PolicyRepository policies;

    public DataService(StoreService storeService, SaleRepository sales, ReviewRepository reviews,
                       PolicyRepository policies) {
        this.storeService = storeService;
        this.sales = sales;
        this.reviews = reviews;
        this.policies = policies;
    }

    @Transactional
    public List<SaleResponse> upsertSales(Long userId, Long storeId, List<SaleInput> items) {
        Store store = storeService.requireOwned(userId, storeId);
        Set<LocalDate> seen = new HashSet<>();
        for (SaleInput item : items) {
            if (!seen.add(item.date())) {
                throw ApiException.badRequest("DUPLICATE_DATE", "같은 날짜가 중복되었습니다: " + item.date());
            }
        }
        return items.stream()
                .sorted(Comparator.comparing(SaleInput::date))
                .map(item -> {
                    int orderCount = item.orderCount() == null ? 0 : item.orderCount();
                    Sale sale = sales.findByStoreIdAndSaleDate(storeId, item.date())
                            .map(existing -> {
                                existing.update(item.amount(), orderCount);
                                return existing;
                            })
                            .orElseGet(() -> sales.save(new Sale(store, item.date(), item.amount(), orderCount)));
                    return SaleResponse.from(sale);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public SalesSummary salesSummary(Long userId, Long storeId, LocalDate from, LocalDate to) {
        storeService.requireOwned(userId, storeId);
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end) || start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw ApiException.badRequest("INVALID_RANGE", "조회 기간은 시작일 ≤ 종료일, 최대 366일입니다.");
        }
        List<SaleResponse> items = sales.findByStoreIdAndSaleDateBetweenOrderBySaleDateAsc(storeId, start, end)
                .stream().map(SaleResponse::from).toList();
        long total = items.stream().mapToLong(SaleResponse::amount).sum();
        long average = items.isEmpty() ? 0 : Math.round((double) total / items.size());
        LocalDate bestDay = items.stream().max(Comparator.comparingLong(SaleResponse::amount))
                .map(SaleResponse::date).orElse(null);
        return new SalesSummary(start, end, total, average, bestDay, items);
    }

    /** AI 오케스트레이터용: 권한 확인이 끝난 매장의 최근 매출 이력을 날짜 오름차순으로 반환합니다. */
    @Transactional(readOnly = true)
    public List<Sale> recentSales(Long storeId, int days) {
        LocalDate today = LocalDate.now();
        return sales.findByStoreIdAndSaleDateBetweenOrderBySaleDateAsc(storeId, today.minusDays(days - 1L), today);
    }

    @Transactional
    public ReviewResponse addReview(Long userId, Long storeId, ReviewRequest request) {
        Store store = storeService.requireOwned(userId, storeId);
        Review review = new Review(store, blankToNull(request.platform()), request.rating(), request.content().trim());
        return ReviewResponse.from(reviews.save(review));
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> listReviews(Long userId, Long storeId, int limit) {
        storeService.requireOwned(userId, storeId);
        return reviews.findByStoreIdOrderByCreatedAtDescIdDesc(storeId, PageRequest.of(0, clamp(limit, 1, 100)))
                .stream().map(ReviewResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Review requireReview(Long storeId, Long reviewId) {
        return reviews.findByIdAndStoreId(reviewId, storeId)
                .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "리뷰를 찾을 수 없습니다."));
    }

    @Transactional
    public ReviewResponse saveReply(Long userId, Long storeId, Long reviewId, String reply) {
        storeService.requireOwned(userId, storeId);
        Review review = requireReview(storeId, reviewId);
        review.reply(reply.trim());
        return ReviewResponse.from(review);
    }

    @Transactional(readOnly = true)
    public long unansweredReviewCount(Long storeId) {
        return reviews.countByStoreIdAndReplyIsNull(storeId);
    }

    @Transactional(readOnly = true)
    public List<PolicyResponse> searchPolicies(String region, String keyword, int limit) {
        boolean allRegions = region == null || region.isBlank();
        String pattern = keyword == null || keyword.isBlank()
                ? "%"
                : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        return policies.search(allRegions, allRegions ? "" : region.trim(), pattern,
                        PageRequest.of(0, clamp(limit, 1, 100)))
                .stream().map(PolicyResponse::from).toList();
    }

    @Transactional
    public PolicyResponse addPolicy(PolicyRequest request) {
        if (request.applyStart() != null && request.applyEnd() != null
                && request.applyStart().isAfter(request.applyEnd())) {
            throw ApiException.badRequest("INVALID_POLICY_PERIOD", "접수 시작일은 마감일보다 늦을 수 없습니다.");
        }
        Policy policy = new Policy(request.title().trim(), request.agency().trim(), request.region().trim(),
                request.category().trim(), request.summary().trim(), blankToNull(request.url()), request.applyStart(), request.applyEnd());
        return PolicyResponse.from(policies.save(policy));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
