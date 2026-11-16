package com.america.backend.user;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import com.america.backend.store.Store;
import com.america.backend.store.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:account-settings;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop", "app.jwt.secret=test-secret-test-secret-test-secret-1234",
    "ai.grpc.internal-key=test-internal-key", "ai.mock.enabled=false", "app.policy-reminder.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AccountSettingsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AccountSettingsService service;
    @Autowired AccountSettingsRepository settings;
    @Autowired StoreRepository stores;
    @Autowired UserRepository users;
    UserAccount first;
    UserAccount second;
    @BeforeEach void setup() {
        settings.deleteAll(); stores.deleteAll(); users.deleteAll();
        first = users.save(new UserAccount("one@example.com", null, "첫 사장님"));
        second = users.save(new UserAccount("two@example.com", null, "두번째 사장님"));
    }
    @Test void freshAccountHasNoStoreAndDefaultStaffWithoutCreatingData() throws Exception {
        mvc.perform(get("/api/me/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/settings").with(jwt().jwt(j -> j.subject(first.getId().toString()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.store").doesNotExist())
            .andExpect(jsonPath("$.staffPreferences.policy").value("boy"))
            .andExpect(jsonPath("$.staffPreferences.marketing").value("girl"));
        assertThat(settings.count()).isZero(); assertThat(stores.count()).isZero();
    }
    @Test void profileAndStaffSurviveFreshReadAndRemainAccountScoped() throws Exception {
        mvc.perform(put("/api/me/store-profile").with(jwt().jwt(j -> j.subject(first.getId().toString())))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\" 오후의 커피 \",\"owner\":\" 김사장 \",\"category\":\"카페·베이커리\",\"region\":\"지역 미설정\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.store.name").value("오후의 커피"));
        long storeId = service.get(first.getId()).store().id();
        mvc.perform(patch("/api/me/staff-preferences/policy").with(jwt().jwt(j -> j.subject(first.getId().toString())))
            .contentType(MediaType.APPLICATION_JSON).content("{\"gender\":\"girl\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.policy").value("girl"));
        mvc.perform(get("/api/me/settings").with(jwt().jwt(j -> j.subject(first.getId().toString()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.store.id").value(storeId))
            .andExpect(jsonPath("$.store.owner").value("김사장"))
            .andExpect(jsonPath("$.staffPreferences.policy").value("girl"));
        assertThat(service.get(second.getId()).store()).isNull();
        assertThat(service.get(second.getId()).staffPreferences().get("policy")).isEqualTo("boy");
        service.saveProfile(first.getId(), profile("바뀐 이름"));
        assertThat(service.get(first.getId()).store().id()).isEqualTo(storeId);
        assertThat(stores.count()).isEqualTo(1);
        assertThat(service.get(first.getId()).staffPreferences().get("policy")).isEqualTo("girl");
    }
    @Test void existingOwnedStoreIsReusedAndOtherAccountsStoresCannotBeAdopted() {
        Store owned = stores.save(new Store(first, "기존 가게", "카페", "서울", "기존 주소"));
        stores.save(new Store(second, "다른 사람 가게", "카페", "서울", null));
        assertThat(service.get(first.getId()).store().id()).isEqualTo(owned.getId());
        service.saveProfile(first.getId(), profile("변경된 가게"));
        assertThat(stores.count()).isEqualTo(2);
        assertThat(stores.findById(owned.getId()).orElseThrow().getAddress()).isEqualTo("기존 주소");
        assertThat(service.get(second.getId()).store().name()).isEqualTo("다른 사람 가게");
    }
    @Test void invalidInputDoesNotOverwriteSavedValues() throws Exception {
        service.saveProfile(first.getId(), profile("보관할 가게"));
        mvc.perform(put("/api/me/store-profile").with(jwt().jwt(j -> j.subject(first.getId().toString())))
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \",\"owner\":\"사장\",\"category\":\"카페\",\"region\":\"서울\"}"))
            .andExpect(status().isBadRequest());
        for (String body : new String[]{"{}", "{\"gender\":\"invalid\"}"}) {
            mvc.perform(patch("/api/me/staff-preferences/policy").with(jwt().jwt(j -> j.subject(first.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/api/me/staff-preferences/unknown").with(jwt().jwt(j -> j.subject(first.getId().toString())))
            .contentType(MediaType.APPLICATION_JSON).content("{\"gender\":\"boy\"}")).andExpect(status().isBadRequest());
        assertThat(service.get(first.getId()).store().name()).isEqualTo("보관할 가게");
        assertThat(service.get(first.getId()).staffPreferences().get("policy")).isEqualTo("boy");
    }
    @Test void concurrentFirstSavesCreateOneStoreAndKeepIndependentStaffChanges() {
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<?> a = CompletableFuture.runAsync(() -> { await(start); service.saveProfile(first.getId(), profile("동시 저장")); });
        CompletableFuture<?> b = CompletableFuture.runAsync(() -> { await(start); service.saveProfile(first.getId(), profile("동시 저장")); });
        start.countDown(); CompletableFuture.allOf(a, b).join();
        assertThat(stores.count()).isEqualTo(1); assertThat(settings.count()).isEqualTo(1);
        CompletableFuture.allOf(
            CompletableFuture.runAsync(() -> service.saveGender(first.getId(), "policy", "girl")),
            CompletableFuture.runAsync(() -> service.saveGender(first.getId(), "marketing", "boy"))
        ).join();
        assertThat(service.get(first.getId()).staffPreferences()).containsEntry("policy", "girl").containsEntry("marketing", "boy");
    }
    private static AccountSettingsService.ProfileInput profile(String name) {
        return new AccountSettingsService.ProfileInput(name, "사장님", "카페", "서울");
    }
    private static void await(CountDownLatch latch) { try { latch.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); } }
}
