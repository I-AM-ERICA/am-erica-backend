package com.america.backend.data;

import java.time.LocalDate;
import java.util.List;

import com.america.backend.auth.CurrentUser;
import com.america.backend.data.DataDtos.PolicyRequest;
import com.america.backend.data.DataDtos.PolicyResponse;
import com.america.backend.data.DataDtos.ReplyRequest;
import com.america.backend.data.DataDtos.ReviewRequest;
import com.america.backend.data.DataDtos.ReviewResponse;
import com.america.backend.data.DataDtos.SaleResponse;
import com.america.backend.data.DataDtos.SalesSummary;
import com.america.backend.data.DataDtos.SalesUpsertRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "3. 데이터", description = "Data Service · 매출 · 리뷰 · 정책")
@RestController
public class DataController {

    private final DataService dataService;

    public DataController(DataService dataService) {
        this.dataService = dataService;
    }

    @Operation(summary = "일 매출 등록/수정 (같은 날짜는 덮어씀)")
    @PutMapping("/api/stores/{storeId}/sales")
    public List<SaleResponse> upsertSales(@AuthenticationPrincipal Jwt jwt, @PathVariable Long storeId,
                                          @Valid @RequestBody SalesUpsertRequest request) {
        return dataService.upsertSales(CurrentUser.id(jwt), storeId, request.items());
    }

    @Operation(summary = "매출 조회 및 요약 (기본: 최근 30일)")
    @GetMapping("/api/stores/{storeId}/sales")
    public SalesSummary sales(@AuthenticationPrincipal Jwt jwt, @PathVariable Long storeId,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dataService.salesSummary(CurrentUser.id(jwt), storeId, from, to);
    }

    @Operation(summary = "리뷰 등록")
    @PostMapping("/api/stores/{storeId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewResponse addReview(@AuthenticationPrincipal Jwt jwt, @PathVariable Long storeId,
                                    @Valid @RequestBody ReviewRequest request) {
        return dataService.addReview(CurrentUser.id(jwt), storeId, request);
    }

    @Operation(summary = "리뷰 목록 (최신순)")
    @GetMapping("/api/stores/{storeId}/reviews")
    public List<ReviewResponse> reviews(@AuthenticationPrincipal Jwt jwt, @PathVariable Long storeId,
                                        @RequestParam(defaultValue = "20") int limit) {
        return dataService.listReviews(CurrentUser.id(jwt), storeId, limit);
    }

    @Operation(summary = "리뷰 답글 저장")
    @PutMapping("/api/stores/{storeId}/reviews/{reviewId}/reply")
    public ReviewResponse reply(@AuthenticationPrincipal Jwt jwt, @PathVariable Long storeId,
                                @PathVariable Long reviewId, @Valid @RequestBody ReplyRequest request) {
        return dataService.saveReply(CurrentUser.id(jwt), storeId, reviewId, request.reply());
    }

    @Operation(summary = "지원 정책 검색 (region 지정 시 해당 지역 + 전국)")
    @GetMapping("/api/policies")
    public List<PolicyResponse> policies(@RequestParam(required = false) String region,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "20") int limit) {
        return dataService.searchPolicies(region, keyword, limit);
    }

    @Operation(summary = "지원 정책 등록")
    @PostMapping("/api/policies")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyResponse addPolicy(@Valid @RequestBody PolicyRequest request) {
        return dataService.addPolicy(request);
    }
}
