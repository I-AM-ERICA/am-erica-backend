package com.america.backend.store;

import java.util.List;

import com.america.backend.common.ApiException;
import com.america.backend.store.StoreDtos.StoreRequest;
import com.america.backend.store.StoreDtos.StoreResponse;
import com.america.backend.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreService {

    private final StoreRepository stores;
    private final UserRepository users;

    public StoreService(StoreRepository stores, UserRepository users) {
        this.stores = stores;
        this.users = users;
    }

    /** 본인 소유 매장만 반환합니다. 다른 사용자의 매장은 존재 여부를 드러내지 않도록 404로 응답합니다. */
    @Transactional(readOnly = true)
    public Store requireOwned(Long userId, Long storeId) {
        return stores.findByIdAndOwnerId(storeId, userId)
                .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "매장을 찾을 수 없습니다."));
    }

    @Transactional
    public StoreResponse create(Long userId, StoreRequest request) {
        Store store = new Store(users.getReferenceById(userId), request.name().trim(),
                request.category().trim(), request.region().trim(), blankToNull(request.address()));
        return StoreResponse.from(stores.save(store));
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> list(Long userId) {
        return stores.findByOwnerIdOrderByIdAsc(userId).stream().map(StoreResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public StoreResponse get(Long userId, Long storeId) {
        return StoreResponse.from(requireOwned(userId, storeId));
    }

    @Transactional
    public StoreResponse update(Long userId, Long storeId, StoreRequest request) {
        Store store = requireOwned(userId, storeId);
        store.update(request.name().trim(), request.category().trim(), request.region().trim(),
                blankToNull(request.address()));
        return StoreResponse.from(store);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
