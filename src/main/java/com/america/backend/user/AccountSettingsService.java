package com.america.backend.user;

import java.util.Map;
import java.util.Set;
import com.america.backend.common.ApiException;
import com.america.backend.store.Store;
import com.america.backend.store.StoreRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountSettingsService {
    public record StoreProfile(Long id, String name, String owner, String category, String region) {}
    public record Snapshot(StoreProfile store, Map<String, String> staffPreferences) {}
    public record ProfileInput(@NotBlank @Size(max = 100) String name,
                               @NotBlank @Size(max = 50) String owner,
                               @NotBlank @Size(max = 50) String category,
                               @NotBlank @Size(max = 50) String region) {}
    private final UserRepository users;
    private final StoreRepository stores;
    private final AccountSettingsRepository settings;
    public AccountSettingsService(UserRepository users, StoreRepository stores, AccountSettingsRepository settings) {
        this.users = users; this.stores = stores; this.settings = settings;
    }
    @Transactional(readOnly = true)
    public Snapshot get(Long userId) {
        UserAccount user = users.findById(userId).orElseThrow(() -> missingUser());
        AccountSettings saved = settings.findById(userId).orElse(null);
        return snapshot(user, saved, currentStore(userId, saved));
    }
    @Transactional
    public Snapshot saveProfile(Long userId, ProfileInput input) {
        UserAccount user = lock(userId);
        AccountSettings saved = settings.findById(userId).orElseGet(() -> new AccountSettings(user));
        Store store = currentStore(userId, saved);
        if (store == null) {
            store = stores.save(new Store(user, input.name().trim(), input.category().trim(), input.region().trim(), null));
        } else {
            store.update(input.name().trim(), input.category().trim(), input.region().trim(), store.getAddress());
        }
        saved.setStore(store, input.owner().trim());
        settings.save(saved);
        return snapshot(user, saved, store);
    }
    @Transactional
    public Map<String, String> saveGender(Long userId, String assistant, String gender) {
        if (!Set.of("policy", "marketing", "customer", "sales").contains(assistant))
            throw ApiException.badRequest("INVALID_ASSISTANT", "비서 종류를 확인해 주세요.");
        if (!Set.of("boy", "girl").contains(gender))
            throw ApiException.badRequest("INVALID_GENDER", "스태프 선택값을 확인해 주세요.");
        UserAccount user = lock(userId);
        AccountSettings saved = settings.findById(userId).orElseGet(() -> new AccountSettings(user));
        saved.setGender(assistant, gender);
        settings.save(saved);
        return preferences(saved);
    }
    private UserAccount lock(Long userId) { return users.lockById(userId).orElseThrow(() -> missingUser()); }
    private static ApiException missingUser() { return ApiException.notFound("USER_NOT_FOUND", "사용자를 찾을 수 없습니다."); }
    private Store currentStore(Long userId, AccountSettings saved) {
        if (saved != null && saved.getStore() != null) {
            return stores.findByIdAndOwnerId(saved.getStore().getId(), userId)
                    .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "매장을 찾을 수 없습니다."));
        }
        // Adopt the most recently created owned store from the earlier policy-chat registration flow.
        return stores.findFirstByOwnerIdOrderByIdDesc(userId).orElse(null);
    }
    private Snapshot snapshot(UserAccount user, AccountSettings saved, Store store) {
        String owner = saved == null || saved.getOwnerName() == null ? user.getName() : saved.getOwnerName();
        return new Snapshot(store == null ? null : new StoreProfile(store.getId(), store.getName(), owner,
                store.getCategory(), store.getRegion()), preferences(saved));
    }
    private Map<String, String> preferences(AccountSettings saved) {
        return saved == null ? Map.of("policy", "boy", "marketing", "girl", "customer", "girl", "sales", "boy")
                : Map.of("policy", saved.getPolicyGender(), "marketing", saved.getMarketingGender(),
                         "customer", saved.getCustomerGender(), "sales", saved.getSalesGender());
    }
}
