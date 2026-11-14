package com.america.backend.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.UUID;
import com.america.backend.data.Policy;
import com.america.backend.data.PolicyRepository;
import com.america.backend.store.Store;
import com.america.backend.store.StoreRepository;
import com.america.backend.user.UserAccount;
import com.america.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:reminders;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop", "app.jwt.secret=test-secret-test-secret-test-secret-1234",
        "ai.grpc.internal-key=test-internal-key", "ai.mock.enabled=false",
        "management.health.mail.enabled=false", "app.policy-reminder.enabled=true", "app.policy-reminder.from=sender@example.com",
        "spring.mail.username=sender@example.com", "spring.mail.password=test-password"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PolicyReminderIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired PolicyReminderService service;
    @Autowired PolicyReminderScheduler scheduler;
    @Autowired PolicyReminderRepository reminders;
    @Autowired PolicyRepository policies;
    @Autowired StoreRepository stores;
    @Autowired UserRepository users;
    @MockitoBean JavaMailSender sender;
    UserAccount owner;
    Store store;
    final LocalDate today = LocalDate.of(2026, 11, 14);

    @BeforeEach void setup() {
        reminders.deleteAll(); policies.deleteAll(); stores.deleteAll(); users.deleteAll();
        reset(sender);
        owner = users.save(new UserAccount("owner@example.com", null, "사장님"));
        store = stores.save(new Store(owner, "오후의 커피", "카페", "서울 강남구", null));
    }

    @Test void settingsRequireAuthenticationAndStayPrivateAndPersist() throws Exception {
        mvc.perform(get("/api/me/policy-reminder")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/policy-reminder").with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.email").value("owner@example.com"));
        mvc.perform(put("/api/me/policy-reminder").with(jwt().jwt(j -> j.subject(owner.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"storeId\":" + store.getId() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        assertThat(service.get(owner.getId()).enabled()).isTrue();
        UserAccount other = users.save(new UserAccount("other@example.com", null, "다른 사장님"));
        mvc.perform(put("/api/me/policy-reminder").with(jwt().jwt(j -> j.subject(other.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"storeId\":" + store.getId() + "}"))
                .andExpect(status().isNotFound());
        assertThat(service.get(other.getId()).enabled()).isFalse();
        mvc.perform(put("/api/me/policy-reminder").with(jwt().jwt(j -> j.subject(owner.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        service.update(owner.getId(), false, null);
        assertThat(service.get(owner.getId()).enabled()).isFalse();
        verifyNoInteractions(sender);
    }

    @Test void missingEmailCannotSubscribeButCanTurnOff() {
        UserAccount noEmail = users.save(UserAccount.fromNaver(UUID.randomUUID().toString(), null, "사장님"));
        assertThatThrownBy(() -> service.update(noEmail.getId(), true, store.getId())).hasMessageContaining("이메일");
        assertThat(service.update(noEmail.getId(), false, null).enabled()).isFalse();
    }

    @Test void sendsAllOpenRegionalPoliciesIncludingDeadlineAndRepeatsTomorrow() {
        policy("오늘 마감", "전국", today.minusDays(10), today);
        policy("계속 접수", "서울", today, today.plusDays(3));
        policy("우리 구 정책", "서울 강남구", null, today.plusDays(3));
        policy("마감된 정책", "전국", null, today.minusDays(1));
        policy("미래 접수", "전국", today.plusDays(3), today.plusDays(4));
        policy("마감 미정", "전국", null, null);
        policy("다른 지역", "부산", null, today.plusDays(5));
        for (int i = 0; i < 105; i++) policy("추가 정책 " + i, "전국", null, today.plusDays(3));
        service.update(owner.getId(), true, store.getId());
        service.deliver(owner.getId(), today);
        service.deliver(owner.getId(), today);
        service.update(owner.getId(), false, null);
        service.update(owner.getId(), true, store.getId());
        service.deliver(owner.getId(), today); // toggling cannot send twice in the same day
        service.deliver(owner.getId(), today.plusDays(1));
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, times(2)).send(captor.capture());
        SimpleMailMessage first = captor.getAllValues().get(0);
        assertThat(first.getTo()).containsExactly("owner@example.com");
        assertThat(first.getFrom()).isEqualTo("sender@example.com");
        assertThat(first.getSubject()).contains("108건");
        assertThat(first.getText()).contains("오늘 마감", "계속 접수", "우리 구 정책", "추가 정책 104", "OFF")
                .doesNotContain("마감된 정책", "미래 접수", "마감 미정", "다른 지역");
        assertThat(captor.getAllValues().get(1).getText()).contains("계속 접수").doesNotContain("오늘 마감");
        assertThat(service.get(owner.getId()).lastSentDate()).isEqualTo(today.plusDays(1));
    }

    @Test void emptyDayStillSendsAndOptOutStopsDelivery() {
        service.update(owner.getId(), true, store.getId());
        service.deliver(owner.getId(), today);
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getText()).contains("진행 중 정책이 없습니다");
        service.update(owner.getId(), false, null);
        service.deliver(owner.getId(), today.plusDays(1));
        verifyNoMoreInteractions(sender);
    }

    @Test void mailFailureDoesNotMarkSentAndOtherRecipientsStillReceive() {
        UserAccount other = users.save(new UserAccount("other@example.com", null, "사장님"));
        Store second = stores.save(new Store(other, "다른 가게", "카페", "서울", null));
        service.update(owner.getId(), true, store.getId());
        service.update(other.getId(), true, second.getId());
        doThrow(new MailSendException("test failure")).doNothing().when(sender).send(any(SimpleMailMessage.class));
        scheduler.sendDaily();
        assertThat(service.get(owner.getId()).lastSentDate()).isNull();
        assertThat(service.get(other.getId()).lastSentDate()).isNotNull();
        verify(sender, times(2)).send(any(SimpleMailMessage.class));
    }

    @Test void scheduleUsesSixAmKoreaTime() throws Exception {
        Scheduled scheduled = PolicyReminderScheduler.class.getMethod("sendDaily").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 0 6 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }

    private void policy(String title, String region, LocalDate start, LocalDate end) {
        policies.save(new Policy(title, "지원기관", region, "금융", "정책 안내", "https://example.com/policy", start, end));
    }
}
