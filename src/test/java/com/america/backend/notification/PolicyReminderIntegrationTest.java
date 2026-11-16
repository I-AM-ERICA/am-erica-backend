package com.america.backend.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import com.america.backend.ai.AiOrchestrator;
import com.america.backend.ai.AiDtos.PolicyAnswerResponse;
import com.america.backend.ai.AiDtos.PolicyAskRequest;
import com.america.backend.ai.AiDtos.SourceDto;
import com.america.backend.common.ApiException;
import org.springframework.http.HttpStatus;
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
    @MockitoBean AiOrchestrator ai;
    UserAccount owner;
    Store store;
    final LocalDate today = LocalDate.of(2026, 11, 14);

    @BeforeEach void setup() {
        reminders.deleteAll(); policies.deleteAll(); stores.deleteAll(); users.deleteAll();
        reset(sender, ai);
        when(ai.askPolicy(any(), any(), any())).thenReturn(new PolicyAnswerResponse("현재 신청 가능한 지원 정책 검색 결과입니다.",
                List.of(new SourceDto("공식 공고", "https://example.com/current", "지원 안내", 1))));
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

    @Test void manualAndScheduledDeliveriesShareSearchAndContentWithoutChangingSubscription() throws Exception {
        LocalDate now = LocalDate.now(ZoneId.of("Asia/Seoul"));
        policy("현재 접수 중인 정책", "전국", now.minusDays(1), now.plusDays(10));
        mvc.perform(post("/api/me/policy-reminder/send-now")
                .with(jwt().jwt(j -> j.subject(owner.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeId\":" + store.getId() + ",\"email\":\"untrusted@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("owner@example.com"))
                .andExpect(jsonPath("$.content").isNotEmpty()).andReturn();
        assertThat(reminders.findById(owner.getId())).isEmpty();
        service.update(owner.getId(), true, store.getId());
        service.deliver(owner.getId(), now);
        var result = service.sendNow(owner.getId(), store.getId());
        assertThat(service.get(owner.getId()).lastSentDate()).isEqualTo(now);
        assertThat(service.get(owner.getId()).enabled()).isTrue();
        var mails = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, times(3)).send(mails.capture());
        for (var message : mails.getAllValues()) {
            assertThat(message.getTo()).containsExactly("owner@example.com");
            assertThat(message.getText()).isEqualTo(result.content()).contains("현재 접수 중인 정책", "https://example.com/current");
        }
        var queries = ArgumentCaptor.forClass(PolicyAskRequest.class);
        verify(ai, times(3)).askPolicy(eq(owner.getId()), eq(store.getId()), queries.capture());
        assertThat(queries.getAllValues()).allSatisfy(q -> {
            assertThat(q.question()).contains(now.toString(), "공식 사이트", "이전에 안내한 정책");
            assertThat(q.topK()).isEqualTo(5);
        });
        assertThat(queries.getAllValues()).containsOnly(queries.getValue());
    }

    @Test void manualDeliveryRejectsUnauthenticatedForeignStoreAndInvalidInputBeforeSearch() throws Exception {
        mvc.perform(post("/api/me/policy-reminder/send-now").contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeId\":" + store.getId() + "}")).andExpect(status().isUnauthorized());
        UserAccount other = users.save(new UserAccount("other@example.com", null, "다른 사장님"));
        mvc.perform(post("/api/me/policy-reminder/send-now").with(jwt().jwt(j -> j.subject(other.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\":" + store.getId() + "}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/me/policy-reminder/send-now").with(jwt().jwt(j -> j.subject(owner.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ai, sender);
    }

    @Test void failedOrEmptySearchDoesNotSendOrMarkDailyDelivery() {
        service.update(owner.getId(), true, store.getId());
        when(ai.askPolicy(any(), any(), any())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "검색 실패"));
        assertThatThrownBy(() -> service.sendNow(owner.getId(), store.getId())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.deliver(owner.getId(), today)).isInstanceOf(ApiException.class);
        doReturn(new PolicyAnswerResponse(" ", List.of())).when(ai).askPolicy(any(), any(), any());
        assertThatThrownBy(() -> service.sendNow(owner.getId(), store.getId())).hasMessageContaining("검색 결과");
        assertThat(service.get(owner.getId()).lastSentDate()).isNull();
        verifyNoInteractions(sender);
    }

    @Test void manualMailFailureIsReportedWithoutRetryOrChangingSubscription() throws Exception {
        doThrow(new MailSendException("SMTP failure")).when(sender).send(any(SimpleMailMessage.class));
        mvc.perform(post("/api/me/policy-reminder/send-now").with(jwt().jwt(j -> j.subject(owner.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\":" + store.getId() + "}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("MAIL_SEND_FAILED"));
        assertThat(reminders.findById(owner.getId())).isEmpty();
        verify(sender).send(any(SimpleMailMessage.class));
    }

    @Test void optingOutWhileSearchRunsPreventsScheduledEmailAndSearchHoldsNoTransaction() {
        service.update(owner.getId(), true, store.getId());
        when(ai.askPolicy(any(), any(), any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            service.update(owner.getId(), false, null);
            return new PolicyAnswerResponse("검색 결과", List.of(new SourceDto("공식 공고", "https://example.com/policy", "", 1)));
        });
        service.deliver(owner.getId(), today);
        assertThat(service.get(owner.getId()).lastSentDate()).isNull();
        verifyNoInteractions(sender);
    }

    @Test void scheduledRefreshCallsModelAgainNextDayInsteadOfReusingYesterdaysAnswer() {
        service.update(owner.getId(), true, store.getId());
        var sources = List.of(new SourceDto("공식 공고", "https://example.com/policy", "", 1));
        when(ai.askPolicy(any(), any(), any())).thenReturn(
                new PolicyAnswerResponse("첫날의 최신 검색 결과", sources),
                new PolicyAnswerResponse("다음 날 갱신된 최신 검색 결과", sources));
        service.deliver(owner.getId(), today);
        service.deliver(owner.getId(), today.plusDays(1));
        var messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, times(2)).send(messages.capture());
        assertThat(messages.getAllValues().get(0).getText()).contains("첫날의 최신 검색 결과");
        assertThat(messages.getAllValues().get(1).getText()).contains("다음 날 갱신된 최신 검색 결과").doesNotContain("첫날의 최신 검색 결과");
        var requests = ArgumentCaptor.forClass(PolicyAskRequest.class);
        verify(ai, times(2)).askPolicy(eq(owner.getId()), eq(store.getId()), requests.capture());
        assertThat(requests.getAllValues().get(1).question()).contains(today.plusDays(1).toString());
    }

    @Test void mockAndUnverifiableModelResponsesAreNeverEmailed() {
        for (var result : List.of(new PolicyAnswerResponse("[MOCK] 예시 정책", List.of()),
                new PolicyAnswerResponse("근거 없는 정책 안내", List.of()),
                new PolicyAnswerResponse("잘못된 출처", List.of(new SourceDto("출처", "javascript:alert(1)", "", 1))))) {
            when(ai.askPolicy(any(), any(), any())).thenReturn(result);
            assertThatThrownBy(() -> service.sendNow(owner.getId(), store.getId())).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(sender);
    }

    private void policy(String title, String region, LocalDate start, LocalDate end) {
        policies.save(new Policy(title, "지원기관", region, "금융", "정책 안내", "https://example.com/policy", start, end));
    }
}
