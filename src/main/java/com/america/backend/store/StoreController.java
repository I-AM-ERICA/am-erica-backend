package com.america.backend.store;

import java.util.List;

import com.america.backend.auth.CurrentUser;
import com.america.backend.store.StoreDtos.StoreRequest;
import com.america.backend.store.StoreDtos.StoreResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "2. 매장", description = "Store Service · 매장 관리")
@RestController
@RequestMapping("/api/stores")
public class StoreController {

    private final StoreService storeService;

    public StoreController(StoreService storeService) {
        this.storeService = storeService;
    }

    @Operation(summary = "매장 생성")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StoreResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StoreRequest request) {
        return storeService.create(CurrentUser.id(jwt), request);
    }

    @Operation(summary = "내 매장 목록")
    @GetMapping
    public List<StoreResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return storeService.list(CurrentUser.id(jwt));
    }

    @Operation(summary = "매장 상세")
    @GetMapping("/{storeId}")
    public StoreResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long storeId) {
        return storeService.get(CurrentUser.id(jwt), storeId);
    }

    @Operation(summary = "매장 정보 수정")
    @PutMapping("/{storeId}")
    public StoreResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long storeId,
                                @Valid @RequestBody StoreRequest request) {
        return storeService.update(CurrentUser.id(jwt), storeId, request);
    }
}
